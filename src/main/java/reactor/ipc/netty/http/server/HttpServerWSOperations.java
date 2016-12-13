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

package reactor.ipc.netty.http.server;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.ReferenceCountUtil;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http.HttpOperations;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;

/**
 * Conversion between Netty types  and Reactor types ({@link HttpOperations}
 *
 * @author Stephane Maldini
 */
final class HttpServerWSOperations extends HttpServerOperations
		implements WebsocketInbound, WebsocketOutbound, BiConsumer<Void, Throwable> {

	final WebSocketServerHandshaker handshaker;
	final ChannelFuture             handshakerResult;

	volatile int closeSent;

	public HttpServerWSOperations(String wsUrl,
			String protocols, HttpServerOperations replaced) {
		super(replaced.channel(), replaced);

		Channel channel = replaced.channel();

		// Handshake
		WebSocketServerHandshakerFactory wsFactory =
				new WebSocketServerHandshakerFactory(wsUrl, protocols, true);
		handshaker = wsFactory.newHandshaker(replaced.nettyRequest);
		if (handshaker == null) {
			WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(channel);
			handshakerResult = null;
		}
		else {
			HttpUtil.setTransferEncodingChunked(replaced.nettyResponse, false);
			handshakerResult = handshaker.handshake(channel,
					replaced.nettyRequest,
					replaced.nettyRequest.headers(),
					channel.newPromise())
			                             .addListener(f -> {
				                             ignoreChannelPersistence();
				                             removeHandler(NettyPipeline.HttpKeepAlive);
				                             channel.read();
			                             });
		}
	}

	@Override
	public void onInboundNext(ChannelHandlerContext ctx, Object frame) {
		if (frame instanceof CloseWebSocketFrame) {
			CloseWebSocketFrame close = (CloseWebSocketFrame) frame;
			sendClose(new CloseWebSocketFrame(close.isFinalFragment(),
					close.rsv(),
					close.content()
					     .retain()), f -> onChannelInactive());
			return;
		}
		if (frame instanceof PingWebSocketFrame) {
			ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) frame).content()
			                                                                           .retain()));
			return;
		}
		super.onInboundNext(ctx, frame);
	}

	@Override
	protected void onOutboundComplete() {
	}

	@Override
	public void accept(Void aVoid, Throwable throwable) {
		if (throwable == null) {
			if (channel().isOpen()) {
				sendClose(null, f -> onChannelInactive());
			}
		}
		else {
			onOutboundError(throwable);
		}
	}

	void sendClose(CloseWebSocketFrame frame, ChannelFutureListener listener) {
		if (frame != null && !frame.isFinalFragment()) {
			channel().writeAndFlush(frame);
			return;
		}
		if (CLOSE_SENT.getAndSet(this, 1) == 0) {
			ChannelFuture f = channel().writeAndFlush(
					frame == null ? new CloseWebSocketFrame() : frame);
			if (listener != null) {
				f.addListener(listener);
			}
			return;
		}
		ReferenceCountUtil.retain(frame);
	}

	@Override
	public boolean isWebsocket() {
		return true;
	}

	static final AtomicIntegerFieldUpdater<HttpServerWSOperations> CLOSE_SENT =
			AtomicIntegerFieldUpdater.newUpdater(HttpServerWSOperations.class,
					"closeSent");
}
