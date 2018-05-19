/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.channel.ChannelOperations;

final class Http2BridgeServerHandler extends ChannelDuplexHandler {

	final ChannelOperations.OnSetup opsFactory;
	final ConnectionObserver listener;

	ChannelHandlerContext ctx;

	Http2BridgeServerHandler(ChannelOperations.OnSetup opsFactory, ConnectionObserver listener) {
		this.opsFactory = opsFactory;
		this.listener = listener;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		super.handlerAdded(ctx);
		this.ctx = ctx;
		if (HttpServerOperations.log.isDebugEnabled()) {
			HttpServerOperations.log.debug("New http connection, requesting read");
		}
		ctx.read();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http2HeadersFrame) {
			ChannelOperations<?, ?> ops = opsFactory.create(Connection.from(ctx.channel()), listener, msg);
			if (ops != null) {
				ops.bind();
				ctx.fireChannelRead(msg);
			}
			return;
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof Http2Headers) {
			msg = new DefaultHttp2HeadersFrame((Http2Headers) msg);
		}

		boolean endOfHttp2Stream = false;
		if (msg instanceof Http2DataFrame) {
			endOfHttp2Stream = ((Http2DataFrame) msg).isEndStream();
		}


		if (!endOfHttp2Stream && msg instanceof ByteBuf &&
				ctx.channel() instanceof Http2StreamChannel) {
			msg = new DefaultHttp2DataFrame((ByteBuf) msg);
		}

		ctx.write(msg, promise);
	}

}