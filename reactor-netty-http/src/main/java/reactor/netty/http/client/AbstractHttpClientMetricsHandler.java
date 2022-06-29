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
package reactor.netty.http.client;

import io.netty.buffer.ByteBufHolder;
import io.netty5.buffer.api.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.util.concurrent.Future;
import reactor.netty.channel.ChannelOperations;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Function;

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
							}
							catch (RuntimeException e) {
								log.warn("Exception caught while recording metrics.", e);
								// Allow request-response exchange to continue, unaffected by metrics problem
							}
				});
			}
		}
		catch (RuntimeException e) {
			log.warn("Exception caught while recording metrics.", e);
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
				reset();
			}
		}
		catch (RuntimeException e) {
			log.warn("Exception caught while recording metrics.", e);
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
			log.warn("Exception caught while recording metrics.", e);
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
		if (msg instanceof ByteBufHolder byteBufHolder) {
			return byteBufHolder.content().readableBytes();
		}
		else if (msg instanceof Buffer buffer) {
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
