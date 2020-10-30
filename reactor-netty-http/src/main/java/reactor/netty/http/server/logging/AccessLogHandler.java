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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import reactor.util.annotation.Nullable;

/**
 * {@link ChannelHandler} for access log of HTTP/1.1.
 *
 * @author Violeta Georgieva
 * @author limaoning
 */
public final class AccessLogHandler extends ChannelDuplexHandler {

	final AccessLogFactory accessLogFactory;
	AccessLogArgProviderH1 accessLogArgProvider = new AccessLogArgProviderH1();

	public AccessLogHandler(@Nullable AccessLogFactory accessLogFactory) {
		this.accessLogFactory = accessLogFactory == null ? AccessLogFactory.DEFAULT : accessLogFactory;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpRequest) {
			final HttpRequest request = (HttpRequest) msg;
			final SocketChannel channel = (SocketChannel) ctx.channel();

			accessLogArgProvider = new AccessLogArgProviderH1()
					.channel(channel)
					.request(request);
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

			accessLogArgProvider.response(response);
		}
		if (msg instanceof LastHttpContent) {
			accessLogArgProvider.increaseContentLength(((LastHttpContent) msg).content().readableBytes());
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
