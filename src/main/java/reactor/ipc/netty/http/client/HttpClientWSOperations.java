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

package reactor.ipc.netty.http.client;

import java.net.URI;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import reactor.ipc.netty.NettyHandlerNames;

/**
 * @author Stephane Maldini
 */
final class HttpClientWSOperations extends HttpClientOperations
		implements GenericFutureListener<Future<Void>> {

	final WebSocketClientHandshaker handshaker;
	final ChannelPromise            handshakerResult;
	final boolean                   plainText;

	HttpClientWSOperations(URI currentURI,
			String protocols, HttpClientOperations replaced,
			boolean plainText) {
		super(replaced.channel(), replaced);
		this.plainText = plainText;

		Channel channel = channel();

		handshaker = WebSocketClientHandshakerFactory.newHandshaker(currentURI,
				WebSocketVersion.V13,
				protocols,
				true,
				replaced.requestHeaders());

		handshakerResult = channel.newPromise();

		String handlerName = channel.pipeline()
		                            .context(HttpClientCodec.class)
		                            .name();

		if (!handlerName.equals(NettyHandlerNames.HttpCodecHandler)) {
			channel.pipeline()
			       .remove(handlerName);
		}
		handshaker.handshake(channel)
		          .addListener(this);
	}

	@Override
	public boolean isWebsocket() {
		return true;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		Class<?> messageClass = msg.getClass();
		if (FullHttpResponse.class.isAssignableFrom(messageClass)) {
			channel().pipeline()
			         .remove(HttpObjectAggregator.class);
			HttpResponse response = (HttpResponse) msg;
			setNettyResponse(response);

			if (checkResponseCode(response)) {

				if (!handshaker.isHandshakeComplete()) {
					handshaker.finishHandshake(channel(), (FullHttpResponse) msg);
				}
				handshakerResult.trySuccess();

				parentContext().fireContextActive(this);
			}
			return;
		}
		if (PingWebSocketFrame.class.isAssignableFrom(messageClass)) {
			channel().writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) msg).content()
			                                                                         .retain()));
			return;
		}
		if (CloseWebSocketFrame.class.isAssignableFrom(messageClass)) {
			if (log.isDebugEnabled()) {
				log.debug("Closing Websocket");
			}
			onChannelComplete();
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	public void operationComplete(Future f) throws Exception {
		if (!f.isSuccess()) {
			handshakerResult.tryFailure(f.cause());
			return;
		}
		channel().read();
	}

	@Override
	protected void postRead(Object msg) {
		if (msg instanceof CloseWebSocketFrame) {
			if (log.isDebugEnabled()) {
				log.debug("Closing Websocket");
			}
			onChannelComplete();
		}
	}

	@Override
	public ChannelFuture sendNext(Object data) {
		if (data instanceof ByteBuf) {
			if (plainText) {
				return channel().write(new TextWebSocketFrame((ByteBuf) data));
			}
			return channel().write(new BinaryWebSocketFrame((ByteBuf) data));
		}
		else if (data instanceof String) {
			return channel().write(new TextWebSocketFrame((String) data));
		}
		else {
			return channel().write(data);
		}
	}
}
