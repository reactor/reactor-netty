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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.util.annotation.Nullable;

import java.util.function.Function;

/**
 * {@link ChannelHandler} for access log of HTTP/1.1.
 *
 * @author Violeta Georgieva
 * @author limaoning
 */
final class AccessLogHandlerH1 extends BaseAccessLogHandler {

	AccessLogArgProviderH1 accessLogArgProvider;

	AccessLogHandlerH1(@Nullable Function<AccessLogArgProvider, AccessLog> accessLog) {
		super(accessLog);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpRequest) {
			final HttpRequest request = (HttpRequest) msg;

			if (accessLogArgProvider == null) {
				accessLogArgProvider = new AccessLogArgProviderH1(ctx.channel().remoteAddress());
			}
			accessLogArgProvider.request(request);
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof HttpResponse) {
			final HttpResponse response = (HttpResponse) msg;
			final HttpResponseStatus status = response.status();

			if (status.equals(HttpResponseStatus.CONTINUE)) {
				//"FutureReturnValueIgnored" this is deliberate
				ctx.write(msg, promise);
				return;
			}

			final boolean chunked = HttpUtil.isTransferEncodingChunked(response);
			accessLogArgProvider.status(status.codeAsText())
					.chunked(chunked);
			if (!chunked) {
				accessLogArgProvider.contentLength(HttpUtil.getContentLength(response, -1));
			}
		}
		if (msg instanceof LastHttpContent) {
			accessLogArgProvider.increaseContentLength(((LastHttpContent) msg).content().readableBytes());
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
		if (msg instanceof ByteBuf) {
			accessLogArgProvider.increaseContentLength(((ByteBuf) msg).readableBytes());
		}
		if (msg instanceof ByteBufHolder) {
			accessLogArgProvider.increaseContentLength(((ByteBufHolder) msg).content().readableBytes());
		}
		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}
}
