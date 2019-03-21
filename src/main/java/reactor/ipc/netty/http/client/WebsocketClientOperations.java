/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.client;

import java.net.URI;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;
import reactor.util.annotation.Nullable;

/**
 * @author Stephane Maldini
 * @author Simon Baslé
 */
final class WebsocketClientOperations extends HttpClientOperations
		implements WebsocketInbound, WebsocketOutbound {

	final WebSocketClientHandshaker handshaker;

	volatile int closeSent;

	WebsocketClientOperations(URI currentURI,
			String protocols,
			HttpClientOperations replaced) {
		super(replaced);
		Channel channel = channel();

		handshaker = WebSocketClientHandshakerFactory.newHandshaker(currentURI,
					WebSocketVersion.V13,
					protocols.isEmpty() ? null : protocols,
					true,
					replaced.requestHeaders()
					        .remove(HttpHeaderNames.HOST));

		handshaker.handshake(channel)
		          .addListener(f -> {
			          markPersistent(false);
			          channel.read();
		          });
	}

	@Override
	public HttpHeaders headers() {
		return responseHeaders();
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

			if (notRedirected(response)) {


				try {
					handshaker.finishHandshake(channel(), response);
					listener().onStateChange(this, RESPONSE_RECEIVED);
				}
				catch (WebSocketHandshakeException wshe) {
					onInboundError(wshe);
				}
				finally {
					//Release unused content (101 status)
					response.content()
					        .release();
				}

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
			onInboundComplete();
			CloseWebSocketFrame close = (CloseWebSocketFrame) msg;
			sendCloseNow(new CloseWebSocketFrame(true,
					close.rsv(),
					close.content()));
		}
		else if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			log.debug("Cancelling Websocket inbound. Closing Websocket");
		}
		sendCloseNow(null);
	}

	@Override
	protected void onInboundClose() {
		terminate();
	}

	@Override
	protected void onOutboundComplete() {
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (channel().isActive()) {
			sendCloseNow(new CloseWebSocketFrame(1002, "Client internal error"));
		}
	}

	@Override
	public Mono<Void> sendClose() {
		return sendClose(new CloseWebSocketFrame());
	}

	@Override
	public Mono<Void> sendClose(int rsv) {
		return sendClose(new CloseWebSocketFrame(true, rsv));
	}

	@Override
	public Mono<Void> sendClose(int statusCode, @javax.annotation.Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(statusCode, reasonText));
	}

	@Override
	public Mono<Void> sendClose(int rsv, int statusCode, @javax.annotation.Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(true, rsv, statusCode, reasonText));
	}

	Mono<Void> sendClose(CloseWebSocketFrame frame) {
		if (CLOSE_SENT.get(this) == 0) {
			return FutureMono.deferFuture(() -> {
				if (CLOSE_SENT.getAndSet(this, 1) == 0) {
					return channel().writeAndFlush(frame)
					                .addListener(ChannelFutureListener.CLOSE);
				}
				return channel().newSucceededFuture();
			});
		}
		return Mono.empty();
	}

	void sendCloseNow(@Nullable CloseWebSocketFrame frame) {
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

	static final AtomicIntegerFieldUpdater<WebsocketClientOperations> CLOSE_SENT =
			AtomicIntegerFieldUpdater.newUpdater(WebsocketClientOperations.class,
					"closeSent");
}
