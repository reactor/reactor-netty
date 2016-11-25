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

package reactor.ipc.netty.http.server;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
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
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.AsciiString;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.http.Cookies;
import reactor.ipc.netty.http.HttpInbound;
import reactor.ipc.netty.http.HttpOperations;
import reactor.ipc.netty.http.HttpOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;

/**
 * Conversion between Netty types  and Reactor types ({@link HttpOperations}.
 *
 * @author Stephane Maldini
 */
class HttpServerOperations extends HttpOperations<HttpServerRequest, HttpServerResponse>
		implements HttpServerRequest, HttpServerResponse {

	static HttpServerOperations bindHttp(Channel channel,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		return new HttpServerOperations(channel, handler, context);
	}

	final HttpResponse nettyResponse;
	final HttpHeaders  responseHeaders;

	Cookies                                       cookieHolder;
	HttpRequest                                   nettyRequest;
	Function<? super String, Map<String, Object>> paramsResolver;

	HttpServerOperations(Channel ch, HttpServerOperations replaced) {
		super(ch, replaced);
		this.cookieHolder = replaced.cookieHolder;
		this.responseHeaders = replaced.responseHeaders;
		this.nettyResponse = replaced.nettyResponse;
		this.paramsResolver = replaced.paramsResolver;
	}

	HttpServerOperations(Channel ch,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		super(ch, handler, context);
		this.nettyResponse =
				new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		this.responseHeaders = nettyResponse.headers();
		responseHeaders.add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
		               //.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
		               .add(HttpHeaderNames.DATE, new Date());
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
		if (!hasSentHeaders()) {
			this.responseHeaders.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpServerResponse chunkedTransfer(boolean chunked) {
		if (!hasSentHeaders()) {
			HttpUtil.setTransferEncodingChunked(nettyResponse, chunked);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
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
	public HttpServerResponse disableChunkedTransfer() {
		HttpUtil.setTransferEncodingChunked(nettyResponse, false);
		return this;
	}

	@Override
	public HttpServerResponse flushEach() {
		super.flushEach();
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
		if (!hasSentHeaders()) {
			this.responseHeaders.set(name, value);
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
		String isWebsocket = requestHeaders().get(HttpHeaderNames.UPGRADE);
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
		Objects.requireNonNull(key, "key");
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
	 * @param headerResolver a selector accepting the current URI string and returning
	 * grouped parameters.
	 */
	@Override
	public HttpServerRequest paramsResolver(Function<? super String, Map<String, Object>> headerResolver) {
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
			return ChannelFutureMono.from(() -> channel().writeAndFlush(CONTINUE))
			                        .thenMany(super.receiveObject());
		}
		else {
			return super.receiveObject();
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
	public HttpHeaders responseHeaders() {
		return responseHeaders;
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
		if (!hasSentHeaders()) {
			this.nettyResponse.setStatus(status);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public Mono<Void> upgradeToWebsocket(String protocols,
			boolean textPlain,
			BiFunction<? super HttpInbound, ? super HttpOutbound, ? extends Publisher<Void>> websocketHandler) {
		return withWebsocketSupport(uri(), protocols, textPlain, websocketHandler);
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
	protected void onChannelActive(ChannelHandlerContext ctx) {
		ctx.pipeline()
		   .addBefore(NettyHandlerNames.ReactiveBridge,
				   NettyHandlerNames.HttpCodecHandler,
				   new HttpServerCodec());

		ctx.read();
	}

	@Override
	public Mono<Void> sendFile(File file, long position, long count) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}

		if (!hasSentHeaders() && !HttpUtil.isTransferEncodingChunked(nettyResponse) && !HttpUtil.isContentLengthSet(
				nettyResponse) && count < Integer.MAX_VALUE) {
			responseHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, (int) count);
		}

		Supplier<Mono<Void>> writeFile = () -> super.sendFile(file, position, count);

		return sendHeaders().then(writeFile);
	}

	@Override
	public Mono<Void> sendFull(Publisher<? extends ByteBuf> source) {
		ByteBufAllocator alloc = channel().alloc();
		return Flux.from(source)
		           .doOnNext(ByteBuf::retain)
		           .collect(alloc::buffer, ByteBuf::writeBytes)
		           .then(agg -> {
			           if (!hasSentHeaders() && !HttpUtil.isTransferEncodingChunked(nettyResponse) && !HttpUtil.isContentLengthSet(
					           nettyResponse)) {
				           responseHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH,
						           agg.readableBytes());
			           }
			           return super.send(Mono.just(agg));
		           });
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpRequest) {
			nettyRequest = (HttpRequest) msg;
			cookieHolder = Cookies.newServerRequestHolder(requestHeaders());

			if (nettyRequest.decoderResult()
			                .isFailure()) {
				onOutboundError(nettyRequest.decoderResult()
				                            .cause());
				return;
			}

			if (isWebsocket()) {
				HttpObjectAggregator agg = new HttpObjectAggregator(65536);
				channel().pipeline()
				         .addBefore(NettyHandlerNames.ReactiveBridge,
						         NettyHandlerNames.HttpAggregator,
						         agg);
			}

			applyHandler();

			if (!(msg instanceof FullHttpRequest)) {
				return;
			}
		}
		if (msg instanceof HttpContent) {
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
			}
			if (msg instanceof LastHttpContent) {
				if(isTerminated()) {
					release();
				}
				else{
					onInboundComplete();
				}
				return;
			}
			if(isTerminated()){
				ctx.read();
			}
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void onOutboundComplete() {
		if (log.isDebugEnabled()) {
			log.debug("User Handler requesting a last HTTP frame write", formatName());
		}
		if (markReceiving() || isWebsocket()) {
			release();
		}
		else {
			if (log.isDebugEnabled()) {
				log.debug("Consuming keep-alive connection, prepare to ignore extra " + "frames");
			}
			channel().read();
		}
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (discreteRemoteClose(err)){
			return;
		}
		if (markHeadersAsSent()) {
			log.error("Error starting response. Replying error status", err);

			HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.INTERNAL_SERVER_ERROR);
			response.headers()
			        .setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
			channel().writeAndFlush(response)
			         .addListener(r -> onChannelTerminate());
			return;
		}
		log.error("Error processing response. Sending last HTTP frame", err);

		if (HttpUtil.isContentLengthSet(nettyResponse)) {
			channel().writeAndFlush(EMPTY_BUFFER)
			         .addListener(r -> onChannelTerminate());
			return;
		}
		channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
		         .addListener(r -> onChannelTerminate());
	}

	@Override
	protected void sendHeadersAndSubscribe(Subscriber<? super Void> s) {
		ChannelFutureMono.from(channel().writeAndFlush(nettyResponse))
		                 .subscribe(s);
	}

	final void release() {
		if (log.isDebugEnabled()) {
			log.debug("Last HTTP response frame");
		}
		ChannelFuture f = null;
		if (!isWebsocket()) {
			if (markHeadersAsSent()) {
				channel().write(nettyResponse);
			}
			if (!HttpUtil.isContentLengthSet(nettyResponse)) {
				f = channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
			}
			if (!isKeepAlive()) {
				//fast path vs deferChannelTerminate
				if(f != null) {
					f.addListener(ChannelFutureListener.CLOSE);
				}
				else{
					channel().close();
				}
				return;
			}


			if(f != null) {
				f.addListener(s -> {
					if (!s.isSuccess() && log.isDebugEnabled()) {
						log.error("Failed flushing last frame", s.cause());
					}
					super.onChannelTerminate();
				});
				return;
			}

			super.onChannelTerminate();
		}
		else {
			f = channel().writeAndFlush(new CloseWebSocketFrame());
			f.addListener(s -> {
				if (!s.isSuccess() && log.isDebugEnabled()) {
					log.error("Failed flushing last frame", s.cause());
				}
				super.onChannelTerminate();
			});
		}
	}

	final Mono<Void> withWebsocketSupport(String url,
			String protocols,
			boolean textPlain,
			BiFunction<? super HttpInbound, ? super HttpOutbound, ? extends Publisher<Void>> websocketHandler) {
		Objects.requireNonNull(websocketHandler, "websocketHandler");
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (markHeadersAsSent()) {
			HttpServerWSOperations ops =
					new HttpServerWSOperations(url, protocols, this, textPlain);

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

	static final Logger           log          =
			Loggers.getLogger(HttpServerOperations.class);
	final static AsciiString      EVENT_STREAM = new AsciiString("text/event-stream");
	final static FullHttpResponse CONTINUE     =
			new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.CONTINUE,
					EMPTY_BUFFER);
}
