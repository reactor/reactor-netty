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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
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
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.http.Cookies;
import reactor.ipc.netty.http.HttpInbound;
import reactor.ipc.netty.http.HttpOperations;
import reactor.ipc.netty.http.HttpOutbound;
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
	final HttpHeaders headers;

	volatile ResponseState responseState;
	int inboundPrefetch;

	boolean redirectable;

	HttpClientOperations(Channel channel, HttpClientOperations replaced) {
		super(channel, replaced);
		this.redirectedFrom = replaced.redirectedFrom;
		this.isSecure = replaced.isSecure;
		this.nettyRequest = replaced.nettyRequest;
		this.responseState = replaced.responseState;
		this.redirectable = replaced.redirectable;
		this.inboundPrefetch = replaced.inboundPrefetch;
		this.headers = replaced.headers;
	}

	HttpClientOperations(Channel channel,
			BiFunction<? super HttpClientResponse, ? super HttpClientRequest, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		super(channel, handler, context);
		this.isSecure = channel.pipeline()
		                       .get(NettyHandlerNames.SslHandler) != null;
		String[] redirects = channel.attr(REDIRECT_ATTR_KEY)
		                            .get();
		this.redirectedFrom = redirects == null ? EMPTY_REDIRECTIONS : redirects;
		this.nettyRequest =
				new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
		this.headers = nettyRequest.headers();
		this.inboundPrefetch = 16;
	}

	@Override
	public HttpClientRequest addCookie(Cookie cookie) {
		if (!hasSentHeaders()) {
			this.headers.add(HttpHeaderNames.COOKIE,
					ClientCookieEncoder.STRICT.encode(cookie));
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
	public HttpClientRequest addHeader(CharSequence name, CharSequence value) {
		if (!hasSentHeaders()) {
			this.headers.add(name, value);
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
	public Map<CharSequence, Set<Cookie>> cookies() {
		ResponseState responseState = this.responseState;
		if (responseState != null) {
			return responseState.cookieHolder.getCachedCookies();
		}
		return null;
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
	public HttpClientRequest disableChunkedTransfer() {
		HttpUtil.setTransferEncodingChunked(nettyRequest, false);
		return this;
	}

	@Override
	public void dispose() {
		cancel();
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
		if (!hasSentHeaders()) {
			this.headers.set(name, value);
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
	public HttpClientRequest keepAlive(boolean keepAlive) {
		HttpUtil.setKeepAlive(nettyRequest, keepAlive);
		return this;
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
	}

	@Override
	public Mono<Void> onClose() {
		return parentContext().onClose();
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
	protected NettyContext context() {
		return this;
	}

	@Override
	protected void onChannelActive(final ChannelHandlerContext ctx) {
		ctx.pipeline()
		   .addBefore(NettyHandlerNames.ReactiveBridge,
				   NettyHandlerNames.HttpCodecHandler,
				   new HttpClientCodec());

		HttpUtil.setTransferEncodingChunked(nettyRequest, true);

		applyHandler();
	}

	@Override
	protected void onChannelTerminate() {
		if (!isKeepAlive()) {
			super.onChannelTerminate();
		}
		else {
			if (log.isDebugEnabled()) {
				log.debug("Consuming keep-alive connection, prepare to ignore extra " + "frames");
			}
			channel().pipeline()
			         .addAfter(NettyHandlerNames.ReactiveBridge,
					         NettyHandlerNames.OnHttpClose,
					         new ChannelInboundHandlerAdapter() {
						         @Override
						         public void channelRead(ChannelHandlerContext ctx,
								         Object msg) throws Exception {
							         if (isWebsocket() && msg instanceof CloseWebSocketFrame || msg instanceof LastHttpContent) {
								         HttpClientOperations.super.onChannelTerminate();
							         }
							         else {
								         ctx.read();
								         if (log.isDebugEnabled()) {
									         log.debug("Consuming keep-alive " + "connection, " + "dropping" + " " + "frame: {}",
											         msg.toString());
								         }
							         }
						         }
					         })
			         .remove(NettyHandlerNames.ReactiveBridge);

		}
	}

	@Override
	protected void onOutboundComplete() {
		if (channel().isOpen()) {
			if (!isWebsocket()) {
				boolean chunked = HttpUtil.isTransferEncodingChunked(nettyRequest);
				if (markHeadersAsSent()) {
					if(!chunked){
						channel().writeAndFlush(nettyRequest)
						         .addListener(r -> deferChannelTerminate());
						return;
					}
					else{
						channel().write(nettyRequest);
					}
				}
				if(chunked) {
					channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
					         .addListener(r -> deferChannelTerminate());
				}
			}
			else{
				deferChannelTerminate();
			}
		}
	}

	final void prefetchMore(ChannelHandlerContext ctx){
		int inboundPrefetch = this.inboundPrefetch - 1;
		if(inboundPrefetch >= 0){
			this.inboundPrefetch = inboundPrefetch;
			ctx.read();
		}
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
			if(msg instanceof FullHttpResponse) {
				super.onInboundNext(ctx, msg);
				onChannelInactive();
			}
			return;
		}
		if (!(msg instanceof LastHttpContent)) {
			super.onInboundNext(ctx, msg);
			prefetchMore(ctx);
		}
		else{
			if (log.isDebugEnabled()) {
				log.debug("Received last HTTP packet");
			}
			if(msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				super.onInboundNext(ctx, msg);
			}
			onChannelInactive();
		}
	}

	@Override
	public Mono<Void> send(Publisher<? extends ByteBuf> dataStream) {
		if (method() == HttpMethod.GET || method() == HttpMethod.HEAD) {
			return sendFull(dataStream);
		}
		return super.send(dataStream);
	}

	@Override
	public Mono<Void> sendFull(Publisher<? extends ByteBuf> source) {
		ByteBufAllocator alloc = channel().alloc();
		return Flux.from(source)
		           .doOnNext(ByteBuf::retain)
		           .collect(alloc::buffer, ByteBuf::writeBytes)
		           .then(agg -> {
			           if (!hasSentHeaders()) {
				           header(HttpHeaderNames.CONTENT_LENGTH,
						           "" + agg.readableBytes());
			           }
			           return super.send(Mono.just(agg));
		           });
	}

	@Override
	protected void sendHeadersAndSubscribe(Subscriber<? super Void> s) {
		ChannelFutureMono.from(channel().writeAndFlush(nettyRequest))
		                 .subscribe(s);
	}

	final boolean checkResponseCode(HttpResponse response) {
		int code = response.status()
		                   .code();
		if (code >= 400) {
			if (log.isDebugEnabled()) {
				log.debug("Received Server Error, stop reading: {}", response.toString());
			}
			Exception ex = new HttpClientException(this);
			super.onChannelTerminate(); // force terminate
			parentContext().fireContextError(ex);
			return false;
		}
		if (code >= 300 && isFollowRedirect()) {
			if (log.isDebugEnabled()) {
				log.debug("Received Redirect location: {}",
						response.headers()
						        .entries()
						        .toString());
			}
			Exception ex = new RedirectClientException(this);
			super.onChannelTerminate(); // force terminate
			parentContext().fireContextError(ex);
			return false;
		}
		return true;
	}

	final HttpRequest getNettyRequest() {
		return nettyRequest;
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
		if (markHeadersAsSent()) {
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

	static final int                    MAX_REDIRECTS      = 50;
	static final String[]               EMPTY_REDIRECTIONS = new String[0];
	static final Logger                 log                =
			Loggers.getLogger(HttpClientOperations.class);
	static final AttributeKey<String[]> REDIRECT_ATTR_KEY  =
			AttributeKey.newInstance("httpRedirects");
}
