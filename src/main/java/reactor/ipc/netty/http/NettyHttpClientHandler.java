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
import java.net.URI;
import java.util.function.Function;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.common.ChannelBridge;
import reactor.ipc.netty.common.NettyChannel;
import reactor.ipc.netty.common.NettyChannelHandler;

/**
 * @author Stephane Maldini
 */
class NettyHttpClientHandler extends NettyChannelHandler<HttpClientChannel> {

	HttpClientChannel                      httpChannel;
	DirectProcessor<Void>                  connectSignal;
	Subscriber<? super HttpClientResponse> replySubscriber;

	NettyHttpClientHandler(Function<? super NettyChannel, ? extends Publisher<Void>> handler,
			ChannelBridge<HttpClientChannel> channelBridge,
			Channel ch) {
		super(handler, channelBridge, ch);
	}

	NettyHttpClientHandler(Function<? super NettyChannel, ? extends Publisher<Void>> handler,
			ChannelBridge<HttpClientChannel> channelBridge,
			Channel ch, NettyHttpClientHandler parent) {
		super(handler, channelBridge, ch, parent);

		this.httpChannel = parent.httpChannel;
		this.replySubscriber = parent.replySubscriber;
	}

	@Override
	public void channelActive(final ChannelHandlerContext ctx) throws Exception {
		ctx.fireChannelActive();

		if (httpChannel != null) {
			return;
		}

		httpChannel = bridgeFactory.createChannelBridge(ctx.channel(), input);
		httpChannel.keepAlive(true);
		HttpUtil.setTransferEncodingChunked(httpChannel.nettyRequest, true);

		handler.apply(httpChannel)
		       .subscribe(new HttpClientCloseSubscriber(ctx));
	}

	void bridgeReply(Subscriber<? super HttpClientResponse> replySubscriber,
			DirectProcessor<Void> connectSignal) {
		this.replySubscriber = replySubscriber;
		this.connectSignal = connectSignal;
	}

	@Override
	protected void doOnTerminate(ChannelHandlerContext ctx,
			ChannelFuture last,
			ChannelPromise promise,
			Throwable exception) {
		super.doOnTerminate(ctx, ctx.channel().write(httpChannel != null && httpChannel
				.isWebsocket() ? Unpooled.EMPTY_BUFFER : LastHttpContent
				.EMPTY_LAST_CONTENT),
				promise,
				exception);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		Class<?> messageClass = msg.getClass();
		if (HttpResponse.class.isAssignableFrom(messageClass)) {

			HttpResponse response = (HttpResponse) msg;

			if (httpChannel != null) {
				httpChannel.setNettyResponse(response);
			}

			if(log.isDebugEnabled()){
				log.debug("Received response (auto-read:{}) : {}", ctx.channel().config
						().isAutoRead(), httpChannel.headers().toString());
			}

			if(checkResponseCode(ctx, response)) {
				ctx.fireChannelRead(msg);
				if (replySubscriber != null) {
					Flux.just(httpChannel)
					    .subscribe(replySubscriber);
				}
				else {
					log.debug(
							"No Response/ HttpInbound subscriber on {}, msg is dropped {}",
							ctx.channel(),
							msg);
				}
			}
			postRead(ctx, msg);
			return;
		}
		if(LastHttpContent.EMPTY_LAST_CONTENT != msg){
			doRead(msg);
		}
		postRead(ctx, msg);
	}

	final NettyWebSocketClientHandler withWebsocketSupport(URI url, String
			protocols, boolean textPlain){
		//prevent further header to be sent for handshaking
		if(!httpChannel.markHeadersAsFlushed()){
			log.error("Cannot enable websocket if headers have already been sent");
			return null;
		}
		return new NettyWebSocketClientHandler(url, protocols, this, textPlain);
	}

	final boolean checkResponseCode(ChannelHandlerContext ctx, HttpResponse response) throws
	                                                                          Exception {
		int code = response.status()
		                   .code();
		if (code >= 400) {
			Exception ex = new HttpException(httpChannel);
			if (connectSignal != null) {
				connectSignal.onError(ex);
			}
			else if (replySubscriber != null) {
				Operators.error(replySubscriber, ex);
			}
			return false;
		}
		if (code >= 300 && httpChannel.isFollowRedirect()) {
			Exception ex = new RedirectException(httpChannel);
			if (connectSignal != null) {
				connectSignal.onError(ex);
			}
			else if (replySubscriber != null) {
				Operators.error(replySubscriber, ex);
			}
			return false;
		}
		if (connectSignal != null) {
			connectSignal.onComplete();
		}
		return true;
	}

	protected void postRead(ChannelHandlerContext ctx, Object msg){
		if (msg instanceof LastHttpContent) {
			if(log.isDebugEnabled()){
				log.debug("Read last http packet");
			}
			ctx.channel().close();
			downstream().complete();
		}
	}

	static class HttpClientCloseSubscriber implements Subscriber<Void> {

		private final ChannelHandlerContext ctx;

		public HttpClientCloseSubscriber(ChannelHandlerContext ctx) {
			this.ctx = ctx;
		}

		@Override
		public void onSubscribe(final Subscription s) {
			ctx.read();
			Operators.validate(null, s);
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onError(Throwable t) {
			if (t == null) {
				throw Exceptions.argumentIsNullException();
			}
			if(t instanceof IOException && t.getMessage() != null && t.getMessage().contains("Broken pipe")){
				if (log.isDebugEnabled()) {
					log.debug("Connection closed remotely", t);
				}
				return;
			}
			if (ctx.channel()
			       .isOpen()) {
				if(log.isDebugEnabled()){
					log.error("Closing HTTP channel due to error", t);
				}
				ctx.channel()
				   .close();
			}
		}

		@Override
		public void onNext(Void aVoid) {
		}

		@Override
		public void onComplete() {
		}
	}
}
