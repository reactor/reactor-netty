/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty5.http.server;

import io.netty5.buffer.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.handler.codec.http2.Http2StreamChannel;
import io.netty5.util.concurrent.Future;
import reactor.netty5.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Function;

import static reactor.netty5.ReactorNetty.format;

/**
 * @author Violeta Georgieva
 * @since 1.0.8
 */
abstract class AbstractHttpServerMetricsHandler extends ChannelHandlerAdapter {

	private static final Logger log = Loggers.getLogger(AbstractHttpServerMetricsHandler.class);

	boolean channelActivated;

	long dataReceived;

	long dataSent;

	long dataReceivedTime;

	long dataSentTime;

	final Function<String, String> uriTagValue;

	protected AbstractHttpServerMetricsHandler(@Nullable Function<String, String> uriTagValue) {
		this.uriTagValue = uriTagValue;
	}

	protected AbstractHttpServerMetricsHandler(AbstractHttpServerMetricsHandler copy) {
		this.channelActivated = copy.channelActivated;
		this.dataReceived = copy.dataReceived;
		this.dataReceivedTime = copy.dataReceivedTime;
		this.dataSent = copy.dataSent;
		this.dataSentTime = copy.dataSentTime;
		this.uriTagValue = copy.uriTagValue;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		// For custom user recorders, we don't propagate the channelActive event, because this will be done
		// by the ChannelMetricsHandler itself. ChannelMetricsHandler is only present when the recorder is
		// not our MicrometerHttpServerMetricsRecorder. See HttpServerConfig class.
		if (!(ctx.channel() instanceof Http2StreamChannel) && recorder() instanceof MicrometerHttpServerMetricsRecorder) {
			try {
				// Always use the real connection local address without any proxy information
				recorder().recordServerConnectionOpened(ctx.channel().localAddress());
			}
			catch (RuntimeException e) {
				if (log.isWarnEnabled()) {
					log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				}
				// Allow request-response exchange to continue, unaffected by metrics problem
			}
		}
		ctx.fireChannelActive();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		if (!(ctx.channel() instanceof Http2StreamChannel) && recorder() instanceof MicrometerHttpServerMetricsRecorder) {
			try {
				// Always use the real connection local address without any proxy information
				recorder().recordServerConnectionClosed(ctx.channel().localAddress());
			}
			catch (RuntimeException e) {
				if (log.isWarnEnabled()) {
					log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				}
				// Allow request-response exchange to continue, unaffected by metrics problem
			}
		}

		recordInactiveConnectionOrStream(ctx.channel());

		ctx.fireChannelInactive();
	}

