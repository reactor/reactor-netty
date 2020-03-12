/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
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

import java.net.SocketAddress;
import java.time.Duration;

/**
 * @author Violeta Georgieva
 */
final class HttpClientMetricsHandler extends ChannelDuplexHandler {

	String path;

	String method;

	String status;


	long dataReceived;

	long dataSent;


	long dataReceivedTime;

	long dataSentTime;


	final HttpClientMetricsRecorder recorder;

	HttpClientMetricsHandler(HttpClientMetricsRecorder recorder) {
		this.recorder = recorder;
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof HttpRequest) {
			ChannelOperations<?,?> channelOps = ChannelOperations.get(ctx.channel());
			if (channelOps instanceof HttpClientOperations) {
				HttpClientOperations ops = (HttpClientOperations) channelOps;
				path = ops.path;
				method = ops.method().name();
			}

			dataSentTime = System.nanoTime();
		}

		if (msg instanceof ByteBufHolder) {
			dataSent += ((ByteBufHolder) msg).content().readableBytes();
		}
		else if (msg instanceof ByteBuf) {
			dataSent += ((ByteBuf) msg).readableBytes();
		}

		if (msg instanceof LastHttpContent) {
			promise.addListener(future -> {
				SocketAddress address = ctx.channel().remoteAddress();

				recorder.recordDataSentTime(address,
						path, method,
						Duration.ofNanos(System.nanoTime() - dataSentTime));

				recorder.recordDataSent(address, path, dataSent);
			});
		}
		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpResponse) {
			HttpResponse response = (HttpResponse) msg;
			status = response.status().codeAsText().toString();

			dataReceivedTime = System.nanoTime();
		}

		if (msg instanceof ByteBufHolder) {
			dataReceived += ((ByteBufHolder) msg).content().readableBytes();
		}
		else if (msg instanceof ByteBuf) {
			dataReceived += ((ByteBuf) msg).readableBytes();
		}

		if (msg instanceof LastHttpContent) {
			SocketAddress address = ctx.channel().remoteAddress();
			recorder.recordDataReceivedTime(address,
					path, method, status,
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder.recordResponseTime(address,
					path, method, status,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder.recordDataReceived(address, path, dataReceived);
			reset();
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		recorder.incrementErrorsCount(ctx.channel().remoteAddress(),
				path != null ? path : resolveUri(ctx));

		ctx.fireExceptionCaught(cause);
	}

	private String resolveUri(ChannelHandlerContext ctx) {
		ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
		if (channelOps instanceof HttpClientOperations) {
			return ((HttpClientOperations) channelOps).uri();
		}
		else {
			return "unknown";
		}
	}

	private void reset() {
		path = null;
		method = null;
		status = null;
		dataReceived = 0;
		dataSent = 0;
		dataReceivedTime = 0;
		dataSentTime = 0;
	}
}
