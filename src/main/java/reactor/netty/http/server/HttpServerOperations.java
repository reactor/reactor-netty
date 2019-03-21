/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.netty.http.server;

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
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
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
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Cookies;
import reactor.netty.http.HttpOperations;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static reactor.netty.ReactorNetty.format;

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
	final ServerCookieEncoder cookieEncoder;
	final ServerCookieDecoder cookieDecoder;

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
		this.cookieEncoder = replaced.cookieEncoder;
		this.cookieDecoder = replaced.cookieDecoder;
	}

	HttpServerOperations(Connection c,
			ConnectionObserver listener,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
			HttpRequest nettyRequest,
			ConnectionInfo connectionInfo,
			ServerCookieEncoder encoder,
			ServerCookieDecoder decoder) {
		super(c, listener);
		this.nettyRequest = nettyRequest;
		this.nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		this.responseHeaders = nettyResponse.headers();
		this.compressionPredicate = compressionPredicate;
		this.cookieHolder = Cookies.newServerRequestHolder(requestHeaders(), decoder);
		this.connectionInfo = connectionInfo;
		this.cookieEncoder = encoder;
		this.cookieDecoder = decoder;
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
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			if (!HttpResponseStatus.NOT_MODIFIED.equals(status())) {
				responseHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
			}
		}

		res.headers().set(responseHeaders);

		markPersistent(true);
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
		return isKeepAlive(nettyRequest);
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
			int maxFramePayloadLength,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		return withWebsocketSupport(uri(), protocols, maxFramePayloadLength, websocketHandler);
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
		if (HttpResponseStatus.NOT_MODIFIED.equals(status())) {
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING)
			               .remove(HttpHeaderNames.CONTENT_LENGTH);
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
			log.debug(format(channel(), "Last HTTP response frame"));
		}
		if (markSentHeaderAndBody()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "No sendHeaders() called before complete, sending " +
						"zero-length header"));
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
				log.debug(format(channel(), "Failed flushing last frame"), s.cause());
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
		log.error(format(channel(), "Error finishing response. Closing connection"), err);
		channel().writeAndFlush(EMPTY_BUFFER)
		         .addListener(ChannelFutureListener.CLOSE);
	}

	@Override
	protected HttpMessage outboundHttpMessage() {
		return nettyResponse;
	}

	final Mono<Void> withWebsocketSupport(String url,
			@Nullable String protocols,
			int maxFramePayloadLength,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		if (markSentHeaders()) {
			WebsocketServerOperations
					ops = new WebsocketServerOperations(url, protocols, maxFramePayloadLength, this);

			if (rebind(ops)) {
				return FutureMono.from(ops.handshakerResult)
				                 .doOnEach(signal -> {
				                 	if(!signal.hasError() && (protocols == null || ops.selectedSubprotocol() != null)) {
					                    websocketHandler.apply(ops, ops)
					                                    .subscribe(new WebsocketSubscriber(ops, signal.getContext()));
				                    }
				                 });
			}
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
				ops.sendCloseNow(null, this);
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
}
