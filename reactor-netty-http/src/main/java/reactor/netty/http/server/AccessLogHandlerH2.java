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
package reactor.netty.http.server;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import reactor.util.annotation.Nullable;

/**
 * @author Violeta Georgieva
 */
final class AccessLogHandlerH2 extends ChannelDuplexHandler {

	final AccessLogFactory accessLogFactory;
	AccessLogArgProviderH2 accessLogArgProvider = new AccessLogArgProviderH2();

	AccessLogHandlerH2(@Nullable AccessLogFactory accessLogFactory) {
		this.accessLogFactory = accessLogFactory == null ? AccessLogFactory.DEFAULT : accessLogFactory;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http2HeadersFrame){
			final Http2HeadersFrame requestHeaders = (Http2HeadersFrame) msg;
			final SocketChannel channel = (SocketChannel) ctx.channel()
			                                                 .parent();

			accessLogArgProvider = new AccessLogArgProviderH2()
					.channel(channel)
					.requestHeaders(requestHeaders);
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		boolean lastContent = false;
		if (msg instanceof Http2HeadersFrame) {
			final Http2HeadersFrame responseHeaders = (Http2HeadersFrame) msg;
			lastContent = responseHeaders.isEndStream();

			accessLogArgProvider.responseHeaders(responseHeaders);
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
				       accessLogFactory
						       .create(accessLogArgProvider)
						       .log();
			       }
			   });
			return;
		}
		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}
}
