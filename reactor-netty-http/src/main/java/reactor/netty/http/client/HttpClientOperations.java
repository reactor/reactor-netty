/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
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
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.publisher.Sinks;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Cookies;
import reactor.netty.http.HttpOperations;
import reactor.netty.http.logging.HttpMessageArgProviderFactory;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 * @author Simon Baslé
 */
class HttpClientOperations extends HttpOperations<NettyInbound, NettyOutbound>
		implements HttpClientResponse, HttpClientRequest {

	final boolean                isSecure;
	final HttpRequest            nettyRequest;
	final HttpHeaders            requestHeaders;
	final ClientCookieEncoder    cookieEncoder;
	final ClientCookieDecoder    cookieDecoder;
	final Sinks.One<HttpHeaders> trailerHeaders;

	Supplier<String>[]          redirectedFrom = EMPTY_REDIRECTIONS;
	UriEndpoint                 uriEndpoint;
	Duration                    responseTimeout;

	volatile ResponseState responseState;

	boolean started;
	boolean retrying;
	boolean is100Continue;
	RedirectClientException redirecting;

	BiPredicate<HttpClientRequest, HttpClientResponse> followRedirectPredicate;
	Consumer<HttpClientRequest> redirectRequestConsumer;
	HttpHeaders previousRequestHeaders;
	BiConsumer<HttpHeaders, HttpClientRequest> redirectRequestBiConsumer;

	final static String INBOUND_CANCEL_LOG = "Http client inbound receiver cancelled, closing channel.";

	HttpClientOperations(HttpClientOperations replaced) {
		super(replaced);
		this.started = replaced.started;
		this.retrying = replaced.retrying;
		this.redirecting = replaced.redirecting;
		this.redirectedFrom = replaced.redirectedFrom;
		this.redirectRequestConsumer = replaced.redirectRequestConsumer;
		this.previousRequestHeaders = replaced.previousRequestHeaders;
		this.redirectRequestBiConsumer = replaced.redirectRequestBiConsumer;
		this.isSecure = replaced.isSecure;
		this.nettyRequest = replaced.nettyRequest;
		this.responseState = replaced.responseState;
		this.followRedirectPredicate = replaced.followRedirectPredicate;
		this.requestHeaders = replaced.requestHeaders;
		this.cookieEncoder = replaced.cookieEncoder;
		this.cookieDecoder = replaced.cookieDecoder;
		this.uriEndpoint = replaced.uriEndpoint;
		this.responseTimeout = replaced.responseTimeout;
		this.is100Continue = replaced.is100Continue;
		this.trailerHeaders = replaced.trailerHeaders;
	}

	HttpClientOperations(Connection c, ConnectionObserver listener, ClientCookieEncoder encoder,
			ClientCookieDecoder decoder, HttpMessageLogFactory httpMessageLogFactory) {
		super(c, listener, httpMessageLogFactory);
		this.isSecure = c.channel()
		                 .pipeline()
		                 .get(NettyPipeline.SslHandler) != null;
		this.nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
		this.requestHeaders = nettyRequest.headers();
		this.cookieDecoder = decoder;
		this.cookieEncoder = encoder;
		this.trailerHeaders = Sinks.unsafe().one();
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
	@SuppressWarnings("deprecation")
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
		return (InetSocketAddress) channel().remoteAddress();
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

	void followRedirectPredicate(BiPredicate<HttpClientRequest, HttpClientResponse> predicate) {
		this.followRedirectPredicate = predicate;
	}

	void redirectRequestConsumer(@Nullable Consumer<HttpClientRequest> redirectRequestConsumer) {
		this.redirectRequestConsumer = redirectRequestConsumer;
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	protected void onInboundCancel() {
		if (isInboundDisposed()) {
			return;
		}
		//"FutureReturnValueIgnored" this is deliberate
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), INBOUND_CANCEL_LOG));
		}
		channel().close();
	}

	@Override
	protected void onInboundClose() {
		if (isInboundCancelled() || isInboundDisposed()) {
			listener().onStateChange(this, ConnectionObserver.State.DISCONNECTING);
			return;
		}
		listener().onStateChange(this, HttpClientState.RESPONSE_INCOMPLETE);
		if (responseState == null) {
			if (markSentHeaderAndBody()) {
				listener().onUncaughtException(this, AbortedException.beforeSend());
			}
			else if (markSentBody()) {
				listener().onUncaughtException(this, new PrematureCloseException("Connection has been closed BEFORE response, while sending request body"));
			}
			else {
				listener().onUncaughtException(this, new PrematureCloseException("Connection prematurely closed BEFORE response"));
			}
			return;
		}
		super.onInboundError(new PrematureCloseException("Connection prematurely closed DURING response"));
	}

	@Override
	protected void afterInboundComplete() {
		if (redirecting != null) {
			listener().onUncaughtException(this, redirecting);
		}
		else {
			listener().onStateChange(this, HttpClientState.RESPONSE_COMPLETED);
		}
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
		return followRedirectPredicate != null && redirectedFrom.length <= MAX_REDIRECTS;
	}

	@Override
	public HttpClientRequest responseTimeout(Duration maxReadOperationInterval) {
		if (!hasSentHeaders()) {
			this.responseTimeout = maxReadOperationInterval;
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
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
	public ContextView currentContextView() {
		return currentContext();
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
		if (!channel().isActive()) {
			return then(Mono.error(AbortedException.beforeSend()));
		}
		if (source instanceof Mono) {
			return super.send(source);
		}
		if (Objects.equals(method(), HttpMethod.GET) || Objects.equals(method(), HttpMethod.HEAD)) {

			ByteBufAllocator alloc = channel().alloc();
			return new PostHeadersNettyOutbound(Flux.from(source)
			                .collectList()
			                .doOnDiscard(ByteBuf.class, ByteBuf::release)
			                .flatMap(list -> {
				                if (markSentHeaderAndBody(list.toArray())) {
					                if (list.isEmpty()) {
						                return FutureMono.from(channel().writeAndFlush(newFullBodyMessage(Unpooled.EMPTY_BUFFER)));
					                }

					                ByteBuf output;
					                int i = list.size();
					                if (i == 1) {
						                output = list.get(0);
					                }
					                else {
						                CompositeByteBuf agg = alloc.compositeBuffer(list.size());

						                for (ByteBuf component : list) {
							                agg.addComponent(true, component);
						                }

						                output = agg;
					                }

					                if (output.readableBytes() > 0) {
						                return FutureMono.from(channel().writeAndFlush(newFullBodyMessage(output)));
					                }
					                output.release();
					                return FutureMono.from(channel().writeAndFlush(newFullBodyMessage(Unpooled.EMPTY_BUFFER)));
				                }
				                for (ByteBuf bb : list) {
				                	if (log.isDebugEnabled()) {
						                log.debug(format(channel(), "Ignoring accumulated bytebuf on http GET {}"), bb);
					                }
				                	bb.release();
				                }
				                return Mono.empty();
			                }), this, null);
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
			return responseState.response.status();
		}
		throw new IllegalStateException("Trying to access status() while missing response");
	}

	@Override
	public Mono<HttpHeaders> trailerHeaders() {
		return trailerHeaders.asMono();
	}

	@Override
	public final String uri() {
		return this.nettyRequest.uri();
	}

	@Override
	public final String fullPath() {
		return uriEndpoint == null ? null : uriEndpoint.getPath();
	}

	@Override
	public String resourceUrl() {
		return uriEndpoint == null ? null : uriEndpoint.toExternalForm();
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
	protected void afterMarkSentHeaders() {
		//Noop
	}

	@Override
	protected void beforeMarkSentHeaders() {
		if (redirectedFrom.length > 0) {
			if (redirectRequestConsumer != null) {
				redirectRequestConsumer.accept(this);
			}
			if (redirectRequestBiConsumer != null && previousRequestHeaders != null) {
				redirectRequestBiConsumer.accept(previousRequestHeaders, this);
				previousRequestHeaders = null;
			}
		}
	}

	@Override
	protected boolean isContentAlwaysEmpty() {
		return false;
	}

	@Override
	protected void onHeadersSent() {
		channel().read();
		if (channel().parent() != null) {
			channel().parent().read();
		}
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	protected void onOutboundComplete() {
		if (isWebsocket() || isInboundCancelled()) {
			return;
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "No sendHeaders() called before complete, sending " +
						"zero-length header"));
			}
			//"FutureReturnValueIgnored" this is deliberate
			channel().writeAndFlush(newFullBodyMessage(Unpooled.EMPTY_BUFFER));
		}
		else if (markSentBody()) {
			//"FutureReturnValueIgnored" this is deliberate
			channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		}
		listener().onStateChange(this, HttpClientState.REQUEST_SENT);
		if (responseTimeout != null) {
			addHandlerFirst(NettyPipeline.ResponseTimeoutHandler,
					new ReadTimeoutHandler(responseTimeout.toMillis(), TimeUnit.MILLISECONDS));
		}
		channel().read();
		if (channel().parent() != null) {
			channel().parent().read();
		}
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (isPersistent() && responseState == null) {
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
			if (HttpResponseStatus.CONTINUE.equals(response.status())) {
				is100Continue = true;
				ReferenceCountUtil.release(msg);
				return;
			}
			if (started) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel(), "HttpClientOperations cannot proceed more than one response {}"),
							httpMessageLogFactory().debug(HttpMessageArgProviderFactory.create(response)));
				}
				ReferenceCountUtil.release(msg);
				return;
			}
			is100Continue = false;
			started = true;
			setNettyResponse(response);

			if (!isKeepAlive()) {
				markPersistent(false);
			}
			if (isInboundCancelled()) {
				ReferenceCountUtil.release(msg);
				return;
			}

			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Received response (auto-read:{}) : {}"),
						channel().config().isAutoRead(),
						httpMessageLogFactory().debug(HttpMessageArgProviderFactory.create(response)));
			}

			if (notRedirected(response)) {
				try {
					listener().onStateChange(this, HttpClientState.RESPONSE_RECEIVED);
				}
				catch (Exception e) {
					onInboundError(e);
					ReferenceCountUtil.release(msg);
					return;
				}
			}
			else {
				// when redirecting no need of manual reading
				channel().config().setAutoRead(true);
			}

			if (msg instanceof FullHttpResponse) {
				FullHttpResponse request = (FullHttpResponse) msg;
				if (request.content().readableBytes() > 0) {
					super.onInboundNext(ctx, msg);
				}
				else {
					request.release();
				}
				terminate();
			}
			return;
		}

		if (msg instanceof LastHttpContent) {
			if (is100Continue) {
				ReferenceCountUtil.release(msg);
				channel().read();
				return;
			}
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
				if (redirecting != null) {
					ReferenceCountUtil.release(msg);
				}
				else {
					super.onInboundNext(ctx, msg);
				}
			}

			if (redirecting == null) {
				// EmitResult is ignored as it is guaranteed that there will be only one emission of LastHttpContent
				// Whether there are subscribers or the subscriber cancels is not of interest
				// Evaluated EmitResult: FAIL_TERMINATED, FAIL_OVERFLOW, FAIL_CANCELLED, FAIL_NON_SERIALIZED
				// FAIL_ZERO_SUBSCRIBER
				trailerHeaders.tryEmitValue(((LastHttpContent) msg).trailingHeaders());
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

		if (redirecting != null) {
			ReferenceCountUtil.release(msg);
			// when redirecting auto-read is set to true, no need of manual reading
			return;
		}
		super.onInboundNext(ctx, msg);
	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyRequest;
	}

	final boolean notRedirected(HttpResponse response) {
		if (isFollowRedirect() && followRedirectPredicate.test(this, this)) {
			try {
				redirecting = new RedirectClientException(response.headers(), response.status());
			}
			catch (RuntimeException e) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel(), "The request cannot be redirected"), e);
				}
				return true;
			}
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Received redirect location: {}"),
						httpMessageLogFactory().debug(HttpMessageArgProviderFactory.create(response)));
			}
			return false;
		}
		return true;
	}

	@Override
	protected HttpMessage newFullBodyMessage(ByteBuf body) {
		HttpRequest request = new DefaultFullHttpRequest(version(), method(), uri(), body);

		requestHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
		requestHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);

		request.headers()
		       .set(requestHeaders);
		return request;
	}

	@Override
	protected Throwable wrapInboundError(Throwable err) {
		if (err instanceof ClosedChannelException) {
			return new PrematureCloseException(err);
		}
		return super.wrapInboundError(err);
	}

	final HttpRequest getNettyRequest() {
		return nettyRequest;
	}

	final Mono<Void> send() {
		if (!channel().isActive()) {
			return Mono.error(AbortedException.beforeSend());
		}
		return FutureMono.deferFuture(() -> markSentHeaderAndBody() ?
				channel().writeAndFlush(newFullBodyMessage(Unpooled.EMPTY_BUFFER)) :
				channel().newSucceededFuture());
	}

	final void setNettyResponse(HttpResponse nettyResponse) {
		ResponseState state = responseState;
		if (state == null) {
			this.responseState =
					new ResponseState(nettyResponse, nettyResponse.headers(), cookieDecoder);
		}
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	final void withWebsocketSupport(WebsocketClientSpec websocketClientSpec, boolean compress) {
		URI url = websocketUri();
		//prevent further header to be sent for handshaking
		if (markSentHeaders()) {
			// Returned value is deliberately ignored
			addHandlerFirst(NettyPipeline.HttpAggregator, new HttpObjectAggregator(8192));
			removeHandler(NettyPipeline.HttpMetricsHandler);

			if (websocketClientSpec.compress()) {
				requestHeaders().remove(HttpHeaderNames.ACCEPT_ENCODING);
				// Returned value is deliberately ignored
				removeHandler(NettyPipeline.HttpDecompressor);
				// Returned value is deliberately ignored
				addHandlerFirst(NettyPipeline.WsCompressionHandler, WebSocketClientCompressionHandler.INSTANCE);
			}

			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Attempting to perform websocket handshake with {}"), url);
			}
			WebsocketClientOperations ops = new WebsocketClientOperations(url, websocketClientSpec, this);

			if (!rebind(ops)) {
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
			if (!parent.markSentHeaders()) {
				Operators.error(s,
						new IllegalStateException("headers have already been sent"));
				return;
			}
			Subscription subscription = Operators.emptySubscription();
			s.onSubscribe(subscription);
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

		@SuppressWarnings("FutureReturnValueIgnored")
		void _subscribe(CoreSubscriber<? super Void> s) {
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
					parent.requestHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
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

				if (encoder.isChunked()) {
					Flux<Long> tail = encoder.progressSink.asFlux().onBackpressureLatest();

					if (encoder.cleanOnTerminate) {
						tail = tail.doOnCancel(encoder)
						           .doAfterTerminate(encoder);
					}

					if (progressCallback != null) {
						progressCallback.accept(tail);
					}
					else {
						tail.subscribe();
					}
					//"FutureReturnValueIgnored" this is deliberate
					parent.channel()
					      .writeAndFlush(encoder);
				}
				else {
					Mono<Void> mono = FutureMono.from(f);

					if (encoder.cleanOnTerminate) {
						mono = mono.doOnCancel(encoder)
						           .doAfterTerminate(encoder);
					}

					if (progressCallback != null) {
						progressCallback.accept(mono.cast(Long.class)
						                            .switchIfEmpty(Mono.just(encoder.length()))
						                            .flux());
					}
					else {
						mono.subscribe();
					}
				}
				s.onComplete();


			}
			catch (Throwable e) {
				Exceptions.throwIfJvmFatal(e);
				df.cleanRequestHttpData(parent.nettyRequest);
				s.onError(Exceptions.unwrap(e));
			}
		}
	}

	static final int                    MAX_REDIRECTS      = 50;
	@SuppressWarnings({"unchecked", "rawtypes"})
	static final Supplier<String>[]     EMPTY_REDIRECTIONS = (Supplier<String>[]) new Supplier[0];
	static final Logger                 log                = Loggers.getLogger(HttpClientOperations.class);
}
