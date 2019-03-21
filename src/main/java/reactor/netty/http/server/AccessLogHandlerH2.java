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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;

/**
 * @author Violeta Georgieva
 */
final class AccessLogHandlerH2 extends ChannelDuplexHandler {
	static final String H2_PROTOCOL_NAME = "HTTP/2.0";

	AccessLog accessLog = new AccessLog();

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof Http2HeadersFrame){
			final Http2HeadersFrame requestHeaders = (Http2HeadersFrame) msg;
			final SocketChannel channel = (SocketChannel) ctx.channel()
			                                                 .parent();
			final Http2Headers headers = requestHeaders.headers();

			accessLog = new AccessLog()
			        .address(channel.remoteAddress().getHostString())
			        .port(channel.localAddress().getPort())
			        .method(headers.method())
			        .uri(headers.path())
			        .protocol(H2_PROTOCOL_NAME);
		}
		super.channelRead(ctx, msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		boolean lastContent = false;
		if (msg instanceof Http2HeadersFrame) {
			final Http2HeadersFrame responseHeaders = (Http2HeadersFrame) msg;
			final Http2Headers headers = responseHeaders.headers();
			lastContent = responseHeaders.isEndStream();

			accessLog.status(headers.status())
			         .chunked(true);
		}
		if (msg instanceof Http2DataFrame) {
			final Http2DataFrame data = (Http2DataFrame) msg;
			lastContent = data.isEndStream();

			accessLog.increaseContentLength(data.content().readableBytes());
		}
		if (lastContent) {
			ctx.write(msg, promise)
			   .addListener(future -> {
			       if (future.isSuccess()) {
			           accessLog.log();
			       }
			   });
			return;
		}
		ctx.write(msg, promise);
	}
}
