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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.MonoSource;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.NettyState;
import reactor.ipc.netty.channel.NettyHandlerNames;
import reactor.ipc.netty.channel.NettyOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
class HttpClientOperations extends HttpOperations<HttpClientResponse, HttpClientRequest>
		implements HttpClientResponse, HttpClientRequest {

	static HttpOperations bindHttp(Channel channel,
			BiFunction<? super HttpClientResponse, ? super HttpClientRequest, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> clientSink) {

		HttpClientOperations ops = new HttpClientOperations(channel, handler, clientSink);

		channel.attr(OPERATIONS_ATTRIBUTE_KEY)
		       .set(ops);

		NettyOperations.addHandler(channel);

		return ops;
	}

	final String[]    redirectedFrom;
	final boolean     isSecure;
	final HttpRequest nettyRequest;
	final HttpHeaders headers;

	volatile ResponseState responseState;

	boolean redirectable;

	HttpClientOperations(Channel channel, HttpClientOperations replaced) {
		super(channel, replaced);
		this.redirectedFrom = replaced.redirectedFrom;
		this.isSecure = replaced.isSecure;
		this.nettyRequest = replaced.nettyRequest;
		this.responseState = replaced.responseState;
		this.redirectable = replaced.redirectable;
		this.headers = replaced.headers;
	}

	HttpClientOperations(Channel channel,
			BiFunction<? super HttpClientResponse, ? super HttpClientRequest, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> clientSink) {
		super(channel, handler, clientSink);
		this.isSecure = channel.pipeline()
		                       .get(NettyHandlerNames.SslHandler) != null;
		String[] redirects = channel.attr(REDIRECT_ATTR_KEY)
		                            .get();
		this.redirectedFrom = redirects == null ? EMPTY_REDIRECTIONS : redirects;
		this.nettyRequest =
				new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
		this.headers = nettyRequest.headers();
	}

	@Override
	public HttpClientRequest addCookie(Cookie cookie) {
		if (statusAndHeadersSent == 0) {
			this.headers.add(HttpHeaderNames.COOKIE,
					ClientCookieEncoder.STRICT.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
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
	public HttpClientRequest addHeader(CharSequence name, CharSequence value) {
		if (statusAndHeadersSent == 0) {
			this.headers.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public Map<CharSequence, Set<Cookie>> cookies() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return responseState.cookies.getCachedCookies();
		}
		return null;
	}

	@Override
	public HttpClientRequest disableChunkedTransfer() {
		HttpUtil.setTransferEncodingChunked(nettyRequest, false);
		return this;
	}

	@Override
	public HttpClientRequest flushEach() {
		super.flushEach();
		return this;
	}

	@Override
	public HttpClientRequest followRedirect() {
		redirectable = true;
		return this;
	}

	@Override
	public Mono<Void> onClose() {
		return ChannelFutureMono.from(channel().closeFuture());
	}

	@Override
	public void dispose() {
		try {
			channel().close()
			         .sync();
		}
		catch (InterruptedException ie) {
			Thread.currentThread()
			      .interrupt();
		}
	}

	@Override
	public InetSocketAddress address() {
		return ((SocketChannel) channel()).remoteAddress();
	}

	/**
	 * Register an HTTP request header
	 *
	 * @param name Header name
	 * @param value Header content
	 *
	 * @return this
	 */
	@Override
	public HttpClientRequest header(CharSequence name, CharSequence value) {
		if (statusAndHeadersSent == 0) {
			this.headers.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpHeaders headers() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return responseState.headers;
		}
		else {
			return null;
		}
	}

	@Override
	public boolean isFollowRedirect() {
		return redirectable && redirectedFrom.length <= MAX_REDIRECTS;
	}

	@Override
	public boolean isKeepAlive() {
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public HttpClientRequest keepAlive(boolean keepAlive) {
		HttpUtil.setKeepAlive(nettyRequest, keepAlive);
		return this;
	}

	final HttpRequest getNettyRequest() {
		return nettyRequest;
	}

	@Override
	public final String uri() {
		return this.nettyRequest.uri();
	}

	@Override
	public final HttpVersion version() {
		HttpVersion version = this.nettyRequest.protocolVersion();
		if (version.equals(HttpVersion.HTTP_1_0)) {
			return HttpVersion.HTTP_1_0;
		}
		else if (version.equals(HttpVersion.HTTP_1_1)) {
			return HttpVersion.HTTP_1_1;
		}
		throw new IllegalStateException(version.protocolName() + " not supported");
	}

	@Override
	public void onActive(final ChannelHandlerContext ctx) {
		HttpUtil.setTransferEncodingChunked(nettyRequest, true);

		handler().apply(this, this)
		         .subscribe(new HttpClientCloseSubscriber(ctx));
	}

	@Override
	public void onNext(Object msg) {
		if (msg instanceof HttpResponse) {
			HttpResponse response = (HttpResponse) msg;
			setNettyResponse(response);

			if (log.isDebugEnabled()) {
				log.debug("Received response (auto-read:{}) : {}",
						channel().config()
						         .isAutoRead(),
						headers().toString());
			}

			if (checkResponseCode(response)) {
				clientSink().success(this);
			}
			else {
				log.debug("Failed status check on response packet", channel(), msg);
			}
			postRead(msg);
			return;
		}
		if (LastHttpContent.EMPTY_LAST_CONTENT != msg) {
			super.onNext(msg);
		}
		postRead(msg);
	}

	@Override
	public String[] redirectedFrom() {
		String[] redirectedFrom = this.redirectedFrom;
		String[] dest = new String[redirectedFrom.length];
		System.arraycopy(redirectedFrom, 0, dest, 0, redirectedFrom.length);
		return dest;
	}

	@Override
	public HttpHeaders requestHeaders() {
		return nettyRequest.headers();
	}

	@Override
	public HttpResponseStatus status() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return HttpResponseStatus.valueOf(responseState.response.status()
			                                                        .code());
		}
		return null;
	}

	@Override
	public Mono<Void> upgradeToWebsocket(String protocols,
			boolean textPlain,
			BiFunction<? super HttpInbound, ? super HttpOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		ChannelPipeline pipeline = channel().pipeline();

		URI uri;
		try {
			String url = uri();
			if (url.startsWith(HttpClient.HTTP_SCHEME) || url.startsWith(HttpClient.WS_SCHEME)) {
				uri = new URI(url);
			}
			else {
				String host = requestHeaders().get(HttpHeaderNames.HOST);
				uri = new URI((isSecure ? HttpClient.WSS_SCHEME :
						HttpClient.WS_SCHEME) + "://" + host + (url.startsWith("/") ?
						url : "/" + url));
			}
			requestHeaders().remove(HttpHeaderNames.HOST);

		}
		catch (URISyntaxException e) {
			throw Exceptions.bubble(e);
		}

		pipeline.addBefore(NettyHandlerNames.ReactiveBridge,
				NettyHandlerNames.HttpAggregator,
				new HttpObjectAggregator(8192));

		return withWebsocketSupport(uri, protocols, textPlain, websocketHandler);
	}

	@Override
	protected void doOnTerminate(ChannelHandlerContext ctx,
			ChannelFuture last,
			ChannelPromise promise,
			Throwable exception) {
		super.doOnTerminate(ctx,
				ctx.write(isWebsocket() ? Unpooled.EMPTY_BUFFER :
						LastHttpContent.EMPTY_LAST_CONTENT),
				promise,
				exception);
	}

	protected void postRead(Object msg) {
		if (msg instanceof LastHttpContent) {
			if (log.isDebugEnabled()) {
				log.debug("Read last http packet");
			}
			channel().close();
		}
	}

	@Override
	protected void doSubscribeHeaders(Subscriber<? super Void> s) {
		ChannelFutureMono.from(channel().writeAndFlush(nettyRequest))
		                 .subscribe(s);
	}

	final boolean checkResponseCode(HttpResponse response)  {
		int code = response.status()
		                   .code();
		if (code >= 400) {
			Exception ex = new HttpClientException(this);
			clientSink().error(ex);
			return false;
		}
		if (code >= 300 && isFollowRedirect()) {
			Exception ex = new RedirectClientException(this);
			clientSink().error(ex);
			return false;
		}
		return true;
	}

	final void setNettyResponse(HttpResponse nettyResponse) {
		ResponseState state = responseState;
		if (state == null) {
			this.responseState =
					new ResponseState(nettyResponse, nettyResponse.headers());
		}
	}

	final Mono<Void> withWebsocketSupport(URI url,
			String protocols,
			boolean textPlain,
			BiFunction<? super HttpInbound, ? super HttpOutbound, ? extends Publisher<Void>> websocketHandler) {

		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		//prevent further header to be sent for handshaking
		if (markHeadersAsFlushed()) {
			HttpClientWSOperations ops =
					new HttpClientWSOperations(url, protocols, this, textPlain);

			if (channel().attr(OPERATIONS_ATTRIBUTE_KEY)
			             .compareAndSet(this, ops)) {
				return ChannelFutureMono.from(ops.handshakerResult)
				                        .then(() -> MonoSource.wrap(websocketHandler.apply(
						                        ops,
						                        ops)));
			}
		}
		else {
			log.error("Cannot enable websocket if headers have already been sent");
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	static class HttpClientCloseSubscriber implements Subscriber<Void> {

		private final ChannelHandlerContext ctx;

		public HttpClientCloseSubscriber(ChannelHandlerContext ctx) {
			this.ctx = ctx;
		}

		@Override
		public void onComplete() {
		}

		@Override
		public void onError(Throwable t) {
			if (t == null) {
				throw Exceptions.argumentIsNullException();
			}
			if (t instanceof IOException && t.getMessage() != null && t.getMessage()
			                                                           .contains(
					                                                           "Broken pipe")) {
				if (log.isDebugEnabled()) {
					log.debug("Connection closed remotely", t);
				}
				return;
			}
			if (ctx.channel()
			       .isOpen()) {
				if (log.isDebugEnabled()) {
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
		public void onSubscribe(final Subscription s) {
			ctx.read();
			Operators.validate(null, s);
			s.request(Long.MAX_VALUE);
		}
	}

	static final class ResponseState {

		final HttpResponse response;
		final HttpHeaders  headers;
		final Cookies      cookies;

		ResponseState(HttpResponse response, HttpHeaders headers) {
			this.response = response;
			this.headers = headers;
			this.cookies = Cookies.newClientResponseHolder(headers);
		}
	}

	static final int                    MAX_REDIRECTS      = 50;
	static final String[]               EMPTY_REDIRECTIONS = new String[0];
	static final Logger                 log                =
			Loggers.getLogger(HttpClientOperations.class);
	static final AttributeKey<String[]> REDIRECT_ATTR_KEY  = AttributeKey.newInstance("httpRedirects");
}
