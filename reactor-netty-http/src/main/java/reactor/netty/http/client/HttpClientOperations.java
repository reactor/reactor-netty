/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.buffer.ByteBufHolder;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.buffer.api.CompositeBuffer;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.DefaultFullHttpRequest;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.EmptyLastHttpContent;
import io.netty5.handler.codec.http.FullHttpResponse;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaders;
import io.netty5.handler.codec.http.HttpMessage;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpObjectAggregator;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.HttpUtil;
import io.netty5.handler.codec.http.HttpVersion;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty5.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty5.handler.codec.http.cookie.Cookie;
import io.netty5.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler;
import io.netty5.handler.timeout.ReadTimeoutHandler;
import io.netty5.util.Resource;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Cookies;
import reactor.netty.http.HttpOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.toPrettyHexDump;

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
	String                      resourceUrl;
	String                      path;
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
		this.resourceUrl = replaced.resourceUrl;
		this.path = replaced.path;
		this.responseTimeout = replaced.responseTimeout;
		this.is100Continue = replaced.is100Continue;
		this.trailerHeaders = replaced.trailerHeaders;
	}

	HttpClientOperations(Connection c, ConnectionObserver listener, ClientCookieEncoder encoder, ClientCookieDecoder decoder) {
		super(c, listener);
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
	public NettyOutbound send(Publisher<? extends Buffer> source) {
		if (!channel().isActive()) {
			return then(Mono.error(AbortedException.beforeSend()));
		}
		if (source instanceof Mono) {
			return super.send(source);
		}
		if (Objects.equals(method(), HttpMethod.GET) || Objects.equals(method(), HttpMethod.HEAD)) {

			BufferAllocator alloc = channel().bufferAllocator();
			return new PostHeadersNettyOutbound(Flux.from(source)
			                .collectList()
			                .doOnDiscard(Buffer.class, Buffer::close)
			                .flatMap(list -> {
				                if (markSentHeaderAndBody(list.toArray())) {
					                if (list.isEmpty()) {
						                return Mono.fromCompletionStage(
							                    channel().writeAndFlush(newFullBodyMessage(channel().bufferAllocator().allocate(0))).asStage());
					                }

					                Buffer output;
					                int i = list.size();
					                if (i == 1) {
						                output = list.get(0);
					                }
					                else {
						                CompositeBuffer agg = alloc.compose();

						                for (Buffer component : list) {
							                agg.extendWith(component.send());
						                }

						                output = agg;
					                }

					                if (output.readableBytes() > 0) {
						                return Mono.fromCompletionStage(channel().writeAndFlush(newFullBodyMessage(output)).asStage());
					                }
					                output.close();
					                return Mono.fromCompletionStage(channel().writeAndFlush(newFullBodyMessage(channel().bufferAllocator().allocate(0))).asStage());
				                }
				                for (Buffer bb : list) {
				                	if (log.isDebugEnabled()) {
				                		log.debug(format(channel(), "Ignoring accumulated buffer on http GET {}"), toPrettyHexDump(bb));
					                }
				                	bb.close();
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
		return this.path;
	}

	@Override
	public String resourceUrl() {
		return resourceUrl;
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
			channel().writeAndFlush(newFullBodyMessage(channel().bufferAllocator().allocate(0)));
		}
		else if (markSentBody()) {
			//"FutureReturnValueIgnored" this is deliberate
			channel().writeAndFlush(new EmptyLastHttpContent(channel().bufferAllocator()));
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
		if (msg instanceof HttpResponse response) {
			if (response.decoderResult()
			            .isFailure()) {
				onInboundError(response.decoderResult()
				                       .cause());
				Resource.dispose(msg);
				return;
			}
			if (HttpResponseStatus.CONTINUE.equals(response.status())) {
				is100Continue = true;
				Resource.dispose(msg);
				return;
			}
			if (started) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel(), "HttpClientOperations cannot proceed more than one response {}"),
							response.headers()
							        .toString());
				}
				Resource.dispose(msg);
				return;
			}
			is100Continue = false;
			started = true;
			setNettyResponse(response);

			if (!isKeepAlive()) {
				markPersistent(false);
			}
			if (isInboundCancelled()) {
				Resource.dispose(msg);
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
				try {
					listener().onStateChange(this, HttpClientState.RESPONSE_RECEIVED);
				}
				catch (Exception e) {
					onInboundError(e);
					Resource.dispose(msg);
					return;
				}
			}
			else {
				// when redirecting no need of manual reading
				channel().config().setAutoRead(true);
			}

			if (msg instanceof FullHttpResponse request) {
				if (request.payload().readableBytes() > 0) {
					super.onInboundNext(ctx, msg);
				}
				else {
					request.close();
				}
				terminate();
			}
			return;
		}

		if (msg instanceof LastHttpContent) {
			if (is100Continue) {
				Resource.dispose(msg);
				channel().read();
				return;
			}
			if (!started) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel(), "HttpClientOperations received an incorrect end " +
							"delimiter (previously used connection?)"));
				}
				Resource.dispose(msg);
				return;
			}
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Received last HTTP packet"));
			}
			if (!(msg instanceof EmptyLastHttpContent)) {
				if (redirecting != null) {
					Resource.dispose(msg);
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
			Resource.dispose(msg);
			return;
		}

		if (redirecting != null) {
			Resource.dispose(msg);
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
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Received redirect location: {}"),
						response.headers()
						        .entries()
						        .toString());
			}
			redirecting = new RedirectClientException(response.headers(), response.status());
			return false;
		}
		return true;
	}

	@Override
	protected HttpMessage newFullBodyMessage(Buffer body) {
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
		if (markSentHeaderAndBody()) {
			HttpMessage request = newFullBodyMessage(channel().bufferAllocator().allocate(0));
			return Mono.fromCompletionStage(() -> channel().writeAndFlush(request).asStage());
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

	static final int                    MAX_REDIRECTS      = 50;
	@SuppressWarnings({"unchecked", "rawtypes"})
	static final Supplier<String>[]     EMPTY_REDIRECTIONS = (Supplier<String>[]) new Supplier[0];
	static final Logger                 log                = Loggers.getLogger(HttpClientOperations.class);
}
