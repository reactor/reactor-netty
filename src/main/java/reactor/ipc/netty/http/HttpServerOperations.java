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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Receiver;
import reactor.core.Trackable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.channel.NettyHandlerNames;
import reactor.ipc.netty.channel.NettyOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Conversion between Netty types  and Reactor types ({@link HttpOperations}.
 *
 * @author Stephane Maldini
 */
class HttpServerOperations extends HttpOperations<HttpServerRequest, HttpServerResponse>
		implements HttpServerRequest, HttpServerResponse {

	static HttpServerOperations bindHttp(Channel channel,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		HttpServerOperations ops = new HttpServerOperations(channel, handler);

		channel.attr(OPERATIONS_ATTRIBUTE_KEY)
		       .set(ops);

		NettyOperations.addHandler(channel);

		return ops;
	}

	final HttpResponse nettyResponse;
	final HttpHeaders  responseHeaders;

	Cookies                                       cookies;
	HttpRequest                                   nettyRequest;
	Function<? super String, Map<String, Object>> paramsResolver;

	HttpServerOperations(Channel ch, HttpServerOperations replaced) {
		super(ch, replaced);
		this.cookies = replaced.cookies;
		this.responseHeaders = replaced.responseHeaders;
		this.nettyResponse = replaced.nettyResponse;
		this.paramsResolver = replaced.paramsResolver;
	}

	HttpServerOperations(Channel ch,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		super(ch, handler, null);
		this.nettyResponse =
				new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		this.responseHeaders = nettyResponse.headers();
		responseHeaders.add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		//FIXME when keep alive is supported
		responseHeaders.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
	}

	@Override
	public String uri() {
		if (nettyRequest != null) {
			return nettyRequest.uri();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpVersion version() {
		if (nettyRequest != null) {
			return nettyRequest.protocolVersion();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpServerResponse addCookie(Cookie cookie) {
		if (statusAndHeadersSent == 0) {
			this.responseHeaders.add(HttpHeaderNames.SET_COOKIE,
					ServerCookieEncoder.STRICT.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public Flux<?> receiveObject() {
		// Handle the 'Expect: 100-continue' header if necessary.
		// TODO: Respond with 413 Request Entity Too Large
		//   and discard the traffic or close the connection.
		//       No need to notify the upstream handlers - just log.
		//       If decoding a response, just throw an error.
		if (HttpUtil.is100ContinueExpected(nettyRequest)) {
			return ChannelFutureMono.from(() -> delegate().writeAndFlush(CONTINUE))
			                        .thenMany(super.receiveObject());
		}
		else {
			return super.receiveObject();
		}
	}

	/**
	 * Accumulate a response HTTP header for the given key name, appending ";" for each
	 * new value
	 *
	 * @param name the HTTP response header name
	 * @param value the HTTP response header value
	 *
	 * @return this
	 */
	@Override
	public HttpServerResponse addHeader(CharSequence name, CharSequence value) {
		if (statusAndHeadersSent == 0) {
			this.responseHeaders.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse flushEach() {
		super.flushEach();
		return this;
	}

	@Override
	public void onActive(ChannelHandlerContext ctx) {
		ctx.read();
	}

	@Override
	public void onNext(Object msg) {
		if (msg instanceof HttpRequest) {
			nettyRequest = (HttpRequest) msg;
			cookies = Cookies.newServerRequestHolder(headers());

			if (isWebsocket()) {
				HttpObjectAggregator agg = new HttpObjectAggregator(65536);
				delegate().pipeline()
				          .addBefore(NettyHandlerNames.ReactiveBridge,
						          NettyHandlerNames.HttpAggregator,
						          agg);
			}

			handler().apply(this, this)
			         .subscribe(new HttpServerCloseSubscriber(this));

			if(!(msg instanceof FullHttpRequest)){
				return;
			}
		}
		if (msg instanceof HttpContent) {
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onNext(msg);
			}
			if (msg instanceof LastHttpContent) {
				onComplete();
			}
		}
		else{
			super.onNext(msg);
		}
	}

	@Override
	public HttpHeaders responseHeaders() {
		return responseHeaders;
	}

	@Override
	public HttpServerResponse disableChunkedTransfer() {
		HttpUtil.setTransferEncodingChunked(nettyResponse, false);
		return this;
	}

	@Override
	public HttpHeaders headers() {
		if (nettyRequest != null) {
			return nettyRequest.headers();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public boolean isKeepAlive() {
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public boolean isWebsocket() {
		String isWebsocket = headers().get(HttpHeaderNames.UPGRADE);
		return isWebsocket != null && isWebsocket.toLowerCase()
		                                         .equals("websocket");
	}

	@Override
	public HttpServerResponse keepAlive(boolean keepAlive) {
		HttpUtil.setKeepAlive(nettyResponse, keepAlive);
		return this;
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
	}

	/**
	 * Read URI param from the given key
	 *
	 * @param key matching key
	 *
	 * @return the resolved parameter for the given key name
	 */
	@Override
	public Object param(CharSequence key) {
		Map<String, Object> params = null;
		if (paramsResolver != null) {
			params = this.paramsResolver.apply(uri());
		}
		return null != params ? params.get(key) : null;
	}

	/**
	 * Read all URI params
	 *
	 * @return a map of resolved parameters against their matching key name
	 */
	@Override
	public Map<String, Object> params() {
		return null != paramsResolver ? paramsResolver.apply(uri()) : null;
	}

	/**
	 * @param headerResolver
	 */
	@Override
	public HttpServerRequest paramsResolver(Function<? super String, Map<String, Object>> headerResolver) {
		this.paramsResolver = headerResolver;
		return this;
	}

	/**
	 * Define the response HTTP header for the given key
	 *
	 * @param name the HTTP response header key to override
	 * @param value the HTTP response header content
	 *
	 * @return this
	 */
	@Override
	public HttpServerResponse header(CharSequence name, CharSequence value) {
		if (statusAndHeadersSent == 0) {
			this.responseHeaders.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse chunkedTransfer(boolean chunked) {
		HttpUtil.setTransferEncodingChunked(nettyResponse, chunked);
		return this;
	}

	/**
	 * @return the Transfer setting SSE for this http connection (e.g. event-stream)
	 */
	@Override
	public HttpServerResponse sse() {
		header(HttpHeaderNames.CONTENT_TYPE, EVENT_STREAM);
		return this;
	}

	@Override
	public HttpResponseStatus status() {
		return HttpResponseStatus.valueOf(this.nettyResponse.status()
		                                                    .code());
	}

	/**
	 * Set the response status to an outgoing response
	 *
	 * @param status the status to define
	 *
	 * @return this
	 */
	@Override
	public HttpServerResponse status(HttpResponseStatus status) {
		if (statusAndHeadersSent == 0) {
			this.nettyResponse.setStatus(status);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
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
		super.doOnTerminate(ctx,
				ctx.channel()
				   .write(Unpooled.EMPTY_BUFFER),
				promise,
				exception);
	}

	final Mono<Void> withWebsocketSupport(String url,
			String protocols,
			boolean textPlain,
			BiFunction<? super HttpInbound, ? super HttpOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (markHeadersAsFlushed()) {
			HttpServerWSOperations ops =
					new HttpServerWSOperations(url, protocols, this, textPlain);

			if (delegate().attr(OPERATIONS_ATTRIBUTE_KEY)
			              .compareAndSet(this, ops)) {
				return ChannelFutureMono.from(ops.handshakerResult)
				                        .then(() ->
						MonoSource
						.wrap(websocketHandler.apply(ops, ops)));
			}
		}
		else {
			log.error("Cannot enable websocket if headers have already been sent");
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	@Override
	protected void doSubscribeHeaders(Subscriber<? super Void> s) {
		ChannelFutureMono.from(delegate().writeAndFlush(nettyResponse))
		                 .subscribe(s);
	}

	@Override
	public Mono<Void> upgradeToWebsocket(String protocols,
			boolean textPlain,
			BiFunction<? super HttpInbound, ? super HttpOutbound, ? extends Publisher<Void>> websocketHandler) {
		return withWebsocketSupport(uri(), protocols, textPlain, websocketHandler);
	}

	@Override
	public Map<CharSequence, Set<Cookie>> cookies() {
		if (cookies != null) {
			return cookies.getCachedCookies();
		}
		throw new IllegalStateException("request not parsed");
	}

	final static class HttpServerCloseSubscriber
			implements Subscriber<Void>, Receiver, Trackable {

		final HttpServerOperations parent;
		Subscription subscription;

		public HttpServerCloseSubscriber(HttpServerOperations parent) {
			this.parent = parent;
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(subscription, s)) {
				subscription = s;
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onError(Throwable t) {
			if (t != null && t instanceof IOException && t.getMessage() != null && t.getMessage()
			                                                                        .contains(
					                                                                        "Broken " + "pipe")) {
				if (log.isDebugEnabled()) {
					log.debug("Connection closed remotely", t);
				}
				return;
			}
			log.error("Error processing connection. Closing the channel.", t);
			if (parent.markHeadersAsFlushed()) {
				parent.delegate()
				      .writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1,
						      HttpResponseStatus.INTERNAL_SERVER_ERROR))
				      .addListener(ChannelFutureListener.CLOSE);
			}
		}

		@Override
		public void onNext(Void aVoid) {

		}

		@Override
		public boolean isStarted() {
			return parent.delegate()
			             .isActive();
		}

		@Override
		public boolean isTerminated() {
			return !parent.delegate()
			           .isOpen();
		}

		@Override
		public Object upstream() {
			return subscription;
		}

		@Override
		public void onComplete() {
			if (parent.delegate()
			          .isOpen()) {
				if (log.isDebugEnabled()) {
					log.debug("Last Http Response packet");
				}
				ChannelFuture f;
				if (!parent.isWebsocket()) {
					if (parent.markHeadersAsFlushed()) {
						parent.delegate()
						      .write(parent.nettyResponse);
					}
					f = parent.delegate()
					          .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
				}
				else {
					f = parent.delegate()
					          .writeAndFlush(new CloseWebSocketFrame());
				}
				if (!parent.isKeepAlive()) {
					f.addListener(ChannelFutureListener.CLOSE);
				}
			}
		}
	}

	static final Logger log = Loggers.getLogger(HttpServerOperations.class);
}
