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
package reactor.netty.http.server.logging;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import reactor.util.annotation.Nullable;

import java.util.function.Function;

/**
 * {@link ChannelHandler} for access log of HTTP/2.0.
 *
 * @author Violeta Georgieva
 * @author limaoning
 */
final class AccessLogHandlerH2 extends BaseAccessLogHandler {

	AccessLogArgProviderH2 accessLogArgProvider;

	AccessLogHandlerH2(@Nullable Function<AccessLogArgProvider, AccessLog> accessLog) {
		super(accessLog);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http2HeadersFrame) {
			final Http2HeadersFrame requestHeaders = (Http2HeadersFrame) msg;

			if (accessLogArgProvider == null) {
				accessLogArgProvider = new AccessLogArgProviderH2(ctx.channel().remoteAddress());
			}
			accessLogArgProvider.requestHeaders(requestHeaders);
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		boolean lastContent = false;
		if (msg instanceof Http2HeadersFrame) {
			final Http2HeadersFrame responseHeaders = (Http2HeadersFrame) msg;
			final Http2Headers headers = responseHeaders.headers();
			lastContent = responseHeaders.isEndStream();

			accessLogArgProvider.status(headers.status())
					.chunked(true);
		}
		if (msg instanceof Http2DataFrame) {
			final Http2DataFrame data = (Http2DataFrame) msg;
			lastContent = data.isEndStream();

			accessLogArgProvider.increaseContentLength(data.content().readableBytes());
		}
		if (lastContent) {
			ctx.write(msg, promise.unvoid())
			   .addListener(future -> {
			       if (future.isSuccess()) {
				       AccessLog log = accessLog.apply(accessLogArgProvider);
				       if (log != null) {
					       log.log();
				       }
				       accessLogArgProvider.clear();
			       }
			   });
			return;
		}
		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}
}
