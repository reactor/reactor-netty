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
package reactor.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Function;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Violeta Georgieva
 * @since 1.0.8
 */
abstract class AbstractHttpClientMetricsHandler extends ChannelDuplexHandler {

	private static final Logger log = Loggers.getLogger(AbstractHttpClientMetricsHandler.class);

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

	protected AbstractHttpClientMetricsHandler(@Nullable Function<String, String> uriTagValue) {
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
				SocketAddress address = ctx.channel().remoteAddress();
				promise.addListener(future -> {
					try {
						// Record write, unless channelRead has already done it (because an early full response has been received)
						if (currentLastWriteSeq == lastWriteSeq) {
							lastWriteSeq = (lastWriteSeq + 1) & 0x7F_FF_FF_FF;
							recordWrite(address);
						}
					}
					catch (RuntimeException e) {
						if (log.isWarnEnabled()) {
							log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
						}
						// Allow request-response exchange to continue, unaffected by metrics problem
					}
				});
			}
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
			// Allow request-response exchange to continue, unaffected by metrics problem
		}
		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof HttpResponse) {
				status = ((HttpResponse) msg).status().codeAsText().toString();

				dataReceivedTime = System.nanoTime();
			}

			dataReceived += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				// Detect if we have received an early response before the request has been fully flushed.
				// In this case, invoke recordwrite now (because next we will reset all class fields).
				lastReadSeq = (lastReadSeq + 1) & 0x7F_FF_FF_FF;
				if ((lastReadSeq > lastWriteSeq) || (lastReadSeq == 0 && lastWriteSeq == Integer.MAX_VALUE)) {
					lastWriteSeq = (lastWriteSeq + 1) & 0x7F_FF_FF_FF;
					recordWrite(ctx.channel().remoteAddress());
				}
				recordRead(ctx.channel().remoteAddress());
				reset();
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
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		try {
			recordException(ctx);
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
			// Allow request-response exchange to continue, unaffected by metrics problem
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

		dataSentTime = System.nanoTime();
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
		recorder().incrementErrorsCount(ctx.channel().remoteAddress(),
				path != null ? path : resolveUri(ctx));
	}

	protected void recordRead(SocketAddress address) {
		recorder().recordDataReceivedTime(address,
				path, method, status,
				Duration.ofNanos(System.nanoTime() - dataReceivedTime));

		recorder().recordResponseTime(address,
				path, method, status,
				Duration.ofNanos(System.nanoTime() - dataSentTime));

		recorder().recordDataReceived(address, path, dataReceived);
	}

	protected void recordWrite(SocketAddress address) {
		recorder().recordDataSentTime(address,
				path, method,
				Duration.ofNanos(System.nanoTime() - dataSentTime));

		recorder().recordDataSent(address, path, dataSent);
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

	private void reset() {
		path = null;
		method = null;
		status = null;
		contextView = null;
		dataReceived = 0;
		dataSent = 0;
		dataReceivedTime = 0;
		dataSentTime = 0;
		// don't reset lastWriteSeq and lastReadSeq, which must be incremented for ever
	}
}
