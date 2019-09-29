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
import reactor.netty.channel.ChannelOperations;

import java.time.Duration;

/**
 * @author Violeta Georgieva
 */
final class HttpServerMetricsHandler extends ChannelDuplexHandler {

	long dataReceived;

	long dataSent;


	long dataReceivedTime;

	long dataSentTime;

	String uri;

	String method;


	final HttpServerMetricsRecorder recorder;

	HttpServerMetricsHandler(HttpServerMetricsRecorder recorder) {
		this.recorder = recorder;
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof HttpResponse) {
			if (((HttpResponse) msg).status().equals(HttpResponseStatus.CONTINUE)) {
				//"FutureReturnValueIgnored" this is deliberate
				ctx.write(msg, promise);
				return;
			}

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
				ChannelOperations<?,?> channelOps = ChannelOperations.get(ctx.channel());
				if (channelOps instanceof HttpServerOperations) {
					HttpServerOperations ops = (HttpServerOperations) channelOps;
					recorder.recordDataSentTime(
							ops.uri(),
							ops.method().name(),
							ops.status().codeAsText().toString(),
							Duration.ofMillis(System.currentTimeMillis() - dataSentTime));

					if (dataReceivedTime != 0) {
						recorder.recordResponseTime(
								ops.uri(),
								ops.method().name(),
								ops.status().codeAsText().toString(),
								Duration.ofMillis(System.currentTimeMillis() - dataReceivedTime));
					}
					else {
						recorder.recordDataSentTime(
								ops.uri(),
								ops.method().name(),
								ops.status().codeAsText().toString(),
								Duration.ofMillis(System.currentTimeMillis() - dataSentTime));
					}

					recorder.recordDataSent(ops.remoteAddress(), ops.uri(), dataSent);

					dataSent = 0;
				}
			});
		}

		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpRequest) {
			dataReceivedTime = System.currentTimeMillis();
			HttpRequest request = (HttpRequest) msg;
			uri = request.uri();
			method = request.method()
			                .name();
		}

		if (msg instanceof ByteBufHolder) {
			dataReceived += ((ByteBufHolder) msg).content().readableBytes();
		}
		else if (msg instanceof ByteBuf) {
			dataReceived += ((ByteBuf) msg).readableBytes();
		}

		if (msg instanceof LastHttpContent) {
			recorder.recordDataReceivedTime(uri, method, Duration.ofMillis(System.currentTimeMillis() - dataReceivedTime));

			recorder.recordDataReceived(ctx.channel().remoteAddress(), uri, dataReceived);

			dataReceived = 0;
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		if (uri != null) {
			recorder.incrementErrorsCount(ctx.channel().remoteAddress(), uri);
		}

		ctx.fireExceptionCaught(cause);
	}
}
