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

import java.net.URI;

import io.netty.buffer.ByteBuf;
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
import reactor.core.publisher.Flux;
import reactor.ipc.netty.common.NettyHandlerNames;

/**
 * @author Stephane Maldini
 */
final class NettyWebSocketClientHandler extends NettyHttpClientHandler {

	final WebSocketClientHandshaker handshaker;

	final ChannelPromise handshakerResult;

	final boolean plainText;

	NettyWebSocketClientHandler(
			URI currentURI,
			String protocols,
			NettyHttpClientHandler originalHandler,
			boolean plainText) {
		super(originalHandler.getHandler(),
				null,
				originalHandler.httpChannel.delegate(),
				originalHandler);
		this.plainText = plainText;

		handshaker =
				WebSocketClientHandshakerFactory.newHandshaker(currentURI,
						WebSocketVersion.V13,
						protocols,
						true,
						httpChannel.headers());

		handshakerResult = httpChannel.delegate().newPromise();
		if(!originalHandler.httpChannel.delegate().pipeline()
		       .context(HttpClientCodec.class)
		       .name()
		       .equals(NettyHandlerNames.HttpCodecHandler)){
			originalHandler.httpChannel.delegate().pipeline().remove(HttpClientCodec.class);
		}
		handshaker.handshake(httpChannel.delegate()).addListener(f -> {
			if(!f.isSuccess()) {
				handshakerResult.tryFailure(f.cause());
				return;
			}
			httpChannel.delegate().read();
		});
	}

	@Override
	protected void postRead(ChannelHandlerContext ctx, Object msg) {
		if(msg instanceof CloseWebSocketFrame){
			if(log.isDebugEnabled()){
				log.debug("Closing Websocket");
			}
			ctx.channel().close();
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		Class<?> messageClass = msg.getClass();
		if (FullHttpResponse.class.isAssignableFrom(messageClass)) {
			ctx.pipeline().remove(HttpObjectAggregator.class);
			HttpResponse response = (HttpResponse) msg;
			if (httpChannel != null) {
				httpChannel.setNettyResponse(response);
			}

			if(checkResponseCode(ctx, response)) {

				if (!handshaker.isHandshakeComplete()) {
					handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
				}
				ctx.fireChannelRead(msg);
				handshakerResult.trySuccess();

				if (replySubscriber != null) {
					Flux.just(httpChannel)
					    .subscribe(replySubscriber);
				}
			}
			return;
		}
		if (PingWebSocketFrame.class.isAssignableFrom(messageClass)) {
			ctx.channel().writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame)msg)
					.content().retain()));
			return;
		}
		if (TextWebSocketFrame.class.isAssignableFrom(messageClass)) {
			downstream().next(msg);
		} else if (CloseWebSocketFrame.class.isAssignableFrom(messageClass)) {
			if(log.isDebugEnabled()){
				log.debug("Closing Websocket");
			}
			ctx.close();
		} else {
			doRead(msg);
		}
	}

	@Override
	protected ChannelFuture doOnWrite(Object data, ChannelHandlerContext ctx) {
		if (data instanceof ByteBuf) {
			if (plainText){
				return ctx.write(new TextWebSocketFrame((ByteBuf)data));
			}
			return ctx.write(new BinaryWebSocketFrame((ByteBuf)data));
		}
		else if (data instanceof String) {
			return ctx.write(new TextWebSocketFrame((String)data));
		}
		else {
			return ctx.write(data);
		}
	}
}
