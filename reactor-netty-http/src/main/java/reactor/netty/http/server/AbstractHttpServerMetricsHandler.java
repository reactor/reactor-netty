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
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

	static final boolean LAST_FLUSH_WHEN_NO_READ = Boolean.parseBoolean(
			System.getProperty("reactor.netty.http.server.lastFlushWhenNoRead", "false"));

	private static final Logger log = Loggers.getLogger(AbstractHttpServerMetricsHandler.class);

	boolean channelActivated;
	boolean channelOpened;

	ContextView contextView;

	long dataReceived;
	long dataReceivedTime;

	long dataSent;
	long dataSentTime;

	boolean initialized;

	boolean isHttp11;

	String method;
	String path;
	SocketAddress remoteSocketAddress;
	String status;

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
		this.contextView = copy.contextView;
		this.dataReceived = copy.dataReceived;
		this.dataReceivedTime = copy.dataReceivedTime;
		this.dataSent = copy.dataSent;
		this.dataSentTime = copy.dataSentTime;
		this.initialized = copy.initialized;
		this.isHttp11 = copy.isHttp11;
		this.method = copy.method;
		this.path = copy.path;
		this.remoteSocketAddress = copy.remoteSocketAddress;
		this.status = copy.status;
		this.methodTagValue = copy.methodTagValue;
		this.uriTagValue = copy.uriTagValue;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		// For custom user recorders, we don't propagate the channelActive event, because this will be done
		// by the ChannelMetricsHandler itself. ChannelMetricsHandler is only present when the recorder is
		// not our MicrometerHttpServerMetricsRecorder. See HttpServerConfig class.
		if (!(ctx.channel() instanceof Http2StreamChannel)) {
			isHttp11 = true;
			if (recorder() instanceof MicrometerHttpServerMetricsRecorder) {
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
					if (!initialized) {
						method = methodTagValue.apply(ops.method().name());
						path = uriTagValue == null ? ops.path : uriTagValue.apply(ops.path);
						// Always take the remote address from the operations in order to consider proxy information
						// Use remoteSocketAddress() in order to obtain UDS info
						remoteSocketAddress = ops.remoteSocketAddress();
						initialized = true;
					}
					if (contextView == null) {
						contextView(ops);
					}
					status = ops.status().codeAsText().toString();
					startWrite(ops);
				}
			}

			dataSent += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				MetricsArgProvider copy;
				if (isHttp11 && LAST_FLUSH_WHEN_NO_READ) {
					copy = createMetricsArgProvider();
					ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
					if (channelOps instanceof HttpServerOperations) {
						recordInactiveConnectionOrStream(ctx.channel(), (HttpServerOperations) channelOps);
					}
				}
				else {
					copy = null;
				}
				promise.addListener(future -> {
					try {
						if (copy == null) {
							recordWrite(ctx.channel());
						}
						else {
							recordWrite(ctx.channel(), copy);
						}
					}
					catch (RuntimeException e) {
						// Allow request-response exchange to continue, unaffected by metrics problem
						if (log.isWarnEnabled()) {
							log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
						}
					}

					if (copy == null) {
						ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
						if (channelOps instanceof HttpServerOperations) {
							recordInactiveConnectionOrStream(ctx.channel(), (HttpServerOperations) channelOps);
						}
					}
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
		HttpServerOperations ops = null;
		try {
			if (msg instanceof HttpRequest) {
				reset(ctx.channel());
				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations) {
					ops = (HttpServerOperations) channelOps;
					method = methodTagValue.apply(ops.method().name());
					path = uriTagValue == null ? ops.path : uriTagValue.apply(ops.path);
					// Always take the remote address from the operations in order to consider proxy information
					// Use remoteSocketAddress() in order to obtain UDS info
					remoteSocketAddress = ops.remoteSocketAddress();
					initialized = true;
					startRead(ops);

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
				recordRead();
			}
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		ctx.fireChannelRead(msg);

		if (ops != null) {
			// ContextView is available only when a subscription to the I/O Handler happens
			contextView(ops);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		try {
			recordException();
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		ctx.fireExceptionCaught(cause);
	}

	private static long extractProcessedDataFromBuffer(Object msg) {
		if (msg instanceof ByteBufHolder) {
			return ((ByteBufHolder) msg).content().readableBytes();
		}
		else if (msg instanceof ByteBuf) {
			return ((ByteBuf) msg).readableBytes();
		}
		return 0;
	}

	protected abstract HttpServerMetricsRecorder recorder();

	protected MetricsArgProvider createMetricsArgProvider() {
		return new MetricsArgProvider(contextView, dataReceivedTime, dataSent, dataSentTime, method, path, remoteSocketAddress, status);
	}

	protected void contextView(HttpServerOperations ops) {
		this.contextView = Context.empty();
	}

	protected void recordException() {
		// Always take the remote address from the operations in order to consider proxy information
		// Use remoteSocketAddress() in order to obtain UDS info
		recorder().incrementErrorsCount(remoteSocketAddress, path);
	}

	protected void recordRead() {
		recorder().recordDataReceivedTime(path, method, Duration.ofNanos(System.nanoTime() - dataReceivedTime));

		// Always take the remote address from the operations in order to consider proxy information
		// Use remoteSocketAddress() in order to obtain UDS info
		recorder().recordDataReceived(remoteSocketAddress, path, dataReceived);
	}

	protected void recordWrite(Channel channel) {
		recordWrite(dataReceivedTime, dataSent, dataSentTime, method, path, remoteSocketAddress, status);
	}

	protected void recordWrite(Channel channel, MetricsArgProvider metricsArgProvider) {
		recordWrite(metricsArgProvider.dataReceivedTime, metricsArgProvider.dataSent, metricsArgProvider.dataSentTime,
				metricsArgProvider.method, metricsArgProvider.path, metricsArgProvider.remoteSocketAddress, metricsArgProvider.status);
	}

	void recordWrite(
			long dataReceivedTime,
			long dataSent,
			long dataSentTime,
			String method,
			String path,
			SocketAddress remoteSocketAddress,
			String status) {
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
		recorder().recordDataSent(remoteSocketAddress, path, dataSent);
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

	protected void startRead(HttpServerOperations ops) {
		dataReceivedTime = System.nanoTime();
	}

	protected void startWrite(HttpServerOperations ops) {
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

	protected void reset(Channel channel) {
		// There is no need to reset 'channelActivated' and 'channelOpened'
		contextView = null;
		dataReceived = 0;
		dataReceivedTime = 0;
		dataSent = 0;
		dataSentTime = 0;
		initialized = false;
		method = null;
		path = null;
		remoteSocketAddress = null;
		status = null;
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

	static class MetricsArgProvider {
		final ContextView contextView;
		final long dataReceivedTime;
		final long dataSent;
		final long dataSentTime;
		final Map<Object, Object> map = new HashMap<>();
		final String method;
		final String path;
		final SocketAddress remoteSocketAddress;
		final String status;

		MetricsArgProvider(
				ContextView contextView,
				long dataReceivedTime,
				long dataSent,
				long dataSentTime,
				String method,
				String path,
				SocketAddress remoteSocketAddress,
				String status) {
			this.contextView = contextView;
			this.dataReceivedTime = dataReceivedTime;
			this.dataSent = dataSent;
			this.dataSentTime = dataSentTime;
			this.method = method;
			this.path = path;
			this.remoteSocketAddress = remoteSocketAddress;
			this.status = status;
		}

		<T> MetricsArgProvider put(Object key, T object) {
			this.map.put(key, object);
			return this;
		}

		@Nullable
		@SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
		<T> T get(Object key) {
			return (T) this.map.get(key);
		}
	}
}
