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
package reactor.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.netty.channel.ChannelOperations;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Function;

import static reactor.netty.ReactorNetty.format;

/**
 * {@link ChannelDuplexHandler} for handling {@link HttpClient} metrics.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
abstract class AbstractHttpClientMetricsHandler extends ChannelDuplexHandler {

	private static final Logger log = Loggers.getLogger(AbstractHttpClientMetricsHandler.class);

	final SocketAddress proxyAddress;
	final SocketAddress remoteAddress;

	String path;

	String method;

	String status;

	ContextView contextView;


	long dataReceived;

	long dataSent;


	long dataReceivedTime;

	long dataSentTime;

	final Function<String, String> uriTagValue;

	int lastReadSeq;

	int lastWriteSeq;

	protected AbstractHttpClientMetricsHandler(SocketAddress remoteAddress, @Nullable SocketAddress proxyAddress, @Nullable Function<String, String> uriTagValue) {
		this.proxyAddress = proxyAddress;
		this.remoteAddress = remoteAddress;
		this.uriTagValue = uriTagValue;
	}

	protected AbstractHttpClientMetricsHandler(AbstractHttpClientMetricsHandler copy) {
		this.contextView = copy.contextView;
		this.dataReceived = copy.dataReceived;
		this.dataReceivedTime = copy.dataReceivedTime;
		this.dataSent = copy.dataSent;
		this.dataSentTime = copy.dataSentTime;
		this.method = copy.method;
		this.path = copy.path;
		this.proxyAddress = copy.proxyAddress;
		this.remoteAddress = copy.remoteAddress;
		this.status = copy.status;
		this.uriTagValue = copy.uriTagValue;
		this.lastWriteSeq = copy.lastWriteSeq;
		this.lastReadSeq = copy.lastReadSeq;
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		try {
			if (msg instanceof HttpRequest) {
				extractDetailsFromHttpRequest(ctx, (HttpRequest) msg);
			}

			dataSent += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				int currentLastWriteSeq = lastWriteSeq;
				promise.addListener(future -> {
					try {
						// Record write, unless channelRead has already done it (because an early full response has been received)
						if (currentLastWriteSeq == lastWriteSeq) {
							lastWriteSeq = (lastWriteSeq + 1) & 0x7F_FF_FF_FF;
							recordWrite(remoteAddress);
						}
					}
					catch (RuntimeException e) {
						// Allow request-response exchange to continue, unaffected by metrics problem
						if (log.isWarnEnabled()) {
							log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
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

		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof HttpResponse) {
				status = ((HttpResponse) msg).status().codeAsText().toString();

				startRead((HttpResponse) msg);
			}

			dataReceived += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				// Detect if we have received an early response before the request has been fully flushed.
				// In this case, invoke #recordWrite now (because next we will reset all class fields).
				lastReadSeq = (lastReadSeq + 1) & 0x7F_FF_FF_FF;
				if ((lastReadSeq > lastWriteSeq) || (lastReadSeq == 0 && lastWriteSeq == Integer.MAX_VALUE)) {
					lastWriteSeq = (lastWriteSeq + 1) & 0x7F_FF_FF_FF;
					recordWrite(remoteAddress);
				}
				recordRead(ctx.channel(), remoteAddress);
				reset();
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
			recordException(ctx);
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		ctx.fireExceptionCaught(cause);
	}

	private void extractDetailsFromHttpRequest(ChannelHandlerContext ctx, HttpRequest request) {
		method = request.method().name();

		ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
		if (channelOps instanceof HttpClientOperations) {
			HttpClientOperations ops = (HttpClientOperations) channelOps;
			path = uriTagValue == null ? ops.path : uriTagValue.apply(ops.path);
			contextView = ops.currentContextView();
		}

		startWrite(request, ctx.channel(), remoteAddress);
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

	protected abstract HttpClientMetricsRecorder recorder();

	protected void recordException(ChannelHandlerContext ctx) {
		if (proxyAddress == null) {
			recorder().incrementErrorsCount(remoteAddress, path != null ? path : resolveUri(ctx));
		}
		else {
			recorder().incrementErrorsCount(remoteAddress, proxyAddress, path != null ? path : resolveUri(ctx));
		}
	}

	protected void recordRead(Channel channel, SocketAddress address) {
		if (proxyAddress == null) {
			recorder().recordDataReceivedTime(address, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder().recordResponseTime(address, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataReceived(address, path, dataReceived);
		}
		else {
			recorder().recordDataReceivedTime(address, proxyAddress, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder().recordResponseTime(address, proxyAddress, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataReceived(address, proxyAddress, path, dataReceived);
		}
	}

	protected void recordWrite(SocketAddress address) {
		if (proxyAddress == null) {
			recorder().recordDataSentTime(address, path, method,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataSent(address, path, dataSent);
		}
		else {
			recorder().recordDataSentTime(address, proxyAddress, path, method,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataSent(address, proxyAddress, path, dataSent);
		}
	}

	protected void reset() {
		path = null;
		method = null;
		status = null;
		contextView = null;
		dataReceived = 0;
		dataSent = 0;
		dataReceivedTime = 0;
		dataSentTime = 0;
		// don't reset lastWriteSeq and lastReadSeq, which must be incremented forever
	}

	protected void startRead(HttpResponse msg) {
		dataReceivedTime = System.nanoTime();
	}

	protected void startWrite(HttpRequest msg, Channel channel, SocketAddress address) {
		dataSentTime = System.nanoTime();
	}

	private String resolveUri(ChannelHandlerContext ctx) {
		ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
		if (channelOps instanceof HttpClientOperations) {
			String path = ((HttpClientOperations) channelOps).uri();
			return uriTagValue == null ? path : uriTagValue.apply(path);
		}
		else {
			return "unknown";
		}
	}
}
