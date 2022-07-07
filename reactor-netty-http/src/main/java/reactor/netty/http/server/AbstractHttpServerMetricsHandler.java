/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2StreamChannel;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.function.Function;

/**
 * @author Violeta Georgieva
 * @since 1.0.8
 */
abstract class AbstractHttpServerMetricsHandler extends ChannelDuplexHandler {

	private static final Logger log = Loggers.getLogger(AbstractHttpServerMetricsHandler.class);

	long dataReceived;

	long dataSent;

	long dataReceivedTime;

	long dataSentTime;

	final Function<String, String> uriTagValue;

	protected AbstractHttpServerMetricsHandler(@Nullable Function<String, String> uriTagValue) {
		this.uriTagValue = uriTagValue;
	}

	protected AbstractHttpServerMetricsHandler(AbstractHttpServerMetricsHandler copy) {
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
				recorder().recordServerConnectionOpened(ctx.channel().localAddress());
			}
			catch (RuntimeException e) {
				log.warn("Exception caught while recording metrics.", e);
				// Allow request-response exchange to continue, unaffected by metrics problem
			}
		}
		ctx.fireChannelActive();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		if (!(ctx.channel() instanceof Http2StreamChannel) && recorder() instanceof MicrometerHttpServerMetricsRecorder) {
			try {
				recorder().recordServerConnectionClosed(ctx.channel().localAddress());
			}
			catch (RuntimeException e) {
				log.warn("Exception caught while recording metrics.", e);
				// Allow request-response exchange to continue, unaffected by metrics problem
			}
		}
		ctx.fireChannelInactive();
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		try {
			if (msg instanceof HttpResponse) {
				if (((HttpResponse) msg).status().equals(HttpResponseStatus.CONTINUE)) {
					//"FutureReturnValueIgnored" this is deliberate
					ctx.write(msg, promise);
					return;
				}

				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations) {
					HttpServerOperations ops = (HttpServerOperations) channelOps;
					startWrite(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path),
							ops.method().name(), ops.status().codeAsText().toString());
				}
			}

			dataSent += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				promise.addListener(future -> {
					ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
					if (channelOps instanceof HttpServerOperations) {
						HttpServerOperations ops = (HttpServerOperations) channelOps;
						try {
							recordWrite(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path),
									ops.method().name(), ops.status().codeAsText().toString());
						}
						catch (RuntimeException e) {
							log.warn("Exception caught while recording metrics.", e);
							// Allow request-response exchange to continue, unaffected by metrics problem
						}
						// ops.hostAddress() == null when request decoding failed, in this case
						// we do not report active connection, so we do not report inactive connection
						if (ops.hostAddress() != null) {
							try {
								if (ops.isHttp2()) {
									recordClosedStream(ops);
								}
								else {
									recordInactiveConnection(ops);
								}
							}
							catch (RuntimeException e) {
								log.warn("Exception caught while recording metrics.", e);
								// Allow request-response exchange to continue, unaffected by metrics problem
							}
						}
					}

					dataSent = 0;
				});
			}
		}
		catch (RuntimeException e) {
			log.warn("Exception caught while recording metrics.", e);
			// Allow request-response exchange to continue, unaffected by metrics problem
		}
		finally {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof HttpRequest) {
				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations) {
					HttpServerOperations ops = (HttpServerOperations) channelOps;
					if (ops.isHttp2()) {
						recordOpenStream(ops);
					}
					else {
						recordActiveConnection(ops);
					}
					startRead(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path), ops.method().name());
				}
			}

			dataReceived += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations) {
					HttpServerOperations ops = (HttpServerOperations) channelOps;
					recordRead(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path), ops.method().name());
				}

				dataReceived = 0;
			}
		}
		catch (RuntimeException e) {
			log.warn("Exception caught while recording metrics.", e);
			// Allow request-response exchange to continue, unaffected by metrics problem
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		try {
			ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
			if (channelOps instanceof HttpServerOperations) {
				HttpServerOperations ops = (HttpServerOperations) channelOps;
				// Always take the remote address from the operations in order to consider proxy information
				recordException(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path));
			}
		}
		catch (RuntimeException e) {
			log.warn("Exception caught while recording metrics.", e);
			// Allow request-response exchange to continue, unaffected by metrics problem
		}

		ctx.fireExceptionCaught(cause);
	}

	private long extractProcessedDataFromBuffer(Object msg) {
		if (msg instanceof ByteBufHolder) {
			return ((ByteBufHolder) msg).content().readableBytes();
		}
		else if (msg instanceof ByteBuf) {
			return ((ByteBuf) msg).readableBytes();
		}
		return 0;
	}

	protected abstract HttpServerMetricsRecorder recorder();

	protected void recordException(HttpServerOperations ops, String path) {
		// Always take the remote address from the operations in order to consider proxy information
		recorder().incrementErrorsCount(ops.remoteAddress(), path);
	}

	protected void recordRead(HttpServerOperations ops, String path, String method) {
		recorder().recordDataReceivedTime(path, method, Duration.ofNanos(System.nanoTime() - dataReceivedTime));

		// Always take the remote address from the operations in order to consider proxy information
		recorder().recordDataReceived(ops.remoteAddress(), path, dataReceived);
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
		recorder().recordDataSent(ops.remoteAddress(), path, dataSent);
	}

	protected void recordActiveConnection(HttpServerOperations ops) {
		recorder().recordServerConnectionActive(ops.hostAddress());
	}

	protected void recordInactiveConnection(HttpServerOperations ops) {
		recorder().recordServerConnectionInactive(ops.hostAddress());
	}

	protected void recordOpenStream(HttpServerOperations ops) {
		recorder().recordStreamOpened(ops.hostAddress());
	}

	protected void recordClosedStream(HttpServerOperations ops) {
		recorder().recordStreamClosed(ops.hostAddress());
	}

	protected void startRead(HttpServerOperations ops, String path, String method) {
		dataReceivedTime = System.nanoTime();
	}

	protected void startWrite(HttpServerOperations ops, String path, String method, String status) {
		dataSentTime = System.nanoTime();
	}
}
