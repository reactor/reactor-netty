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

import java.io.IOException;
import java.util.function.Function;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Receiver;
import reactor.core.Trackable;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.common.ChannelBridge;
import reactor.ipc.netty.common.NettyChannel;
import reactor.ipc.netty.common.NettyChannelHandler;
import reactor.ipc.netty.common.NettyHandlerNames;

/**
 * Conversion between Netty types  and Reactor types ({@link NettyHttpChannel}.
 *
 * @author Stephane Maldini
 */
class NettyHttpServerHandler extends NettyChannelHandler<NettyHttpChannel> {

	     NettyHttpChannel request;

	NettyHttpServerHandler(
			Function<? super NettyChannel, ? extends Publisher<Void>> handler,
			ChannelBridge<NettyHttpChannel> channelBridge,
			Channel ch) {
		super(handler, channelBridge, ch);
	}


	NettyHttpServerHandler(Function<? super NettyChannel, ? extends Publisher<Void>> handler,
			ChannelBridge<NettyHttpChannel> channelBridge,
			Channel ch, NettyHttpServerHandler parent) {
		super(handler, channelBridge, ch, parent);
		this.request = parent.request;

	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		ctx.fireChannelActive();
		ctx.read();
	}

	@Override
	public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
		Class<?> messageClass = msg.getClass();
		if (request == null && io.netty.handler.codec.http.HttpRequest.class.isAssignableFrom(messageClass)) {
			request = bridgeFactory.createChannelBridge(ctx.channel(), input, msg);

			if (request.isWebsocket()) {
				HttpObjectAggregator agg = new HttpObjectAggregator(65536);
				ctx.pipeline().addBefore(NettyHandlerNames.ReactiveBridge,
						NettyHandlerNames.HttpAggregator, agg);
			}

			final Publisher<Void> closePublisher = handler.apply(request);
			final Subscriber<Void> closeSub = new HttpServerCloseSubscriber(request, ctx);

			closePublisher.subscribe(closeSub);

		}
		if (HttpContent.class.isAssignableFrom(messageClass)) {
			doRead(msg);
			if (LastHttpContent.class.isAssignableFrom(msg.getClass())) {
				downstream().complete();
			}
		}
	}

	@Override
	protected ChannelFuture doOnWrite(final Object data, final ChannelHandlerContext ctx) {
		return ctx.write(data);
	}

	@Override
	protected void doOnTerminate(ChannelHandlerContext ctx,
			ChannelFuture last,
			ChannelPromise promise,
			Throwable exception) {
		super.doOnTerminate(ctx, ctx.channel().write(Unpooled.EMPTY_BUFFER),
				promise,
				exception);
	}

	final NettyWebSocketServerHandler withWebsocketSupport(String url, String
			protocols, boolean textPlain){
		//prevent further header to be sent for handshaking
		if(!request.markHeadersAsFlushed()){
			log.error("Cannot enable websocket if headers have already been sent");
			return null;
		}
		return new NettyWebSocketServerHandler(url, protocols, this, textPlain);
	}

	final static class HttpServerCloseSubscriber implements Subscriber<Void>, Receiver,
	                                                        Trackable {

		final NettyHttpChannel request;
		final ChannelHandlerContext ctx;
		Subscription subscription;

		public HttpServerCloseSubscriber(NettyHttpChannel request, ChannelHandlerContext ctx) {
			this.ctx = ctx;
			this.request = request;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if(Operators.validate(subscription, s)) {
				subscription = s;
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onError(Throwable t) {
			if(t != null && t instanceof IOException && t.getMessage() != null && t.getMessage().contains("Broken " +
					"pipe")){
				if (log.isDebugEnabled()) {
					log.debug("Connection closed remotely", t);
				}
				return;
			}
			log.error("Error processing connection. Closing the channel.", t);
			if (request.markHeadersAsFlushed()) {
				request.delegate()
				       .writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR))
						.addListener(ChannelFutureListener.CLOSE);
			}
		}

		@Override
		public void onNext(Void aVoid) {

		}

		@Override
		public boolean isStarted() {
			return ctx.channel().isActive();
		}

		@Override
		public boolean isTerminated() {
			return !ctx.channel().isOpen();
		}

		@Override
		public Object upstream() {
			return subscription;
		}

		@Override
		public void onComplete() {
			if (ctx.channel().isOpen()) {
				if (log.isDebugEnabled()) {
					log.debug("Last Http Response packet");
				}
				ChannelFuture f;
				if(!request.isWebsocket()) {
					if (request.markHeadersAsFlushed()) {
						ctx.write(request.getNettyResponse());
					}
					f = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
				}
				else{
					f = ctx.channel().writeAndFlush(new CloseWebSocketFrame());
				}
				if (!request.isKeepAlive()) {
					f.addListener(ChannelFutureListener.CLOSE);
				}
			}
		}
	}
}
