/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2StreamChannel;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import static reactor.netty.ReactorNetty.format;

/**
 * {@link ChannelDuplexHandler} for handling {@link HttpServer} metrics.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
abstract class AbstractHttpServerMetricsHandler extends ChannelDuplexHandler {

	private static final Logger log = Loggers.getLogger(AbstractHttpServerMetricsHandler.class);

	boolean channelActivated;
	boolean channelOpened;

	long dataReceived;

	long dataSent;

	long dataReceivedTime;

	long dataSentTime;

	final Function<String, String> methodTagValue;
	final Function<String, String> uriTagValue;

	protected AbstractHttpServerMetricsHandler(
			@Nullable Function<String, String> methodTagValue,
			@Nullable Function<String, String> uriTagValue) {
		this.methodTagValue = methodTagValue == null ? DEFAULT_METHOD_TAG_VALUE : methodTagValue;
		this.uriTagValue = uriTagValue;
	}

	protected AbstractHttpServerMetricsHandler(AbstractHttpServerMetricsHandler copy) {
		this.channelActivated = copy.channelActivated;
		this.channelOpened = copy.channelOpened;
		this.dataReceived = copy.dataReceived;
		this.dataReceivedTime = copy.dataReceivedTime;
		this.dataSent = copy.dataSent;
		this.dataSentTime = copy.dataSentTime;
		this.methodTagValue = copy.methodTagValue;
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
				channelOpened = true;
				recorder().recordServerConnectionOpened(ctx.channel().localAddress());
			}
			catch (RuntimeException e) {
				// Allow request-response exchange to continue, unaffected by metrics problem
				if (log.isWarnEnabled()) {
					log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				}
			}
		}
		ctx.fireChannelActive();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		if (!(ctx.channel() instanceof Http2StreamChannel) && recorder() instanceof MicrometerHttpServerMetricsRecorder) {
			try {
				// Always use the real connection local address without any proxy information
				if (channelOpened) {
					channelOpened = false;
					recorder().recordServerConnectionClosed(ctx.channel().localAddress());
				}
			}
			catch (RuntimeException e) {
				// Allow request-response exchange to continue, unaffected by metrics problem
				if (log.isWarnEnabled()) {
					log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				}
			}
		}

		ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
		HttpServerOperations ops = null;
		if (channelOps instanceof HttpServerOperations) {
			ops = (HttpServerOperations) channelOps;
		}
		recordInactiveConnectionOrStream(ctx.channel(), ops);

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
							methodTagValue.apply(ops.method().name()), ops.status().codeAsText().toString());
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
									methodTagValue.apply(ops.method().name()), ops.status().codeAsText().toString());
						}
						catch (RuntimeException e) {
							// Allow request-response exchange to continue, unaffected by metrics problem
							if (log.isWarnEnabled()) {
								log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
							}
						}

						recordInactiveConnectionOrStream(ctx.channel(), ops);
					}

					dataSent = 0;
				});
			}
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
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
					startRead(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path), methodTagValue.apply(ops.method().name()));

					channelActivated = true;
					if (ctx.channel() instanceof Http2StreamChannel) {
						// Always use the real connection local address without any proxy information
						recordOpenStream(ops.connectionHostAddress());
					}
					else if (ctx.channel() instanceof SocketChannel) {
						// Always use the real connection local address without any proxy information
						recordActiveConnection(ops.connectionHostAddress());
					}
					else {
						// Always use the real connection local address without any proxy information
						recordOpenStream(ops.connectionHostAddress());
					}
				}
			}

			dataReceived += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations) {
					HttpServerOperations ops = (HttpServerOperations) channelOps;
					recordRead(ops, uriTagValue == null ? ops.path : uriTagValue.apply(ops.path), methodTagValue.apply(ops.method().name()));
				}

				dataReceived = 0;
			}
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
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
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
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

	void recordInactiveConnectionOrStream(Channel channel, @Nullable HttpServerOperations ops) {
		if (channelActivated) {
			channelActivated = false;
			try {
				SocketAddress localAddress = ops != null ? ops.connectionHostAddress() : channel.localAddress();
				if (channel instanceof Http2StreamChannel) {
					// Always use the real connection local address without any proxy information
					recordClosedStream(localAddress);
				}
				else if (channel instanceof SocketChannel) {
					// Always use the real connection local address without any proxy information
					recordInactiveConnection(localAddress);
				}
				else {
					// Always use the real connection local address without any proxy information
					recordClosedStream(localAddress);
				}
			}
			catch (RuntimeException e) {
				// Allow request-response exchange to continue, unaffected by metrics problem
				if (log.isWarnEnabled()) {
					log.warn(format(channel, "Exception caught while recording metrics."), e);
				}
			}
		}
	}

	static final Set<String> STANDARD_METHODS;
	static {
		Set<String> standardMethods = new HashSet<>();
		standardMethods.add("GET");
		standardMethods.add("HEAD");
		standardMethods.add("POST");
		standardMethods.add("PUT");
		standardMethods.add("PATCH");
		standardMethods.add("DELETE");
		standardMethods.add("OPTIONS");
		standardMethods.add("TRACE");
		standardMethods.add("CONNECT");
		STANDARD_METHODS = Collections.unmodifiableSet(standardMethods);
	}
	static final String UNKNOWN_METHOD = "UNKNOWN";
	static final Function<String, String> DEFAULT_METHOD_TAG_VALUE = m -> STANDARD_METHODS.contains(m) ? m : UNKNOWN_METHOD;
}
