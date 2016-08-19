/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

/**
 * Conversion between Netty types  and Reactor types ({@link NettyHttpChannel}
 *
 * @author Stephane Maldini
 */
final class NettyWebSocketServerHandler extends NettyHttpServerHandler {

	final WebSocketServerHandshaker handshaker;

	final ChannelFuture handshakerResult;

	final boolean plainText;

	public NettyWebSocketServerHandler(String wsUrl,
			String protocols,
			NettyHttpServerHandler originalHandler,
			boolean plainText
	) {
		super(originalHandler.getHandler(),
				null,
				originalHandler.request.delegate(),
				originalHandler);
		this.plainText = plainText;

		// Handshake
		WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(wsUrl, protocols, true);
		handshaker = wsFactory.newHandshaker(request.getNettyRequest());
		if (handshaker == null) {
			WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(request
					.delegate());
			handshakerResult = null;
		}
		else {
			HttpUtil.setTransferEncodingChunked(request.getNettyResponse(), false);
			handshakerResult = handshaker.handshake(request.delegate(),
					request.getNettyRequest(),
					request.responseHeaders(),
					request.delegate().newPromise()).addListener((ChannelFutureListener) future -> request.delegate().read());
		}
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object frame) throws Exception {
		if (CloseWebSocketFrame.class.equals(frame.getClass())) {
			handshaker.close(ctx.channel(), ((CloseWebSocketFrame) frame).retain());
			downstream().complete();
			return;
		}
		if (PingWebSocketFrame.class.isAssignableFrom(frame.getClass())) {
			ctx.channel().writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame)frame)
					.content().retain()));
			return;
		}
		doRead(frame);
	}

	@Override
	protected ChannelFuture doOnWrite(final Object data, final ChannelHandlerContext ctx) {
		if (data instanceof ByteBuf) {
			if (plainText){
				return ctx.write(new TextWebSocketFrame((ByteBuf)data));
			}
			return ctx.write(new BinaryWebSocketFrame((ByteBuf)data));
		}
		else if (data instanceof String) {
			return ctx.write(new TextWebSocketFrame((String)data));
		}
		return ctx.write(data);
	}
}