	@Override
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof HttpResponse httpResponse) {
				if (httpResponse.status().equals(HttpResponseStatus.CONTINUE)) {
					return ctx.write(msg);
				}

				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations ops) {
					startWrite(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path),
							ops.method().name(), ops.status().codeAsText().toString());
				}
			}

			dataSent += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				// The listeners are now invoked asynchronously (see https://github.com/netty/netty/pull/9489),
				// and it seems we need to first obtain the channelOps, which may not be present anymore
				// when the listener will be invoked.
				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				return ctx.write(msg)
						.addListener(future -> {
								if (channelOps instanceof HttpServerOperations ops) {
									try {
										recordWrite(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path),
												ops.method().name(), ops.status().codeAsText().toString());
									}
									catch (RuntimeException e) {
										if (log.isWarnEnabled()) {
											log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
										}
										// Allow request-response exchange to continue, unaffected by metrics problem
									}
								}

							recordInactiveConnectionOrStream(ctx.channel());

							dataSent = 0;
						});
			}
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
			// Allow request-response exchange to continue, unaffected by metrics problem
		}
		return ctx.write(msg);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof HttpRequest) {
				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations ops) {
					startRead(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path), ops.method().name());
				}

				channelActivated = true;
				if (ctx.channel() instanceof Http2StreamChannel) {
					// Always use the real connection local address without any proxy information
					recordOpenStream(ctx.channel().localAddress());
				}
				else {
					// Always use the real connection local address without any proxy information
					recordActiveConnection(ctx.channel().localAddress());
				}
			}

			dataReceived += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations ops) {
					recordRead(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path), ops.method().name());
				}

				dataReceived = 0;
			}
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
			// Allow request-response exchange to continue, unaffected by metrics problem
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		try {
			ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
			if (channelOps instanceof HttpServerOperations ops) {
				// Always take the remote address from the operations in order to consider proxy information
				recordException(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path));
			}
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
			// Allow request-response exchange to continue, unaffected by metrics problem
		}

		ctx.fireChannelExceptionCaught(cause);
	}

	private long extractProcessedDataFromBuffer(Object msg) {
		if (msg instanceof Buffer buffer) {
			return buffer.readableBytes();
		}
		else if (msg instanceof HttpContent<?> httpContent) {
			return httpContent.payload().readableBytes();
		}
		return 0;
	}

	protected abstract HttpServerMetricsRecorder recorder();

	protected void recordException(HttpServerOperations ops, String path) {
		// Always take the remote address from the operations in order to consider proxy information
		// Use remoteSocketAddress() in order to obtain UDS info
		recorder().incrementErrorsCount(ops.remoteSocketAddress(), path);
	}

	protected void recordRead(HttpServerOperations ops, String path, String method) {
		recorder().recordDataReceivedTime(path, method, Duration.ofNanos(System.nanoTime() - dataReceivedTime));

		// Always take the remote address from the operations in order to consider proxy information
		// Use remoteSocketAddress() in order to obtain UDS info
		recorder().recordDataReceived(ops.remoteSocketAddress(), path, dataReceived);
	}

	protected void recordWrite(HttpServerOperations ops, String path, String method, String status) {
		Duration dataSentTimeDuration = Duration.ofNanos(System.nanoTime() - dataSentTime);
		recorder().recordDataSentTime(path, method, status, dataSentTimeDuration);

		if (dataReceivedTime != 0) {
			recorder().recordResponseTime(path, method, status, Duration.ofNanos(System.nanoTime() - dataReceivedTime));
		}
		else {
			recorder().recordResponseTime(path, method, status, dataSentTimeDuration);
		}

		// Always take the remote address from the operations in order to consider proxy information
		// Use remoteSocketAddress() in order to obtain UDS info
		recorder().recordDataSent(ops.remoteSocketAddress(), path, dataSent);
	}

	protected void recordActiveConnection(SocketAddress localAddress) {
		recorder().recordServerConnectionActive(localAddress);
	}

	protected void recordInactiveConnection(SocketAddress localAddress) {
		recorder().recordServerConnectionInactive(localAddress);
	}

	protected void recordOpenStream(SocketAddress localAddress) {
		recorder().recordStreamOpened(localAddress);
	}

	protected void recordClosedStream(SocketAddress localAddress) {
		recorder().recordStreamClosed(localAddress);
	}

	protected void startRead(HttpServerOperations ops, String path, String method) {
		dataReceivedTime = System.nanoTime();
	}

	protected void startWrite(HttpServerOperations ops, String path, String method, String status) {
		dataSentTime = System.nanoTime();
	}

	void recordInactiveConnectionOrStream(Channel channel) {
		if (channelActivated) {
			channelActivated = false;
			try {
				if (channel instanceof Http2StreamChannel) {
					// Always use the real connection local address without any proxy information
					recordClosedStream(channel.localAddress());
				}
				else {
					// Always use the real connection local address without any proxy information
					recordInactiveConnection(channel.localAddress());
				}
			}
			catch (RuntimeException e) {
				if (log.isWarnEnabled()) {
					log.warn(format(channel, "Exception caught while recording metrics."), e);
				}
				// Allow request-response exchange to continue, unaffected by metrics problem
			}
		}
	}
}
