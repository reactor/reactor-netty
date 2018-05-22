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

package reactor.ipc.netty.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http2.Http2Operations;
import reactor.util.Logger;
import reactor.util.Loggers;

public class Http2ClientOperations extends Http2Operations<NettyInbound, NettyOutbound>
		implements Http2ClientResponse, Http2ClientRequest, BiConsumer<Void, Throwable> {

	final boolean               isSecure;
	final Http2Headers          nettyRequest;

	volatile ResponseState responseState;
	int inboundPrefetch;

	boolean started;

	public Http2ClientOperations(Connection c, ConnectionObserver listener) {
		super(c, listener);
		this.isSecure = c.channel()
		                 .pipeline()
		                 .get(NettyPipeline.SslHandler) != null;
		this.nettyRequest = new DefaultHttp2Headers().method(HttpMethod.GET.name())
		                                             .path("/");
		this.inboundPrefetch = 16;
	}

	@Override
	public Http2ClientOperations addHandlerLast(ChannelHandler handler) {
		super.addHandlerLast(handler);
		return this;
	}

	@Override
	public Http2ClientOperations addHandlerLast(String name, ChannelHandler handler) {
		super.addHandlerLast(name, handler);
		return this;
	}

	@Override
	public Http2ClientOperations addHandlerFirst(ChannelHandler handler) {
		super.addHandlerFirst(handler);
		return this;
	}

	@Override
	public Http2ClientOperations addHandlerFirst(String name, ChannelHandler handler) {
		super.addHandlerFirst(name, handler);
		return this;
	}

	@Override
	public Http2ClientOperations addHandler(ChannelHandler handler) {
		super.addHandler(handler);
		return this;
	}

	@Override
	public Http2ClientOperations addHandler(String name, ChannelHandler handler) {
		super.addHandler(name, handler);
		return this;
	}

	@Override
	public Http2ClientOperations replaceHandler(String name, ChannelHandler handler) {
		super.replaceHandler(name, handler);
		return this;
	}

	@Override
	public Http2ClientOperations removeHandler(String name) {
		super.removeHandler(name);
		return this;
	}

	@Override
	public Http2ClientRequest addHeader(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.nettyRequest.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public InetSocketAddress address() {
		return ((SocketChannel) channel()).remoteAddress();
	}

	@Override
	public Http2ClientOperations withConnection(Consumer<? super Connection> withConnection) {
		Objects.requireNonNull(withConnection, "withConnection");
		withConnection.accept(this);
		return this;
	}

	@Override
	protected void onInboundCancel() {
		if (isInboundDisposed()){
			return;
		}
		channel().close();
	}

	@Override
	protected void onInboundClose() {
		if (isInboundCancelled() || isInboundDisposed()) {
			return;
		}
		if (responseState == null) {
			listener().onUncaughtException(this, new IOException("Connection closed prematurely"));
			return;
		}
		super.onInboundError(new IOException("Connection closed prematurely"));
	}

	@Override
	public Http2ClientRequest header(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.nettyRequest.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public Http2ClientRequest headers(Http2Headers headers) {
		if (!hasSentHeaders()) {
			this.nettyRequest.set(headers);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public CharSequence method() {
		return nettyRequest.method();
	}

	@Override
	public final Http2ClientOperations onDispose(Disposable onDispose) {
		super.onDispose(onDispose);
		return this;
	}

	@Override
	public Http2Headers requestHeaders() {
		return nettyRequest;
	}

	@Override
	public Http2Headers responseHeaders() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return responseState.response;
		}
		throw new IllegalStateException("Response headers cannot be accessed without " + "server response");
	}

	@Override
	public NettyOutbound send(Publisher<? extends ByteBuf> source) {
		if (Objects.equals(method(), HttpMethod.GET) || Objects.equals(method(), HttpMethod.HEAD)) {
			ByteBufAllocator alloc = channel().alloc();
			return then(Flux.from(source)
			                .doOnNext(ByteBuf::retain)
			                .collect(alloc::buffer, ByteBuf::writeBytes)
			                .flatMapMany(agg -> super.send(Mono.just(agg)).then()));
		}
		return super.send(source);
	}

	@Override
	public CharSequence status() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return responseState.response.status();
		}
		throw new IllegalStateException("Trying to access status() while missing response");
	}

	@Override
	public final CharSequence path() {
		return this.nettyRequest.path();
	}

	@Override
	protected void onOutboundComplete() {
		if (isInboundCancelled()) {
			return;
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug("No sendHeaders() called before complete, sending " + "zero-length header");
			}
			channel().writeAndFlush(new DefaultHttp2HeadersFrame(
					new DefaultHttp2Headers().method(nettyRequest.method())
					                         .path(nettyRequest.path()),
					true));
		}
		else if (markSentBody()) {
			channel().writeAndFlush(new DefaultHttp2DataFrame(true));
		}
		listener().onStateChange(this, REQUEST_SENT);
		channel().read();
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if(responseState == null){
			listener().onUncaughtException(this, err);
			terminate();
			return;
		}
		super.onOutboundError(err);
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http2HeadersFrame) {
			Http2HeadersFrame response = (Http2HeadersFrame) msg;
			if (started) {
				if (log.isDebugEnabled()) {
					log.debug("{} An Http2ClientOperations cannot proceed more than one response {}",
							channel(),
							response.headers());
				}
				return;
			}
			started = true;
			setNettyResponse(response.headers());
			if(isInboundCancelled()){
				ReferenceCountUtil.release(msg);
				return;
			}

			if (log.isDebugEnabled()) {
				log.debug("{} Received response (auto-read:{}) : {}",
						channel(),
						channel().config()
						         .isAutoRead(),
						responseState.response);
			}

			listener().onStateChange(this, RESPONSE_RECEIVED);
			prefetchMore(ctx);

			if (response.isEndStream()) {
				super.onInboundNext(ctx, msg);
				terminate();
			}
			return;
		}
		if (msg instanceof Http2DataFrame && ((Http2DataFrame) msg).isEndStream()) {
			if (!started) {
				if (log.isDebugEnabled()) {
					log.debug("{} Http2ClientOperations received an incorrect end " +
							"delimiter" + "(previously used connection?)", channel());
				}
				return;
			}
			if (log.isDebugEnabled()) {
				log.debug("{} Received last HTTP packet", channel());
			}
			//force auto read to enable more accurate close selection now inbound is done
			channel().config().setAutoRead(true);
			if (markSentBody()) {
				markPersistent(false);
			}
			terminate();
			return;
		}

		if (!started) {
			if (log.isDebugEnabled()) {
				if (msg instanceof ByteBufHolder) {
					msg = ((ByteBufHolder) msg).content();
				}
				log.debug("{} Http2ClientOperations received an incorrect chunk {} " +
								"(previously used connection?)",
						channel(), msg);
			}
			return;
		}
		super.onInboundNext(ctx, msg);
		prefetchMore(ctx);
	}

	@Override
	protected Http2Headers outboundHttpMessage() {
		return nettyRequest;
	}

	final Http2Headers getNettyRequest() {
		return nettyRequest;
	}

	final Mono<Void> send() {
		if (markSentHeaderAndBody()) {
			return FutureMono.deferFuture(() -> channel().writeAndFlush(new DefaultHttp2HeadersFrame(
					new DefaultHttp2Headers().method(nettyRequest.method())
					                         .path(nettyRequest.path()),
					true)));
		}
		else {
			return Mono.empty();
		}
	}

	final void prefetchMore(ChannelHandlerContext ctx) {
		int inboundPrefetch = this.inboundPrefetch - 1;
		if (inboundPrefetch >= 0) {
			this.inboundPrefetch = inboundPrefetch;
			ctx.read();
		}
	}

	final void setNettyResponse(Http2Headers nettyResponse) {
		ResponseState state = responseState;
		if (state == null) {
			this.responseState =
					new ResponseState(nettyResponse);
		}
	}

	static final class ResponseState {

		final Http2Headers response;

		ResponseState(Http2Headers response) {
			this.response = response;
		}
	}

	static final Logger                 log                =
			Loggers.getLogger(Http2ClientOperations.class);

	static final ConnectionObserver.State REQUEST_SENT = new ConnectionObserver.State() {
		@Override
		public String toString() {
			return "[request_sent]";
		}
	};
	static final ConnectionObserver.State RESPONSE_RECEIVED = new ConnectionObserver.State
			() {
		@Override
		public String toString() {
			return "[response_received]";
		}
	};

	@Override
	public void accept(Void aVoid, Throwable throwable) {
		if (throwable == null) {
			if (channel().isActive()) {
				onOutboundComplete();
			}
		}
		else {
			onOutboundError(throwable);
		}
	}
}
