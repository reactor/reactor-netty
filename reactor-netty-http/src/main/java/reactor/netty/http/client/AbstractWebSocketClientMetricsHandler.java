/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jspecify.annotations.Nullable;
import reactor.netty.channel.ChannelOperations;
import reactor.util.context.ContextView;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Function;

import static reactor.netty.ReactorNetty.format;

/**
 * {@link ChannelDuplexHandler} for handling WebSocket {@link HttpClient} metrics.
 *
 * @author LivingLikeKrillin
 * @since 1.3.5
 */
abstract class AbstractWebSocketClientMetricsHandler extends ChannelDuplexHandler {

	private static final Logger log = Loggers.getLogger(AbstractWebSocketClientMetricsHandler.class);

	final @Nullable SocketAddress proxyAddress;
	final SocketAddress remoteAddress;

	@Nullable String path;

	@Nullable ContextView contextView;

	long dataReceived;

	long dataSent;

	long dataReceivedTime;

	long dataSentTime;

	long connectionStartTime;

	final @Nullable Function<String, String> uriTagValue;

	protected AbstractWebSocketClientMetricsHandler(SocketAddress remoteAddress, @Nullable SocketAddress proxyAddress,
			@Nullable Function<String, String> uriTagValue) {
		this.proxyAddress = proxyAddress;
		this.remoteAddress = remoteAddress;
		this.uriTagValue = uriTagValue;
	}

	protected AbstractWebSocketClientMetricsHandler(AbstractWebSocketClientMetricsHandler copy) {
		this.connectionStartTime = copy.connectionStartTime;
		this.contextView = copy.contextView;
		this.dataReceived = copy.dataReceived;
		this.dataReceivedTime = copy.dataReceivedTime;
		this.dataSent = copy.dataSent;
		this.dataSentTime = copy.dataSentTime;
		this.path = copy.path;
		this.proxyAddress = copy.proxyAddress;
		this.remoteAddress = copy.remoteAddress;
		this.uriTagValue = copy.uriTagValue;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		super.handlerAdded(ctx);
		connectionStartTime = System.nanoTime();
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		try {
			if (connectionStartTime > 0) {
				recordConnectionClosed(ctx);
			}
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}
		super.handlerRemoved(ctx);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		try {
			if (msg instanceof WebSocketFrame && isDataFrame(msg)) {
				dataSentTime = System.nanoTime();
				dataSent += extractProcessedDataFromBuffer(msg);

				recordWrite(remoteAddress);
			}
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof WebSocketFrame && isDataFrame(msg)) {
				dataReceivedTime = System.nanoTime();
				long bytes = extractProcessedDataFromBuffer(msg);
				dataReceived += bytes;

				recordRead(ctx.channel(), remoteAddress);
			}
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		try {
			recordException(ctx);
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		ctx.fireExceptionCaught(cause);
	}

	void initMetrics(ChannelHandlerContext ctx) {
		ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
		if (channelOps instanceof HttpClientOperations) {
			HttpClientOperations ops = (HttpClientOperations) channelOps;
			path = uriTagValue == null ? resolvePath(ops) : uriTagValue.apply(resolvePath(ops));
			contextView = ops.currentContextView();
		}
	}

	static boolean isDataFrame(Object msg) {
		return !(msg instanceof CloseWebSocketFrame) &&
				!(msg instanceof PingWebSocketFrame) &&
				!(msg instanceof PongWebSocketFrame);
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

	protected abstract WebSocketClientMetricsRecorder recorder();

	protected void recordConnectionClosed(ChannelHandlerContext ctx) {
		if (path == null) {
			initMetrics(ctx);
		}
		Duration duration = Duration.ofNanos(System.nanoTime() - connectionStartTime);
		if (proxyAddress == null) {
			recorder().recordWebSocketConnectionDuration(remoteAddress, path != null ? path : "unknown", duration);
		}
		else {
			recorder().recordWebSocketConnectionDuration(remoteAddress, proxyAddress, path != null ? path : "unknown", duration);
		}
	}

	protected void recordException(ChannelHandlerContext ctx) {
		if (path == null) {
			initMetrics(ctx);
		}
		if (proxyAddress == null) {
			recorder().incrementErrorsCount(remoteAddress, path != null ? path : "unknown");
		}
		else {
			recorder().incrementErrorsCount(remoteAddress, proxyAddress, path != null ? path : "unknown");
		}
	}

	protected void recordRead(io.netty.channel.Channel channel, SocketAddress address) {
		if (path == null) {
			return;
		}
		if (proxyAddress == null) {
			recorder().recordDataReceivedTime(address, path, "GET", "n/a",
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder().recordDataReceived(address, path, dataReceived);
		}
		else {
			recorder().recordDataReceivedTime(address, proxyAddress, path, "GET", "n/a",
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder().recordDataReceived(address, proxyAddress, path, dataReceived);
		}
		dataReceived = 0;
	}

	protected void recordWrite(SocketAddress address) {
		if (path == null) {
			return;
		}
		if (proxyAddress == null) {
			recorder().recordDataSentTime(address, path, "GET",
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataSent(address, path, dataSent);
		}
		else {
			recorder().recordDataSentTime(address, proxyAddress, path, "GET",
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataSent(address, proxyAddress, path, dataSent);
		}
		dataSent = 0;
	}

	static String resolvePath(HttpClientOperations ops) {
		try {
			return ops.fullPath();
		}
		catch (Exception e) {
			return "/bad-request";
		}
	}
}
