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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
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
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.http.Cookies;
import reactor.ipc.netty.http.HttpOperations;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
class HttpClientOperations extends HttpOperations<HttpClientResponse, HttpClientRequest>
		implements HttpClientResponse, HttpClientRequest {

	static HttpOperations bindHttp(Channel channel,
			BiFunction<? super HttpClientResponse, ? super HttpClientRequest, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		return new HttpClientOperations(channel, handler, context);
	}

	final String[]    redirectedFrom;
	final boolean     isSecure;
	final HttpRequest nettyRequest;
	final HttpHeaders requestHeaders;

	volatile ResponseState responseState;
	int inboundPrefetch;

	boolean clientError = true;
	boolean serverError = true;
	boolean redirectable;

	HttpClientOperations(Channel channel, HttpClientOperations replaced) {
		super(channel, replaced);
		this.redirectedFrom = replaced.redirectedFrom;
		this.isSecure = replaced.isSecure;
		this.nettyRequest = replaced.nettyRequest;
		this.responseState = replaced.responseState;
		this.redirectable = replaced.redirectable;
		this.inboundPrefetch = replaced.inboundPrefetch;
		this.requestHeaders = replaced.requestHeaders;
	}

	HttpClientOperations(Channel channel,
			BiFunction<? super HttpClientResponse, ? super HttpClientRequest, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		super(channel, handler, context);
		this.isSecure = channel.pipeline()
		                       .get(NettyPipeline.SslHandler) != null;
		String[] redirects = channel.attr(REDIRECT_ATTR_KEY)
		                            .get();
		this.redirectedFrom = redirects == null ? EMPTY_REDIRECTIONS : redirects;
		this.nettyRequest =
				new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
		this.requestHeaders = nettyRequest.headers();
		this.inboundPrefetch = 16;
		chunkedTransfer(true);
	}

	@Override
	public HttpClientRequest addCookie(Cookie cookie) {
		if (!hasSentHeaders()) {
			this.requestHeaders.add(HttpHeaderNames.COOKIE,
					ClientCookieEncoder.STRICT.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public final HttpClientOperations addHandler(ChannelHandler handler) {
		super.addHandler(handler);
		return this;
	}

	@Override
	public final HttpClientOperations addHandler(String name, ChannelHandler handler) {
		super.addHandler(name, handler);
		return this;
	}

	@Override
	public HttpClientOperations addDecoder(ChannelHandler handler) {
		super.addDecoder(handler);
		return this;
	}

	@Override
	public HttpClientOperations addDecoder(String name, ChannelHandler handler) {
		super.addDecoder(name, handler);
		return this;
	}

	@Override
	public HttpClientRequest addHeader(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.requestHeaders.add(name, value);
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
	public HttpClientRequest chunkedTransfer(boolean chunked) {
		if (!hasSentHeaders()) {
			HttpUtil.setTransferEncodingChunked(nettyRequest, chunked);
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
			return responseState.cookieHolder.getCachedCookies();
		}
		return null;
	}

	@Override
	public final HttpClientRequest disableChunkedTransfer() {
		HttpUtil.setTransferEncodingChunked(nettyRequest, false);
		return this;
	}

	@Override
	public HttpClientRequest followRedirect() {
		redirectable = true;
		return this;
	}

	@Override
	public HttpClientRequest failOnClientError(boolean shouldFail) {
		clientError = shouldFail;
		return this;
	}

	@Override
	public HttpClientRequest failOnServerError(boolean shouldFail) {
		serverError = shouldFail;
		return this;
	}

	@Override
	public HttpClientRequest header(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.requestHeaders.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpClientRequest headers(HttpHeaders headers) {
		if (!hasSentHeaders()) {
			String host = requestHeaders.get(HttpHeaderNames.HOST);
			this.requestHeaders.set(headers);
			this.requestHeaders.set(HttpHeaderNames.HOST, host);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
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
	public boolean isWebsocket() {
		return attr(OPERATIONS_KEY).get()
		                           .getClass()
		                           .equals(HttpClientWSOperations.class);
	}

	@Override
	public HttpClientRequest keepAlive(boolean keepAlive) {
		HttpUtil.setKeepAlive(nettyRequest, keepAlive);
		return this;
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
	}

	@Override
	public final HttpClientOperations onClose(Runnable onClose) {
		super.onClose(onClose);
		return this;
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

	public HttpHeaders responseHeaders() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return responseState.headers;
		}
		else {
			return null;
		}
	}



	@Override
	public Mono<Void> send() {
		if (markHeadersAsSent()) {
			HttpMessage request = newFullEmptyBodyMessage();
			return FutureMono.deferFuture(() -> channel().writeAndFlush(request));
		}
		else {
			return Mono.empty();
		}
	}

	@Override
	public NettyOutbound send(Publisher<? extends ByteBuf> source) {
		if (method() == HttpMethod.GET || method() == HttpMethod.HEAD) {
			ByteBufAllocator alloc = channel().alloc();
			Flux.from(source)
			    .doOnNext(ByteBuf::retain)
			    .collect(alloc::buffer, ByteBuf::writeBytes)
			    .then(agg -> {
				    if (!hasSentHeaders() && !HttpUtil.isTransferEncodingChunked(
						    outboundHttpMessage()) && !HttpUtil.isContentLengthSet(
						    outboundHttpMessage())) {
					    outboundHttpMessage().headers()
					                         .setInt(HttpHeaderNames.CONTENT_LENGTH,
							                         agg.readableBytes());
				    }
				    return sendHeaders().send(Mono.just(agg))
				                        .then();
			    });
		}
		return super.send(source);
	}

	@Override
	public Flux<Long> sendForm(Consumer<Form> formCallback) {
		return new FluxSendForm(this,
				new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE),
				formCallback);
	}

	@Override
	public WebsocketOutbound sendWebsocket() {

		Mono<Void> m = withWebsocketSupport(websocketUri(), null, noopHandler());

		return new WebsocketOutbound() {
			@Override
			public NettyContext context() {
				return HttpClientOperations.this;
			}

			@Override
			public Mono<Void> then() {
				return m;
			}
		};
	}

	@Override
	public Mono<Void> receiveWebsocket(String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		return withWebsocketSupport(websocketUri(), protocols, websocketHandler);
	}

	final URI websocketUri() {
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

		}
		catch (URISyntaxException e) {
			throw Exceptions.bubble(e);
		}
		return uri;
	}

	@Override
	public WebsocketInbound receiveWebsocket() {
		return null;
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
	protected void onHandlerStart() {
		applyHandler();
	}

	@Override
	protected void onOutboundComplete() {
		if (isWebsocket()) {
			return;
		}
		if (markHeadersAsSent()) {
			channel().writeAndFlush(newFullEmptyBodyMessage());
		}
		else if (HttpUtil.isTransferEncodingChunked(nettyRequest)) {
			channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		}
		channel().read();
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpResponse) {
			HttpResponse response = (HttpResponse) msg;
			setNettyResponse(response);
			if (response.decoderResult()
			            .isFailure()) {
				onOutboundError(response.decoderResult()
				                        .cause());
				return;
			}

			if (log.isDebugEnabled()) {
				log.debug("Received response (auto-read:{}) : {}",
						channel().config()
						         .isAutoRead(),
						responseHeaders().entries()
						                 .toString());
			}

			if (checkResponseCode(response)) {
				prefetchMore(ctx);
				parentContext().fireContextActive(this);
			}
			if (msg instanceof FullHttpResponse) {
				super.onInboundNext(ctx, msg);
				onHandlerTerminate();
			}
			return;
		}
		if (msg instanceof LastHttpContent) {
			if (log.isDebugEnabled()) {
				log.debug("Received last HTTP packet");
			}
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
			}
			onHandlerTerminate();
			return;
		}

		super.onInboundNext(ctx, msg);
		if (downstream() == null) {
			ctx.read();
		}
		else {
			prefetchMore(ctx);
		}

	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyRequest;
	}

	final boolean checkResponseCode(HttpResponse response) {
		int code = response.status()
		                   .code();
		if (code >= 500 && serverError) {
			if (log.isDebugEnabled()) {
				log.debug("Received Server Error, stop reading: {}", response.toString());
			}
			Exception ex = new HttpClientException(uri(), response);
			parentContext().fireContextError(ex);
			onHandlerTerminate();
			return false;
		}

		if (code >= 400 && clientError) {
			if (log.isDebugEnabled()) {
				log.debug("Received Request Error, stop reading: {}",
						response.toString());
			}
			Exception ex = new HttpClientException(uri(), response);
			parentContext().fireContextError(ex);
			onHandlerTerminate();
			return false;
		}
		if (code == 301 || code == 302 && isFollowRedirect()) {
			if (log.isDebugEnabled()) {
				log.debug("Received Redirect location: {}",
						response.headers()
						        .entries()
						        .toString());
			}
			Exception ex = new RedirectClientException(uri(), response);
			parentContext().fireContextError(ex);
			onHandlerTerminate();
			return false;
		}
		return true;
	}

	@Override
	protected HttpMessage newFullEmptyBodyMessage() {
		HttpRequest request = new DefaultFullHttpRequest(version(), method(), uri());

		request.headers()
		       .set(requestHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING)
		                          .setInt(HttpHeaderNames.CONTENT_LENGTH, 0));
		return request;
	}

	final HttpRequest getNettyRequest() {
		return nettyRequest;
	}

	final void prefetchMore(ChannelHandlerContext ctx) {
		int inboundPrefetch = this.inboundPrefetch - 1;
		if (inboundPrefetch >= 0) {
			this.inboundPrefetch = inboundPrefetch;
			ctx.read();
		}
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
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {

		//prevent further header to be sent for handshaking
		if (markHeadersAsSent()) {
			addHandler(NettyPipeline.HttpAggregator, new HttpObjectAggregator(8192));

			HttpClientWSOperations ops = new HttpClientWSOperations(url, protocols, this);

			if (replace(ops)) {
				Mono<Void> handshake = FutureMono.from(ops.handshakerResult)
				                                 .then(() -> Mono.from(websocketHandler.apply(
						                                 ops,
						                                 ops)));
				if (websocketHandler != noopHandler()) {
					handshake = handshake.doAfterTerminate(ops);
				}
				return handshake;
			}
		}
		else if (isWebsocket()) {
			HttpClientWSOperations ops =
					(HttpClientWSOperations) attr(OPERATIONS_KEY).get();
			Mono<Void> handshake = FutureMono.from(ops.handshakerResult);

			if (websocketHandler != noopHandler()) {
				handshake =
						handshake.then(() -> Mono.from(websocketHandler.apply(ops, ops)))
						         .doAfterTerminate(ops);
			}

			return handshake;
		}
		else {
			log.error("Cannot enable websocket if headers have already been sent");
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	static final class ResponseState {

		final HttpResponse response;
		final HttpHeaders  headers;
		final Cookies      cookieHolder;

		ResponseState(HttpResponse response, HttpHeaders headers) {
			this.response = response;
			this.headers = headers;
			this.cookieHolder = Cookies.newClientResponseHolder(headers);
		}
	}

	static final class FluxSendForm extends Flux<Long> {

		final HttpClientOperations parent;
		final Consumer<Form>       formCallback;
		final HttpDataFactory      df;

		FluxSendForm(HttpClientOperations parent,
				HttpDataFactory df,
				Consumer<Form> formCallback) {
			this.parent = parent;
			this.df = df;
			this.formCallback = formCallback;
		}

		@Override
		public void subscribe(Subscriber<? super Long> s) {
			if (s == null) {
				throw Exceptions.argumentIsNullException();
			}

			if (parent.channel()
			          .eventLoop()
			          .inEventLoop()) {
				_subscribe(s);
			}
			else {
				parent.channel()
				      .eventLoop()
				      .execute(() -> _subscribe(s));
			}
		}

		void _subscribe(Subscriber<? super Long> s) {
			if (!parent.markHeadersAsSent()) {
				Operators.error(s,
						new IllegalStateException("headers have already " + "been sent"));
				return;
			}

			try {
				HttpClientFormEncoder encoder = new HttpClientFormEncoder(df,
						parent.nettyRequest,
						true,
						HttpConstants.DEFAULT_CHARSET,
						HttpPostRequestEncoder.EncoderMode.RFC1738);

				formCallback.accept(encoder);

				encoder = encoder.applyChanges(parent.nettyRequest);

				if (!encoder.isMultipart) {
					parent.disableChunkedTransfer();
				}

				parent.addHandler(NettyPipeline.ChunkedWriter, new ChunkedWriteHandler());

				boolean chunked = HttpUtil.isTransferEncodingChunked(parent.nettyRequest);

				HttpRequest r = encoder.finalizeRequest();

				if (!chunked) {
					HttpUtil.setTransferEncodingChunked(r, false);
					HttpUtil.setContentLength(r, encoder.length());
				}

				parent.channel()
				      .writeAndFlush(r);

				Flux<Long> tail = encoder.progressFlux.onBackpressureLatest();

				if (encoder.cleanOnTerminate) {
					tail = tail.doOnCancel(encoder)
					           .doAfterTerminate(encoder);
				}

				tail.subscribe(s);

				if (encoder.isChunked()) {
					parent.channel()
					      .write(encoder);
				}

				parent.channel()
				      .flush();


			}
			catch (Throwable e) {
				Exceptions.throwIfFatal(e);
				df.cleanRequestHttpData(parent.nettyRequest);
				Operators.error(s, Exceptions.unwrap(e));
			}
		}
	}

	static final int                    MAX_REDIRECTS      = 50;
	static final String[]               EMPTY_REDIRECTIONS = new String[0];
	static final Logger                 log                =
			Loggers.getLogger(HttpClientOperations.class);
	static final AttributeKey<String[]> REDIRECT_ATTR_KEY  =
			AttributeKey.newInstance("httpRedirects");
}
