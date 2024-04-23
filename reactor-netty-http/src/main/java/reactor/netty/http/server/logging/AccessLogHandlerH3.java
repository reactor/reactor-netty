/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server.logging;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.quic.QuicChannel;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.server.HttpServerInfos;
import reactor.util.annotation.Nullable;

import java.util.function.Function;

final class AccessLogHandlerH3 extends BaseAccessLogHandler {

	AccessLogArgProviderH3 accessLogArgProvider;

	AccessLogHandlerH3(@Nullable Function<AccessLogArgProvider, AccessLog> accessLog) {
		super(accessLog);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http3HeadersFrame) {
			Http3HeadersFrame requestHeaders = (Http3HeadersFrame) msg;

			if (accessLogArgProvider == null) {
				accessLogArgProvider = new AccessLogArgProviderH3(ctx.channel().parent() instanceof QuicChannel ?
						((QuicChannel) ctx.channel().parent()).remoteSocketAddress() : null);
			}
			accessLogArgProvider.requestHeaders(requestHeaders);

			ctx.channel()
			   .closeFuture()
			   .addListener(f -> {
			       AccessLog log = accessLog.apply(accessLogArgProvider);
			       if (log != null) {
			           log.log();
			       }
			       accessLogArgProvider.clear();
			   });
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof Http3HeadersFrame) {
			Http3HeadersFrame responseHeaders = (Http3HeadersFrame) msg;

			if (HttpResponseStatus.CONTINUE.codeAsText().contentEquals(responseHeaders.headers().status())) {
				//"FutureReturnValueIgnored" this is deliberate
				ctx.write(msg, promise);
				return;
			}

			accessLogArgProvider.responseHeaders(responseHeaders)
					.chunked(true);

			ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
			if (ops instanceof HttpServerInfos) {
				super.applyServerInfos(accessLogArgProvider, (HttpServerInfos) ops);
			}
		}
		if (msg instanceof Http3DataFrame) {
			Http3DataFrame data = (Http3DataFrame) msg;

			accessLogArgProvider.increaseContentLength(data.content().readableBytes());
		}

		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}
}
