/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import java.net.SocketAddress;
import java.time.Duration;

/**
 * @author Violeta Georgieva
 */
final class HttpClientMetricsHandler extends ChannelDuplexHandler {

	HttpRequest request;

	HttpResponse response;


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
			request = (HttpRequest) msg;

			dataSentTime = System.currentTimeMillis();
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
						request.uri(),
						request.method().name(),
						Duration.ofMillis(System.currentTimeMillis() - dataSentTime));

				recorder.recordDataSent(address, request.uri(), dataSent);
			});
		}
		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpResponse) {
			response = (HttpResponse) msg;

			dataReceivedTime = System.currentTimeMillis();
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
					request.uri(),
					request.method().name(),
					response.status().codeAsText().toString(),
					Duration.ofMillis(System.currentTimeMillis() - dataReceivedTime));

			recorder.recordResponseTime(address,
					request.uri(),
					request.method().name(),
					response.status().codeAsText().toString(),
					Duration.ofMillis(System.currentTimeMillis() - dataSentTime));

			recorder.recordDataReceived(address, request.uri(), dataReceived);
			reset();
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		recorder.incrementErrorsCount(ctx.channel().remoteAddress(), request.uri());

		ctx.fireExceptionCaught(cause);
	}

	private void reset() {
		request = null;
		response = null;
		dataReceived = 0;
		dataSent = 0;
		dataReceivedTime = 0;
		dataSentTime = 0;
	}
}
