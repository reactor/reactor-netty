/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelOperations;

import static reactor.netty.ReactorNetty.format;

/**
 * This handler is intended to work together with {@link Http2StreamFrameToHttpObjectCodec}
 * it converts the outgoing messages into objects expected by
 * {@link Http2StreamFrameToHttpObjectCodec}.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class Http2StreamBridgeClientHandler extends ChannelDuplexHandler {

	final ConnectionObserver observer;
	final ChannelOperations.OnSetup opsFactory;

	Http2StreamBridgeClientHandler(ConnectionObserver listener, ChannelOperations.OnSetup opsFactory) {
		this.observer = listener;
		this.opsFactory = opsFactory;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		Http2ConnectionProvider.registerClose(ctx.channel());
		ctx.read();
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		if (HttpClientOperations.log.isDebugEnabled()) {
			HttpClientOperations.log.debug(format(ctx.channel(), "New HTTP/2 stream"));
		}

		ChannelOperations<?, ?> ops = opsFactory.create(Connection.from(ctx.channel()), observer, null);
		if (ops != null) {
			ops.bind();
		}
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof ByteBuf) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(new DefaultHttpContent((ByteBuf) msg), promise);
		}
		else {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}
	}
}
