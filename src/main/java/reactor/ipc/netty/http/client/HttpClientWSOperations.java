/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.client;

import java.net.URI;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiConsumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;

/**
 * @author Stephane Maldini
 * @author Simon Baslé
 */
final class HttpClientWSOperations extends HttpClientOperations
		implements WebsocketInbound, WebsocketOutbound, BiConsumer<Void, Throwable> {

	final WebSocketClientHandshaker handshaker;
	final ChannelPromise            handshakerResult;

	volatile int closeSent;

	HttpClientWSOperations(URI currentURI,
			String protocols,
			HttpClientOperations replaced) {
		super(replaced.channel(), replaced);

		Channel channel = channel();

		handshaker = WebSocketClientHandshakerFactory.newHandshaker(currentURI,
				WebSocketVersion.V13,
				protocols,
				true,
				replaced.requestHeaders().remove(HttpHeaderNames.HOST));

		handshakerResult = channel.newPromise();

		String handlerName = channel.pipeline()
		                            .context(HttpRequestEncoder.class)
		                            .name();

		if (!handlerName.equals(NettyPipeline.HttpEncoder)) {
			channel.pipeline()
			       .remove(handlerName);
		}
		handshaker.handshake(channel)
		          .addListener(f -> {
			          ignoreChannelPersistence();
			          channel.read();
		          });
	}

	@Override
	public boolean isWebsocket() {
		return true;
	}

	@Override
	public String selectedSubprotocol() {
		return handshaker.actualSubprotocol();
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof FullHttpResponse) {
			started = true;
			channel().pipeline()
			         .remove(HttpObjectAggregator.class);
			FullHttpResponse response = (FullHttpResponse) msg;

			setNettyResponse(response);

			if (checkResponseCode(response)) {

				try {
					if (!handshaker.isHandshakeComplete()) {
						handshaker.finishHandshake(channel(), response);
					}
				}
				catch (WebSocketHandshakeException wshe) {
					sendClose(null);
					throw wshe;
				}

				parentContext().fireContextActive(this);
				handshakerResult.trySuccess();
			}
			return;
		}
		if (msg instanceof PingWebSocketFrame) {
			channel().writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) msg).content()));
			ctx.read();
			return;
		}
		if (msg instanceof CloseWebSocketFrame &&
				((CloseWebSocketFrame)msg).isFinalFragment()) {
			if (log.isDebugEnabled()) {
				log.debug("CloseWebSocketFrame detected. Closing Websocket");
			}

			CloseWebSocketFrame close = (CloseWebSocketFrame) msg;
			sendClose(new CloseWebSocketFrame(true,
					close.rsv(),
					close.content()));
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	public WebsocketInbound receiveWebsocket() {
		return this;
	}

	@Override
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			log.debug("Cancelling Websocket inbound. Closing Websocket");
		}
		sendClose(null);
	}

	@Override
	protected void onOutboundComplete() {
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (channel().isActive()) {
			sendClose(new CloseWebSocketFrame(1002, "Client internal error"));
		}
	}

	void sendClose(CloseWebSocketFrame frame) {
		if (frame != null && !frame.isFinalFragment()) {
			channel().writeAndFlush(frame);
			return;
		}
		if (CLOSE_SENT.getAndSet(this, 1) == 0) {
			ChannelFuture f = channel().writeAndFlush(
					frame == null ? new CloseWebSocketFrame() : frame);
			f.addListener(ChannelFutureListener.CLOSE);
		}
	}

	@Override
	public void accept(Void aVoid, Throwable throwable) {
		if (log.isDebugEnabled()) {
			log.debug("Handler terminated. Closing Websocket");
		}
		if (throwable == null) {
			if (channel().isActive()) {
				sendClose(null);
			}
		}
		else {
			onOutboundError(throwable);
		}
	}

	static final AtomicIntegerFieldUpdater<HttpClientWSOperations> CLOSE_SENT =
			AtomicIntegerFieldUpdater.newUpdater(HttpClientWSOperations.class,
					"closeSent");
}
