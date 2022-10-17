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
package reactor.netty5.http.client;

import io.netty5.buffer.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.util.concurrent.Future;
import reactor.netty5.channel.ChannelOperations;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Function;

import static reactor.netty5.ReactorNetty.format;

/**
 * @author Violeta Georgieva
 * @since 1.0.8
 */
abstract class AbstractHttpClientMetricsHandler extends ChannelHandlerAdapter {

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

	int flags;

	final static int REQUEST_SENT = 0x01;

	final static int RESPONSE_RECEIVED = 0x02;

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
		this.flags = copy.flags;
	}

	@Override
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof HttpRequest request) {
				extractDetailsFromHttpRequest(ctx, request);
			}

			dataSent += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				SocketAddress address = ctx.channel().remoteAddress();
				return ctx.write(msg)
						.addListener(future -> {
							try {
								recordWrite(address);
								// Indicate that we have fully flushed the request. The channelRead method will reset
								// only if the REQUEST_SENT flag is set
								flags |= REQUEST_SENT;

								// Under heavy load, it may happen that the netty Selector is processing OP_WRITES, then OP_READ operations.
								// So, since netty5 write listeners are executed asynchronously, it may happen that the channelRead method
								// has already been called before our write listener in case we have already received the response.
								// So, we need to reset class fields in case we detect that the channelRead has been called before our write listener.
								if ((flags & RESPONSE_RECEIVED) == RESPONSE_RECEIVED) {
									reset();
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

		return ctx.write(msg);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof HttpResponse response) {
				status = response.status().codeAsText().toString();

				startRead(response);
			}

			dataReceived += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				recordRead(ctx.channel().remoteAddress());
				// Set flag used to indicate that we have received the full response.
				flags |= RESPONSE_RECEIVED;

				// Under heavy load, it may happen that the netty Selector is processing OP_WRITES, then OP_READ operations.
				// So, since netty5 write listeners are executed asynchronously, it may happen that channelRead is called before
				// the write listener is called. So, only reset class fields if the write listener has been called.
				if ((flags & REQUEST_SENT) == REQUEST_SENT) {
					reset();
				}
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
			recordException(ctx);
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
			// Allow request-response exchange to continue, unaffected by metrics problem
		}

		ctx.fireChannelExceptionCaught(cause);
	}

	private void extractDetailsFromHttpRequest(ChannelHandlerContext ctx, HttpRequest request) {
		method = request.method().name();

		ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
		if (channelOps instanceof HttpClientOperations ops) {
			path = uriTagValue == null ? ops.path : uriTagValue.apply(ops.path);
			contextView = ops.currentContextView();
		}

		startWrite(request, ctx.channel(), contextView);
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

	protected void reset() {
		path = null;
		method = null;
		status = null;
		contextView = null;
		dataReceived = 0;
		dataSent = 0;
		dataReceivedTime = 0;
		dataSentTime = 0;
		flags = 0x0;
	}

	protected void startRead(HttpResponse msg) {
		dataReceivedTime = System.nanoTime();
	}

	protected void startWrite(HttpRequest msg, Channel channel, @Nullable ContextView contextView) {
		dataSentTime = System.nanoTime();
	}

	private String resolveUri(ChannelHandlerContext ctx) {
		ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
		if (channelOps instanceof HttpClientOperations ops) {
			String path = ops.uri();
			return uriTagValue == null ? path : uriTagValue.apply(path);
		}
		else {
			return "unknown";
		}
	}
}
