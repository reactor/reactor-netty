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

package reactor.netty.http.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
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
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Cookies;
import reactor.netty.http.HttpOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 * @author Simon Baslé
 */
class HttpClientOperations extends HttpOperations<NettyInbound, NettyOutbound>
		implements HttpClientResponse, HttpClientRequest {

	final Supplier<String>[]    redirectedFrom;
	final boolean               isSecure;
	final HttpRequest           nettyRequest;
	final HttpHeaders           requestHeaders;
	final ClientCookieEncoder   cookieEncoder;
	final ClientCookieDecoder   cookieDecoder;

	volatile ResponseState responseState;

	boolean started;

	boolean redirectable;

	HttpClientOperations(HttpClientOperations replaced) {
		super(replaced);
		this.started = replaced.started;
		this.redirectedFrom = replaced.redirectedFrom;
		this.isSecure = replaced.isSecure;
		this.nettyRequest = replaced.nettyRequest;
		this.responseState = replaced.responseState;
		this.redirectable = replaced.redirectable;
		this.requestHeaders = replaced.requestHeaders;
		this.cookieEncoder = replaced.cookieEncoder;
		this.cookieDecoder = replaced.cookieDecoder;
	}

	HttpClientOperations(Connection c, ConnectionObserver listener, ClientCookieEncoder encoder, ClientCookieDecoder decoder) {
		super(c, listener);
		this.isSecure = c.channel()
		                 .pipeline()
		                 .get(NettyPipeline.SslHandler) != null;
		Supplier<String>[] redirects = c.channel()
		                                .attr(REDIRECT_ATTR_KEY)
		                                .get();
		this.redirectedFrom = redirects == null ? EMPTY_REDIRECTIONS : redirects;
		this.nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
		this.requestHeaders = nettyRequest.headers();
		this.cookieDecoder = decoder;
		this.cookieEncoder = encoder;
	}

	@Override
	public HttpClientRequest addCookie(Cookie cookie) {
		if (!hasSentHeaders()) {
			this.requestHeaders.add(HttpHeaderNames.COOKIE,
					cookieEncoder.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpClientOperations addHandlerLast(ChannelHandler handler) {
		super.addHandlerLast(handler);
		return this;
	}

	@Override
	public HttpClientOperations addHandlerLast(String name, ChannelHandler handler) {
		super.addHandlerLast(name, handler);
		return this;
	}

	@Override
	public HttpClientOperations addHandlerFirst(ChannelHandler handler) {
		super.addHandlerFirst(handler);
		return this;
	}

	@Override
	public HttpClientOperations addHandlerFirst(String name, ChannelHandler handler) {
		super.addHandlerFirst(name, handler);
		return this;
	}

	@Override
	public HttpClientOperations addHandler(ChannelHandler handler) {
		super.addHandler(handler);
		return this;
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public HttpClientOperations addHandler(String name, ChannelHandler handler) {
		// Returned value is deliberately ignored
		super.addHandler(name, handler);
		return this;
	}

	@Override
	public HttpClientOperations replaceHandler(String name, ChannelHandler handler) {
		super.replaceHandler(name, handler);
		return this;
	}

	@Override
	public HttpClientOperations removeHandler(String name) {
		super.removeHandler(name);
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
		return (InetSocketAddress)channel().remoteAddress();
	}

	public void chunkedTransfer(boolean chunked) {
		if (!hasSentHeaders() && HttpUtil.isTransferEncodingChunked(nettyRequest) != chunked) {
			requestHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			HttpUtil.setTransferEncodingChunked(nettyRequest, chunked);
		}
	}

	@Override
	public HttpClientOperations withConnection(Consumer<? super Connection> withConnection) {
		Objects.requireNonNull(withConnection, "withConnection");
		withConnection.accept(this);
		return this;
	}

	@Override
	public Map<CharSequence, Set<Cookie>> cookies() {
		ResponseState responseState = this.responseState;
		if (responseState != null && responseState.cookieHolder != null) {
			return responseState.cookieHolder.getCachedCookies();
		}
		return Collections.emptyMap();
	}

	public void followRedirect(boolean redirectable) {
		this.redirectable = redirectable;
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
		listener().onStateChange(this, HttpClientState.RESPONSE_INCOMPLETE);
		if (responseState == null) {
			if (markSentBody()) {
				listener().onUncaughtException(this, PrematureCloseException.BEFORE_RESPONSE_SENDING_REQUEST);
			}
			else {
				listener().onUncaughtException(this, PrematureCloseException.BEFORE_RESPONSE);
			}
			return;
		}
		super.onInboundError(PrematureCloseException.DURING_RESPONSE);
	}

	@Override
	public NettyOutbound sendObject(Publisher<?> dataStream) {
		if (!HttpUtil.isTransferEncodingChunked(nettyRequest) &&
				!HttpUtil.isContentLengthSet(nettyRequest) &&
				!method().equals(HttpMethod.HEAD) &&
				!hasSentHeaders()) {
			HttpUtil.setTransferEncodingChunked(nettyRequest, true);
		}
		return super.sendObject(dataStream);
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
		ResponseState rs = responseState;
		if (rs != null) {
			return HttpUtil.isKeepAlive(rs.response);
		}
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public boolean isWebsocket() {
		ChannelOperations<?, ?> ops = get(channel());
		return ops != null && ops.getClass().equals(WebsocketClientOperations.class);
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
	}

	@Override
	public final HttpClientOperations onDispose(Disposable onDispose) {
		super.onDispose(onDispose);
		return this;
	}

	@Override
	public String[] redirectedFrom() {
		Supplier<String>[] redirectedFrom = this.redirectedFrom;
		String[] dest = new String[redirectedFrom.length];
		for (int i = 0; i < redirectedFrom.length; i++) {
			dest[i] = redirectedFrom[i].get();
		}
		return dest;
	}

	@Override
	public HttpHeaders requestHeaders() {
		return nettyRequest.headers();
	}

	@Override
	public HttpHeaders responseHeaders() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return responseState.headers;
		}
		throw new IllegalStateException("Response headers cannot be accessed without " + "server response");
	}

	@Override
	public NettyOutbound send(Publisher<? extends ByteBuf> source) {
		if (Objects.equals(method(), HttpMethod.GET) || Objects.equals(method(), HttpMethod.HEAD)) {
			return new GetOrHeadAggregateOutbound(this, source, outboundHttpMessage());
		}
		return super.send(source);
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
				                          HttpClient.WS_SCHEME) + "://" + host + (url.startsWith("/") ? url : "/" + url));
			}
		}
		catch (URISyntaxException e) {
			throw new IllegalArgumentException(e);
		}
		return uri;
	}


	@Override
	public HttpResponseStatus status() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return HttpResponseStatus.valueOf(responseState.response.status()
			                                                        .code());
		}
		throw new IllegalStateException("Trying to access status() while missing response");
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
	protected void preSendHeadersAndStatus() {
		//Noop
	}

	@Override
	protected void onOutboundComplete() {
		if (isWebsocket() || isInboundCancelled()) {
			return;
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "No sendHeaders() called before complete, sending " +
						"zero-length header"));
			}
			channel().writeAndFlush(newFullEmptyBodyMessage());
		}
		else if (markSentBody()) {
			channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		}
		listener().onStateChange(this, HttpClientState.REQUEST_SENT);
		channel().read();
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if(isPersistent() && responseState == null){
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Outbound error happened"), err);
			}
			listener().onUncaughtException(this, err);
			if (markSentBody()) {
				markPersistent(false);
			}
			terminate();
			return;
		}
		super.onOutboundError(err);
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpResponse) {
			HttpResponse response = (HttpResponse) msg;
			if (response.decoderResult()
			            .isFailure()) {
				onInboundError(response.decoderResult()
				                       .cause());
				ReferenceCountUtil.release(msg);
				return;
			}
			if (started) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel(), "HttpClientOperations cannot proceed more than one response {}"),
							response.headers()
							        .toString());
				}
				ReferenceCountUtil.release(msg);
				return;
			}
			started = true;
			setNettyResponse(response);

			if (!isKeepAlive()) {
				markPersistent(false);
			}
			if(isInboundCancelled()){
				ReferenceCountUtil.release(msg);
				return;
			}


			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Received response (auto-read:{}) : {}"),
						channel().config()
						         .isAutoRead(),
						responseHeaders().entries()
						                 .toString());
			}

			if (notRedirected(response)) {
				listener().onStateChange(this, HttpClientState.RESPONSE_RECEIVED);
			}
			if (msg instanceof FullHttpResponse) {
				super.onInboundNext(ctx, msg);
				terminate();
			}
			return;
		}
		if (msg instanceof LastHttpContent) {
			if (!started) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel(), "HttpClientOperations received an incorrect end " +
							"delimiter (previously used connection?)"));
				}
				ReferenceCountUtil.release(msg);
				return;
			}
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Received last HTTP packet"));
			}
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
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
				log.debug(format(channel(), "HttpClientOperations received an incorrect chunk {} " +
								"(previously used connection?)"),
						msg);
			}
			ReferenceCountUtil.release(msg);
			return;
		}
		super.onInboundNext(ctx, msg);
	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyRequest;
	}

	final boolean notRedirected(HttpResponse response) {
		int code = response.status()
		                   .code();
		if ((code == 301 || code == 302) && isFollowRedirect()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Received redirect location: {}"),
						response.headers()
						        .entries()
						        .toString());
			}
			listener().onUncaughtException(this, new RedirectClientException(response.headers()));
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

	final Mono<Void> send() {
		if (markSentHeaderAndBody()) {
			HttpMessage request = newFullEmptyBodyMessage();
			return FutureMono.deferFuture(() -> channel().writeAndFlush(request));
		}
		else {
			return Mono.empty();
		}
	}

	final void setNettyResponse(HttpResponse nettyResponse) {
		ResponseState state = responseState;
		if (state == null) {
			this.responseState =
					new ResponseState(nettyResponse, nettyResponse.headers(), cookieDecoder);
		}
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	final void withWebsocketSupport(String protocols, int maxFramePayloadLength) {
		URI url = websocketUri();
		//prevent further header to be sent for handshaking
		if (markSentHeaders()) {
			// Returned value is deliberately ignored
			addHandlerFirst(NettyPipeline.HttpAggregator, new HttpObjectAggregator(8192));

			WebsocketClientOperations ops = new WebsocketClientOperations(url, protocols, maxFramePayloadLength, this);

			if(!rebind(ops)) {
				log.error(format(channel(), "Error while rebinding websocket in channel attribute: " +
						get(channel()) + " to " + ops));
			}
		}
	}

	static final class ResponseState {

		final HttpResponse response;
		final HttpHeaders  headers;
		final Cookies      cookieHolder;

		ResponseState(HttpResponse response, HttpHeaders headers, ClientCookieDecoder decoder) {
			this.response = response;
			this.headers = headers;
			this.cookieHolder = Cookies.newClientResponseHolder(headers, decoder);
		}
	}

	static final class SendForm extends Mono<Void> {

		static final HttpDataFactory DEFAULT_FACTORY = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

		final HttpClientOperations                                  parent;
		final BiConsumer<? super HttpClientRequest, HttpClientForm> formCallback;
		final Consumer<Flux<Long>>                                  progressCallback;

		SendForm(HttpClientOperations parent,
				BiConsumer<? super HttpClientRequest, HttpClientForm>  formCallback,
				@Nullable Consumer<Flux<Long>> progressCallback) {
			this.parent = parent;
			this.formCallback = formCallback;
			this.progressCallback = progressCallback;
		}

		@Override
		public void subscribe(CoreSubscriber<? super Void> s) {
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

		void _subscribe(CoreSubscriber<? super Void> s) {
			if (!parent.markSentHeaders()) {
				Operators.error(s,
						new IllegalStateException("headers have already been sent"));
				return;
			}

			HttpDataFactory df = DEFAULT_FACTORY;

			try {
				HttpClientFormEncoder encoder = new HttpClientFormEncoder(df,
						parent.nettyRequest,
						false,
						HttpConstants.DEFAULT_CHARSET,
						HttpPostRequestEncoder.EncoderMode.RFC1738);

				formCallback.accept(parent, encoder);

				encoder = encoder.applyChanges(parent.nettyRequest);
				df = encoder.newFactory;

				if (!encoder.isMultipart()) {
					parent.chunkedTransfer(false);
				}

				// Returned value is deliberately ignored
				parent.addHandlerFirst(NettyPipeline.ChunkedWriter, new ChunkedWriteHandler());

				boolean chunked = HttpUtil.isTransferEncodingChunked(parent.nettyRequest);

				HttpRequest r = encoder.finalizeRequest();

				if (!chunked) {
					HttpUtil.setTransferEncodingChunked(r, false);
					HttpUtil.setContentLength(r, encoder.length());
				}

				ChannelFuture f = parent.channel()
				                        .writeAndFlush(r);

				Flux<Long> tail = encoder.progressFlux.onBackpressureLatest();

				if (encoder.cleanOnTerminate) {
					tail = tail.doOnCancel(encoder)
					           .doAfterTerminate(encoder);
				}

				if (encoder.isChunked()) {
					if (progressCallback != null) {
						progressCallback.accept(tail);
					}
					parent.channel()
					      .writeAndFlush(encoder);
				}
				else {
					if (progressCallback != null) {
						progressCallback.accept(FutureMono.from(f)
						                                  .cast(Long.class)
						                                  .switchIfEmpty(Mono.just(encoder.length()))
						                                  .flux());
					}
				}
				Operators.complete(s);


			}
			catch (Throwable e) {
				Exceptions.throwIfFatal(e);
				df.cleanRequestHttpData(parent.nettyRequest);
				Operators.error(s, Exceptions.unwrap(e));
			}
		}
	}

	static final int                    MAX_REDIRECTS      = 50;
	@SuppressWarnings("unchecked")
	static final Supplier<String>[]     EMPTY_REDIRECTIONS = (Supplier<String>[])new Supplier[0];
	static final Logger                 log                =
			Loggers.getLogger(HttpClientOperations.class);
	static final AttributeKey<Supplier<String>[]> REDIRECT_ATTR_KEY  =
			AttributeKey.newInstance("httpRedirects");

	static final class PrematureCloseException extends IOException {

		static final PrematureCloseException BEFORE_RESPONSE_SENDING_REQUEST =
				new PrematureCloseException("Connection has been closed BEFORE response, while sending request body");

		static final PrematureCloseException BEFORE_RESPONSE =
				new PrematureCloseException("Connection prematurely closed BEFORE response");

		static final PrematureCloseException DURING_RESPONSE =
				new PrematureCloseException("Connection prematurely closed DURING response");

		PrematureCloseException(String message) {
			super(message);
		}

		@Override
		public synchronized Throwable fillInStackTrace() {
			return this;
		}
	}

	static final class GetOrHeadAggregateOutbound implements NettyOutbound {

		final HttpOperations<?, ?>         parent;
		final HttpMessage                  request;
		final Publisher<? extends ByteBuf> source;

		 GetOrHeadAggregateOutbound(HttpOperations<?, ?> parent,
				Publisher<? extends ByteBuf> source,
				 HttpMessage request) {
			this.parent = parent;
			this.source = source;
			this.request = request;
		}

		@Override
		public ByteBufAllocator alloc() {
			return parent.alloc();
		}

		@Override
		public NettyOutbound sendObject(Publisher<?> dataStream) {
			return parent.sendObject(dataStream);
		}

		@Override
		public NettyOutbound sendObject(Object message) {
			return parent.sendObject(message);
		}

		@Override
		public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
				BiFunction<? super Connection, ? super S, ?> mappedInput, Consumer<? super S> sourceCleanup) {
			return parent.sendUsing(sourceInput, mappedInput, sourceCleanup);
		}

		@Override
		public Mono<Void> then() {
			ByteBufAllocator alloc = parent.channel().alloc();
			return Flux.from(source)
			           .collect(alloc::heapBuffer, ByteBuf::writeBytes)
			           .flatMap(agg -> {
				           if (!HttpUtil.isTransferEncodingChunked(request) && !HttpUtil.isContentLengthSet(request)) {
					           request.headers()
					                  .setInt(HttpHeaderNames.CONTENT_LENGTH, agg.readableBytes());
				           }
				           return parent.then().thenEmpty(FutureMono.disposableWriteAndFlush(parent.channel(), Mono.just(agg)));
			           });
		}

		@Override
		public NettyOutbound withConnection(Consumer<? super Connection> withConnection) {
			return parent.withConnection(withConnection);
		}
	}
}
