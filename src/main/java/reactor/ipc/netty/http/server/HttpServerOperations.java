/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
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
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.AsciiString;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.http.Cookies;
import reactor.ipc.netty.http.HttpOperations;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;
import reactor.ipc.netty.http2.server.Http2ServerRequest;
import reactor.ipc.netty.http2.server.Http2ServerResponse;
import reactor.util.Logger;
import reactor.util.Loggers;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;

/**
 * Conversion between Netty types  and Reactor types ({@link HttpOperations}.
 *
 * @author Stephane Maldini1
 */
class HttpServerOperations extends HttpOperations<HttpServerRequest, HttpServerResponse>
		implements HttpServerRequest, HttpServerResponse {

	final HttpResponse nettyResponse;
	final HttpHeaders  responseHeaders;
	final Cookies     cookieHolder;
	final HttpRequest nettyRequest;
	final ConnectionInfo connectionInfo;

	final BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate;

	Function<? super String, Map<String, String>> paramsResolver;

	HttpServerOperations(HttpServerOperations replaced) {
		super(replaced);
		this.cookieHolder = replaced.cookieHolder;
		this.connectionInfo = replaced.connectionInfo;
		this.responseHeaders = replaced.responseHeaders;
		this.nettyResponse = replaced.nettyResponse;
		this.paramsResolver = replaced.paramsResolver;
		this.nettyRequest = replaced.nettyRequest;
		this.compressionPredicate = replaced.compressionPredicate;
	}

	HttpServerOperations(Connection c,
			ConnectionObserver listener,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
			HttpRequest nettyRequest,
			ConnectionInfo connectionInfo) {
		super(c, listener);
		this.nettyRequest = nettyRequest;
		this.nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		this.responseHeaders = nettyResponse.headers();
		this.compressionPredicate = compressionPredicate;
		this.cookieHolder = Cookies.newServerRequestHolder(requestHeaders());
		this.connectionInfo = connectionInfo;
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
	protected HttpMessage newFullEmptyBodyMessage() {
		HttpResponse res =
				new DefaultFullHttpResponse(version(), status(), EMPTY_BUFFER);

		if (!HttpMethod.HEAD.equals(method())) {
			res.headers()
			   .set(responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING)
			                       .setInt(HttpHeaderNames.CONTENT_LENGTH, 0));
		}
		else {
			res.headers().set(responseHeaders);
		}
		markPersistent(true);
		return res;
	}

	@Override
	public HttpServerResponse addCookie(Cookie cookie) {
		if (!hasSentHeaders()) {
			this.responseHeaders.add(HttpHeaderNames.SET_COOKIE,
					ServerCookieEncoder.STRICT.encode(cookie));
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
		if (!hasSentHeaders() && HttpUtil.isTransferEncodingChunked(nettyResponse) != chunked) {
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
	public boolean isKeepAlive() {
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public boolean isWebsocket() {
		return requestHeaders().contains(HttpHeaderNames.UPGRADE,
				HttpHeaderValues.WEBSOCKET,
				true)
				&& HttpResponseStatus.SWITCHING_PROTOCOLS.equals(status());
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
		return null != params ? params.get(key) : null;
	}

	@Override
	@Nullable
	public Map<String, String> params() {
		return null != paramsResolver ? paramsResolver.apply(uri()) : null;
	}

	@Override
	public HttpServerRequest paramsResolver(Function<? super String, Map<String, String>> headerResolver) {
		this.paramsResolver = headerResolver;
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
			return FutureMono.deferFuture(() -> {
						if(!hasSentHeaders()) {
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
	public InetSocketAddress hostAddress() {
		return this.connectionInfo.getHostAddress();
	}

	@Override
	public InetSocketAddress remoteAddress() {
		return this.connectionInfo.getRemoteAddress();
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
		return this.connectionInfo.getScheme();
	}

	@Override
	public HttpHeaders responseHeaders() {
		return responseHeaders;
	}

	@Override
	public Mono<Void> send() {
		if (markSentHeaderAndBody()) {
			HttpMessage response = newFullEmptyBodyMessage();
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
				log.debug("Path not resolved", e);
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

	@Override
	public Mono<Void> asHttp2(BiFunction<? super Http2ServerRequest, ? super Http2ServerResponse, ? extends Publisher<Void>> handler) {
		return Mono.error(new IllegalStateException("Current operations is running on HTTP1 protocol channel"));
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
	public Mono<Void> sendWebsocket(@Nullable String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		return withWebsocketSupport(uri(), protocols, websocketHandler);
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
	public HttpServerResponse compression(boolean compress) {
		if (!compress) {
			removeHandler(NettyPipeline.CompressionHandler);
		}
		else if (channel().pipeline()
		                  .get(NettyPipeline.CompressionHandler) == null) {
			SimpleCompressionHandler handler = new SimpleCompressionHandler();

			try {
				handler.channelRead(channel().pipeline()
				                             .context(NettyPipeline.ReactiveBridge),
						nettyRequest);

				addHandlerFirst(NettyPipeline.CompressionHandler, handler);
			}
			catch (Throwable e) {
				log.error("", e);
			}
		}
		return this;
	}
	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpRequest) {
			listener().onStateChange(this, ConnectionObserver.State.CONFIGURED);
			if (msg instanceof FullHttpRequest) {
				super.onInboundNext(ctx, msg);
			}
			return;
		}
		if (msg instanceof HttpContent) {
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
			}
			if (msg instanceof LastHttpContent) {
				onInboundComplete();
			}
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void preSendHeadersAndStatus(){
		if (!HttpUtil.isTransferEncodingChunked(nettyResponse) && !HttpUtil.isContentLengthSet(
				nettyResponse)) {
			markPersistent(false);
		}
		if (compressionPredicate != null && compressionPredicate.test(this, this)) {
			compression(true);
		}
	}

	@Override
	protected void onOutboundComplete() {
		if (isWebsocket()) {
			return;
		}

		final ChannelFuture f;
		if (log.isDebugEnabled()) {
			log.debug("Last HTTP response frame");
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug("No sendHeaders() called before complete, sending " + "zero-length header");
			}

			f = channel().writeAndFlush(newFullEmptyBodyMessage());
		}
		else if (markSentBody()) {
			f = channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		}
		else{
			discard();
			return;
		}
		f.addListener(s -> {
			discard();
			if (!s.isSuccess() && log.isDebugEnabled()) {
				log.error(channel()+" Failed flushing last frame", s.cause());
			}
		});

	}

	static void cleanHandlerTerminate(Channel ch){
		ChannelOperations<?, ?> ops = get(ch);

		if (ops == null) {
			return;
		}

		((HttpServerOperations)ops).terminate();
	}

	@Override
	protected void onOutboundError(Throwable err) {

		if (!channel().isActive()) {
			super.onOutboundError(err);
			return;
		}

		if (markSentHeaders()) {
			log.error("Error starting response. Replying error status", err);

			HttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.INTERNAL_SERVER_ERROR);
			response.headers()
			        .setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
			        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
			channel().writeAndFlush(response)
			         .addListener(ChannelFutureListener.CLOSE);
			return;
		}

		markSentBody();
		log.error("Error finishing response. Closing connection", err);
		channel().writeAndFlush(EMPTY_BUFFER)
		         .addListener(ChannelFutureListener.CLOSE);
	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyResponse;
	}

	final Mono<Void> withWebsocketSupport(String url,
			@Nullable String protocols,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		if (markSentHeaders()) {
			WebsocketServerOperations
					ops = new WebsocketServerOperations(url, protocols, this);

			if (rebind(ops)) {
				return FutureMono.from(ops.handshakerResult)
				                 .then(Mono.defer(() -> {
					                 //skip handler if no matching subprotocol
					                 if (protocols != null && ops.selectedSubprotocol() == null) {
						                 return Mono.empty();
					                 }
					                 return Mono.fromDirect(websocketHandler.apply(ops, ops));
				                 }))
				                 .doAfterSuccessOrError(ops);
			}
		}
		else {
			log.error("Cannot enable websocket if headers have already been sent");
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to websocket"));
	}

	static final Logger log = Loggers.getLogger(HttpServerOperations.class);
	final static AsciiString      EVENT_STREAM = new AsciiString("text/event-stream");

	final static FullHttpResponse CONTINUE     =
			new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.CONTINUE,
					EMPTY_BUFFER);
}
