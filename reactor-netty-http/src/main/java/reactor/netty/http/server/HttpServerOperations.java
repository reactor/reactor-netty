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
package reactor.netty.http.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DefaultHeaders;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.HttpOperations;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpUtil.isTransferEncodingChunked;
import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.server.HttpServerFormDecoderProvider.DEFAULT_FORM_DECODER_SPEC;
import static reactor.netty.http.server.HttpServerState.REQUEST_DECODING_FAILED;

/**
 * Conversion between Netty types  and Reactor types ({@link HttpOperations}.
 *
 * @author Stephane Maldini1
 */
class HttpServerOperations extends HttpOperations<HttpServerRequest, HttpServerResponse>
		implements HttpServerRequest, HttpServerResponse {

	final BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate;
	final ConnectionInfo connectionInfo;
	final ServerCookieDecoder cookieDecoder;
	final ServerCookieEncoder cookieEncoder;
	final ServerCookies cookieHolder;
	final HttpServerFormDecoderProvider formDecoderProvider;
	final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle;
	final HttpRequest nettyRequest;
	final HttpResponse nettyResponse;
	final String path;
	final HttpHeaders responseHeaders;
	final String scheme;

	Function<? super String, Map<String, String>> paramsResolver;
	Consumer<? super HttpHeaders> trailerHeadersConsumer;

	HttpServerOperations(HttpServerOperations replaced) {
		super(replaced);
		this.compressionPredicate = replaced.compressionPredicate;
		this.connectionInfo = replaced.connectionInfo;
		this.cookieDecoder = replaced.cookieDecoder;
		this.cookieEncoder = replaced.cookieEncoder;
		this.cookieHolder = replaced.cookieHolder;
		this.formDecoderProvider = replaced.formDecoderProvider;
		this.mapHandle = replaced.mapHandle;
		this.nettyRequest = replaced.nettyRequest;
		this.nettyResponse = replaced.nettyResponse;
		this.paramsResolver = replaced.paramsResolver;
		this.path = replaced.path;
		this.responseHeaders = replaced.responseHeaders;
		this.scheme = replaced.scheme;
		this.trailerHeadersConsumer = replaced.trailerHeadersConsumer;
	}

	HttpServerOperations(Connection c, ConnectionObserver listener, HttpRequest nettyRequest,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
			@Nullable ConnectionInfo connectionInfo,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			boolean secured) {
		this(c, listener, nettyRequest, compressionPredicate, connectionInfo, decoder, encoder, formDecoderProvider,
				mapHandle, true, secured);
	}

	HttpServerOperations(Connection c, ConnectionObserver listener, HttpRequest nettyRequest,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
			@Nullable ConnectionInfo connectionInfo,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			boolean resolvePath,
			boolean secured) {
		super(c, listener);
		this.compressionPredicate = compressionPredicate;
		this.connectionInfo = connectionInfo;
		this.cookieDecoder = decoder;
		this.cookieEncoder = encoder;
		this.cookieHolder = ServerCookies.newServerRequestHolder(nettyRequest.headers(), decoder);
		this.formDecoderProvider = formDecoderProvider;
		this.mapHandle = mapHandle;
		this.nettyRequest = nettyRequest;
		this.nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		if (resolvePath) {
			this.path = resolvePath(nettyRequest.uri());
		}
		else {
			this.path = null;
		}
		this.responseHeaders = nettyResponse.headers();
		this.responseHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		this.scheme = secured ? "https" : "http";
	}

	@Override
	public NettyOutbound sendHeaders() {
		if (hasSentHeaders()) {
			return this;
		}

		return then(Mono.empty());
	}

	@Override
	public HttpServerOperations withConnection(Consumer<? super Connection> withConnection) {
		Objects.requireNonNull(withConnection, "withConnection");
		withConnection.accept(this);
		return this;
	}

	@Override
	protected HttpMessage newFullBodyMessage(ByteBuf body) {
		HttpResponse res =
				new DefaultFullHttpResponse(version(), status(), body);

		if (!HttpMethod.HEAD.equals(method())) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			if (!HttpResponseStatus.NOT_MODIFIED.equals(status())) {

				if (HttpUtil.getContentLength(nettyResponse, -1) == -1) {
					responseHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
				}
			}
		}
		// For HEAD requests:
		// - if there is Transfer-Encoding and Content-Length, Transfer-Encoding will be removed
		// - if there is only Transfer-Encoding, it will be kept and not replaced by
		// Content-Length: body.readableBytes()
		// For HEAD requests, the I/O handler may decide to provide only the headers and complete
		// the response. In that case body will be EMPTY_BUFFER and if we set Content-Length: 0,
		// this will not be correct
		// https://github.com/reactor/reactor-netty/issues/1333
		else if (HttpUtil.getContentLength(nettyResponse, -1) != -1) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
		}

		res.headers().set(responseHeaders);
		return res;
	}

	@Override
	public HttpServerResponse addCookie(Cookie cookie) {
		if (!hasSentHeaders()) {
			this.responseHeaders.add(HttpHeaderNames.SET_COOKIE,
					cookieEncoder.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse addHeader(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.responseHeaders.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerOperations chunkedTransfer(boolean chunked) {
		if (!hasSentHeaders() && isTransferEncodingChunked(nettyResponse) != chunked) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			HttpUtil.setTransferEncodingChunked(nettyResponse, chunked);
		}
		return this;
	}

	@Override
	public Map<CharSequence, Set<Cookie>> cookies() {
		if (cookieHolder != null) {
			return cookieHolder.getCachedCookies();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public Map<CharSequence, List<Cookie>> allCookies() {
		if (cookieHolder != null) {
			return cookieHolder.getAllCachedCookies();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public HttpServerResponse header(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.responseHeaders.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse headers(HttpHeaders headers) {
		if (!hasSentHeaders()) {
			this.responseHeaders.set(headers);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public boolean isFormUrlencoded() {
		String contentType = requestHeaders().get(HttpHeaderNames.CONTENT_TYPE);
		return contentType != null &&
				contentType.startsWith(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString());
	}

	@Override
	public boolean isKeepAlive() {
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public boolean isMultipart() {
		return HttpPostRequestDecoder.isMultipart(nettyRequest);
	}

	@Override
	public boolean isWebsocket() {
		return get(channel()) instanceof WebsocketServerOperations;
	}

	final boolean isHttp2() {
		return requestHeaders().contains(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text());
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

	@Override
	@Nullable
	public String param(CharSequence key) {
		Objects.requireNonNull(key, "key");
		Map<String, String> params = null;
		if (paramsResolver != null) {
			params = this.paramsResolver.apply(uri());
		}
		return null != params ? params.get(key.toString()) : null;
	}

	@Override
	@Nullable
	public Map<String, String> params() {
		return null != paramsResolver ? paramsResolver.apply(uri()) : null;
	}

	@Override
	public HttpServerRequest paramsResolver(Function<? super String, Map<String, String>> paramsResolver) {
		this.paramsResolver = paramsResolver;
		return this;
	}

	@Override
	public Flux<HttpData> receiveForm() {
		return receiveFormInternal(formDecoderProvider);
	}

	@Override
	public Flux<HttpData> receiveForm(Consumer<HttpServerFormDecoderProvider.Builder> formDecoderBuilder) {
		Objects.requireNonNull(formDecoderBuilder, "formDecoderBuilder");
		HttpServerFormDecoderProvider.Build builder = new HttpServerFormDecoderProvider.Build();
		formDecoderBuilder.accept(builder);
		HttpServerFormDecoderProvider config = builder.build();
		return receiveFormInternal(config);
	}

	@Override
	public Flux<?> receiveObject() {
		// Handle the 'Expect: 100-continue' header if necessary.
		// TODO: Respond with 413 Request Entity Too Large
		//   and discard the traffic or close the connection.
		//       No need to notify the upstream handlers - just log.
		//       If decoding a response, just throw an error.
		if (HttpUtil.is100ContinueExpected(nettyRequest)) {
			return FutureMono.deferFuture(() -> {
						if (!hasSentHeaders()) {
							return channel().writeAndFlush(CONTINUE);
						}
						return channel().newSucceededFuture();
					})

			                 .thenMany(super.receiveObject());
		}
		else {
			return super.receiveObject();
		}
	}

	@Override
	@Nullable
	public InetSocketAddress hostAddress() {
		if (connectionInfo != null) {
			return this.connectionInfo.getHostAddress();
		}
		else {
			return null;
		}
	}

	@Override
	@Nullable
	public InetSocketAddress remoteAddress() {
		if (connectionInfo != null) {
			return this.connectionInfo.getRemoteAddress();
		}
		else {
			return null;
		}
	}

	@Override
	public HttpHeaders requestHeaders() {
		if (nettyRequest != null) {
			return nettyRequest.headers();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public String scheme() {
		if (connectionInfo != null) {
			return this.connectionInfo.getScheme();
		}
		else {
			return scheme;
		}
	}

	@Override
	public HttpHeaders responseHeaders() {
		return responseHeaders;
	}

	@Override
	public Mono<Void> send() {
		if (markSentHeaderAndBody()) {
			HttpMessage response = newFullBodyMessage(EMPTY_BUFFER);
			return FutureMono.deferFuture(() -> channel().writeAndFlush(response));
		}
		else {
			return Mono.empty();
		}
	}

	@Override
	public NettyOutbound sendFile(Path file) {
		try {
			return sendFile(file, 0L, Files.size(file));
		}
		catch (IOException e) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Path not resolved"), e);
			}
			return then(sendNotFound());
		}
	}

	@Override
	public Mono<Void> sendNotFound() {
		return this.status(HttpResponseStatus.NOT_FOUND)
		           .send();
	}

	@Override
	public Mono<Void> sendRedirect(String location) {
		Objects.requireNonNull(location, "location");
		return this.status(HttpResponseStatus.FOUND)
		           .header(HttpHeaderNames.LOCATION, location)
		           .send();
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
		return this.nettyResponse.status();
	}

	@Override
	public HttpServerResponse status(HttpResponseStatus status) {
		if (!hasSentHeaders()) {
			this.nettyResponse.setStatus(status);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse trailerHeaders(Consumer<? super HttpHeaders> trailerHeaders) {
		this.trailerHeadersConsumer = Objects.requireNonNull(trailerHeaders, "trailerHeaders");
		return this;
	}

	@Override
	public Mono<Void> sendWebsocket(
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler,
			WebsocketServerSpec configurer) {
		return withWebsocketSupport(uri(), configurer, websocketHandler);
	}

	@Override
	public String uri() {
		if (nettyRequest != null) {
			return nettyRequest.uri();
		}
		throw new IllegalStateException("request not parsed");
	}

	@Override
	public String fullPath() {
		if (path != null) {
			return path;
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
	public HttpServerResponse compression(boolean compress) {
		if (!compress) {
			removeHandler(NettyPipeline.CompressionHandler);
		}
		else if (channel().pipeline()
		                  .get(NettyPipeline.CompressionHandler) == null) {
			SimpleCompressionHandler handler = new SimpleCompressionHandler();
			try {
				//Do not invoke handler.channelRead as it will trigger ctx.fireChannelRead
				handler.decode(channel().pipeline().context(NettyPipeline.ReactiveBridge), nettyRequest);

				addHandlerFirst(NettyPipeline.CompressionHandler, handler);
			}
			catch (Throwable e) {
				log.error(format(channel(), ""), e);
			}
		}
		return this;
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpRequest) {
			try {
				listener().onStateChange(this, HttpServerState.REQUEST_RECEIVED);
			}
			catch (Exception e) {
				onInboundError(e);
				ReferenceCountUtil.release(msg);
				return;
			}
			if (msg instanceof FullHttpRequest) {
				FullHttpRequest request = (FullHttpRequest) msg;
				if (request.content().readableBytes() > 0) {
					super.onInboundNext(ctx, msg);
				}
				else {
					request.release();
				}
				if (isHttp2()) {
					//force auto read to enable more accurate close selection now inbound is done
					channel().config().setAutoRead(true);
					onInboundComplete();
				}
			}
			return;
		}
		if (msg instanceof HttpContent) {
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
			}
			if (msg instanceof LastHttpContent) {
				//force auto read to enable more accurate close selection now inbound is done
				channel().config().setAutoRead(true);
				onInboundComplete();
			}
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void onInboundClose() {
		discardWhenNoReceiver();
		if (!(isInboundCancelled() || isInboundDisposed())) {
			onInboundError(new AbortedException("Connection has been closed"));
		}
		terminate();
	}

	@Override
	protected void afterMarkSentHeaders() {
		if (HttpResponseStatus.NOT_MODIFIED.equals(status())) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING)
			               .remove(HttpHeaderNames.CONTENT_LENGTH);
		}
		if (compressionPredicate != null && compressionPredicate.test(this, this)) {
			compression(true);
		}
	}

	@Override
	protected void beforeMarkSentHeaders() {
		//noop
	}

	@Override
	protected void onHeadersSent() {
		//noop
	}

	@Override
	protected void onOutboundComplete() {
		if (isWebsocket()) {
			return;
		}

		final ChannelFuture f;
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), "Last HTTP response frame"));
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "No sendHeaders() called before complete, sending " +
						"zero-length header"));
			}

			f = channel().writeAndFlush(newFullBodyMessage(EMPTY_BUFFER));
		}
		else if (markSentBody()) {
			LastHttpContent lastHttpContent = LastHttpContent.EMPTY_LAST_CONTENT;
			// https://datatracker.ietf.org/doc/html/rfc7230#section-4.1.2
			// A trailer allows the sender to include additional fields at the end
			// of a chunked message in order to supply metadata that might be
			// dynamically generated while the message body is sent, such as a
			// message integrity check, digital signature, or post-processing
			// status.
			if (trailerHeadersConsumer != null && isTransferEncodingChunked(nettyResponse)) {
				// https://datatracker.ietf.org/doc/html/rfc7230#section-4.4
				// When a message includes a message body encoded with the chunked
				// transfer coding and the sender desires to send metadata in the form
				// of trailer fields at the end of the message, the sender SHOULD
				// generate a Trailer header field before the message body to indicate
				// which fields will be present in the trailers.
				String declaredHeaderNames = responseHeaders.get(HttpHeaderNames.TRAILER);
				if (declaredHeaderNames != null) {
					HttpHeaders trailerHeaders = new TrailerHeaders(declaredHeaderNames);
					try {
						trailerHeadersConsumer.accept(trailerHeaders);
					}
					catch (IllegalArgumentException e) {
						// A sender MUST NOT generate a trailer when header names are
						// HttpServerOperations.TrailerHeaders.DISALLOWED_TRAILER_HEADER_NAMES
						log.error(format(channel(), "Cannot apply trailer headers [{0}]"), declaredHeaderNames, e);
					}
					if (!trailerHeaders.isEmpty()) {
						lastHttpContent = new DefaultLastHttpContent();
						lastHttpContent.trailingHeaders().set(trailerHeaders);
					}
				}
			}
			f = channel().writeAndFlush(lastHttpContent);
		}
		else {
			discard();
			return;
		}
		f.addListener(s -> {
			discard();
			if (!s.isSuccess() && log.isDebugEnabled()) {
				log.debug(format(channel(), "Failed flushing last frame"), s.cause());
			}
		});

	}

	static void cleanHandlerTerminate(Channel ch) {
		ChannelOperations<?, ?> ops = get(ch);

		if (ops == null) {
			return;
		}

		ops.discard();

		//Try to defer the disposing to leave a chance for any synchronous complete following this callback
		if (!ops.isSubscriptionDisposed()) {
			ch.eventLoop()
			  .execute(((HttpServerOperations) ops)::terminate);
		}
		else {
			//if already disposed, we can immediately call terminate
			((HttpServerOperations) ops).terminate();
		}
	}

	static long requestsCounter(Channel channel) {
		HttpServerOperations ops = Connection.from(channel).as(HttpServerOperations.class);

		if (ops == null) {
			return -1;
		}

		return ((AtomicLong) ops.connection()).get();
	}

	static void sendDecodingFailures(
			ChannelHandlerContext ctx,
			ConnectionObserver listener,
			boolean secure,
			Throwable t,
			Object msg) {

		Connection conn = Connection.from(ctx.channel());

		Throwable cause = t.getCause() != null ? t.getCause() : t;

		if (log.isWarnEnabled()) {
			log.warn(format(ctx.channel(), "Decoding failed: " + msg + " : "), cause);
		}

		ReferenceCountUtil.release(msg);

		HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_0,
				cause instanceof TooLongFrameException ? HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE :
				                                         HttpResponseStatus.BAD_REQUEST);
		response.headers()
		        .setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
		        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		ctx.writeAndFlush(response)
		   .addListener(ChannelFutureListener.CLOSE);

		HttpRequest request = null;
		if (msg instanceof HttpRequest) {
			request = (HttpRequest) msg;
		}
		listener.onStateChange(new FailedHttpServerRequest(conn, listener, request, response, secure), REQUEST_DECODING_FAILED);
	}

	/**
	 * There is no need of invoking {@link #discard()}, the inbound will
	 * be canceled on channel inactive event if there is no subscriber available
	 *
	 * @param err the {@link Throwable} cause
	 */
	@Override
	protected void onOutboundError(Throwable err) {

		if (!channel().isActive()) {
			super.onOutboundError(err);
			return;
		}

		if (markSentHeaders()) {
			log.error(format(channel(), "Error starting response. Replying error status"), err);

			nettyResponse.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
			responseHeaders.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
			channel().writeAndFlush(newFullBodyMessage(EMPTY_BUFFER))
			         .addListener(ChannelFutureListener.CLOSE);
			return;
		}

		markSentBody();
		log.error(format(channel(), "Error finishing response. Closing connection"), err);
		channel().writeAndFlush(EMPTY_BUFFER)
		         .addListener(ChannelFutureListener.CLOSE);
	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyResponse;
	}

	final Flux<HttpData> receiveFormInternal(HttpServerFormDecoderProvider config) {
		boolean isMultipart = isMultipart();
		if (!Objects.equals(method(), HttpMethod.POST) || !(isFormUrlencoded() || isMultipart)) {
			return Flux.error(new IllegalStateException(
					"Request is not POST or does not have Content-Type " +
							"with value 'application/x-www-form-urlencoded' or 'multipart/form-data'"));
		}
		return Flux.defer(() ->
				config.newHttpPostRequestDecoder(nettyRequest, isMultipart).flatMapMany(decoder ->
						receiveObject() // receiveContent uses filter operator, this operator buffers, but we don't want it
								.concatMap(object -> {
									if (!(object instanceof HttpContent)) {
										return Mono.empty();
									}
									HttpContent httpContent = (HttpContent) object;
									if (config.maxInMemorySize > -1) {
										httpContent.retain();
									}
									return config.maxInMemorySize == -1 ?
											Flux.using(
													() -> decoder.offer(httpContent),
													d -> Flux.fromIterable(decoder.currentHttpData(!config.streaming)),
													d -> decoder.cleanCurrentHttpData(!config.streaming)) :
											Flux.usingWhen(
													Mono.fromCallable(() -> decoder.offer(httpContent))
													    .subscribeOn(config.scheduler)
													    .doFinally(sig -> httpContent.release()),
													d -> Flux.fromIterable(decoder.currentHttpData(true)),
													// FIXME Can we have cancellation for the resourceSupplier that will
													// cause this one to not be invoked?
													d -> Mono.fromRunnable(() -> decoder.cleanCurrentHttpData(true)));
								}, 0) // There is no need of prefetch, we already have the buffers in the Reactor Netty inbound queue
								.doFinally(sig -> decoder.destroy())));
	}

	final Mono<Void> withWebsocketSupport(String url,
			WebsocketServerSpec websocketServerSpec,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketServerSpec, "websocketServerSpec");
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		if (markSentHeaders()) {
			WebsocketServerOperations ops = new WebsocketServerOperations(url, websocketServerSpec, this);

			return FutureMono.from(ops.handshakerResult)
			                 .doOnEach(signal -> {
			                     if (!signal.hasError() && (websocketServerSpec.protocols() == null || ops.selectedSubprotocol() != null)) {
			                         websocketHandler.apply(ops, ops)
			                                         .subscribe(new WebsocketSubscriber(ops, Context.of(signal.getContextView())));
			                     }
			                 });
		}
		else {
			log.error(format(channel(), "Cannot enable websocket if headers have already been sent"));
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	static final class WebsocketSubscriber implements CoreSubscriber<Void>, ChannelFutureListener {
		final WebsocketServerOperations ops;
		final Context                context;

		WebsocketSubscriber(WebsocketServerOperations ops, Context context) {
			this.ops = ops;
			this.context = context;
		}

		@Override
		public void onSubscribe(Subscription s) {
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(Void aVoid) {

		}

		@Override
		public void onError(Throwable t) {
			ops.onError(t);
		}

		@Override
		public void operationComplete(ChannelFuture future)  {
			ops.terminate();
		}

		@Override
		public void onComplete() {
			if (ops.channel()
			       .isActive()) {
				ops.sendCloseNow(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE), this);
			}
		}

		@Override
		public Context currentContext() {
			return context;
		}
	}

	static final Logger log = Loggers.getLogger(HttpServerOperations.class);
	final static AsciiString      EVENT_STREAM = new AsciiString("text/event-stream");

	final static FullHttpResponse CONTINUE     =
			new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.CONTINUE,
					EMPTY_BUFFER);

	static final class FailedHttpServerRequest extends HttpServerOperations {

		final HttpResponse customResponse;

		FailedHttpServerRequest(
				Connection c,
				ConnectionObserver listener,
				@Nullable HttpRequest nettyRequest,
				HttpResponse nettyResponse,
				boolean secure) {
			super(c, listener, nettyRequest, null, null, ServerCookieDecoder.STRICT, ServerCookieEncoder.STRICT,
					DEFAULT_FORM_DECODER_SPEC, null, false, secure);
			this.customResponse = nettyResponse;
		}

		@Override
		protected HttpMessage outboundHttpMessage() {
			return customResponse;
		}

		@Override
		public HttpResponseStatus status() {
			return customResponse.status();
		}
	}

	static final class TrailerHeaders extends DefaultHttpHeaders {

		static final Set<String> DISALLOWED_TRAILER_HEADER_NAMES = new HashSet<>(14);
		static {
			// https://datatracker.ietf.org/doc/html/rfc7230#section-4.1.2
			// A sender MUST NOT generate a trailer that contains a field necessary
			// for message framing (e.g., Transfer-Encoding and Content-Length),
			// routing (e.g., Host), request modifiers (e.g., controls and
			// conditionals in Section 5 of [RFC7231]), authentication (e.g., see
			// [RFC7235] and [RFC6265]), response control data (e.g., see Section
			// 7.1 of [RFC7231]), or determining how to process the payload (e.g.,
			// Content-Encoding, Content-Type, Content-Range, and Trailer).
			DISALLOWED_TRAILER_HEADER_NAMES.add("age");
			DISALLOWED_TRAILER_HEADER_NAMES.add("cache-control");
			DISALLOWED_TRAILER_HEADER_NAMES.add("content-encoding");
			DISALLOWED_TRAILER_HEADER_NAMES.add("content-length");
			DISALLOWED_TRAILER_HEADER_NAMES.add("content-range");
			DISALLOWED_TRAILER_HEADER_NAMES.add("content-type");
			DISALLOWED_TRAILER_HEADER_NAMES.add("date");
			DISALLOWED_TRAILER_HEADER_NAMES.add("expires");
			DISALLOWED_TRAILER_HEADER_NAMES.add("location");
			DISALLOWED_TRAILER_HEADER_NAMES.add("retry-after");
			DISALLOWED_TRAILER_HEADER_NAMES.add("trailer");
			DISALLOWED_TRAILER_HEADER_NAMES.add("transfer-encoding");
			DISALLOWED_TRAILER_HEADER_NAMES.add("vary");
			DISALLOWED_TRAILER_HEADER_NAMES.add("warning");
		}

		TrailerHeaders(String declaredHeaderNames) {
			super(true, new TrailerNameValidator(filterHeaderNames(declaredHeaderNames)));
		}

		static Set<String> filterHeaderNames(String declaredHeaderNames) {
			Objects.requireNonNull(declaredHeaderNames, "declaredHeaderNames");
			Set<String> result = new HashSet<>();
			String[] names = declaredHeaderNames.split(",", -1);
			for (String name : names) {
				String trimmedStr = name.trim();
				if (trimmedStr.isEmpty() ||
						DISALLOWED_TRAILER_HEADER_NAMES.contains(trimmedStr.toLowerCase(Locale.ENGLISH))) {
					continue;
				}
				result.add(trimmedStr);
			}
			return result;
		}

		static final class TrailerNameValidator implements DefaultHeaders.NameValidator<CharSequence> {

			/**
			 * Contains the headers names specified with {@link HttpHeaderNames#TRAILER}
			 */
			final Set<String> declaredHeaderNames;

			TrailerNameValidator(Set<String> declaredHeaderNames) {
				this.declaredHeaderNames = declaredHeaderNames;
			}

			@Override
			public void validateName(CharSequence name) {
				if (!declaredHeaderNames.contains(name.toString())) {
					throw new IllegalArgumentException("Trailer header name [" + name +
							"] not declared with [Trailer] header, or it is not a valid trailer header name");
				}
			}
		}
	}
}
