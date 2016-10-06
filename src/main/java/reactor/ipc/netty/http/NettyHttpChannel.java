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

import java.io.File;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AsciiString;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Loopback;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.common.MonoChannelFuture;
import reactor.ipc.netty.tcp.TcpChannel;

/**
 * @author Sebastien Deleuze
 * @author Stephane Maldini
 */
abstract class NettyHttpChannel extends TcpChannel
		implements HttpChannel, HttpInbound, HttpOutbound {

	final static AsciiString EVENT_STREAM = new AsciiString("text/event-stream");

	final HttpRequest nettyRequest;
	final HttpHeaders headers;
	HttpResponse nettyResponse;
	HttpHeaders  responseHeaders;

	volatile int statusAndHeadersSent = 0;
	Function<? super String, Map<String, Object>> paramsResolver;

	public NettyHttpChannel(io.netty.channel.Channel ioChannel,
			Flux<Object> input,
			HttpRequest request
	) {
		super(ioChannel, input);
		if (request == null) {
			nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
		}
		else {
			nettyRequest = request;
		}

		this.nettyResponse = new DefaultHttpResponse(nettyRequest.protocolVersion(), HttpResponseStatus.OK);
		this.headers = nettyRequest.headers();
		this.responseHeaders = nettyResponse.headers();
		this.responseHeader(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
		//FIXME when keep alive is supported
		this.responseHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
	}

	@Override
	public HttpOutbound addCookie(Cookie cookie) {
		if (statusAndHeadersSent == 0) {
			this.headers.add(HttpHeaderNames.COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	/**
	 * Accumulate a Request Header using the given name and value, appending ";" for each
	 * new value
	 * @return this
	 */
	@Override
	public HttpOutbound addHeader(CharSequence name, CharSequence value) {
		if (statusAndHeadersSent == 0) {
			this.headers.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpChannel addResponseCookie(Cookie cookie) {
		if (statusAndHeadersSent == 0) {
			this.responseHeaders.add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	/**
	 * Accumulate a response HTTP header for the given key name, appending ";" for each
	 * new value
	 * @param name the HTTP response header name
	 * @param value the HTTP response header value
	 * @return this
	 */
	@Override
	public HttpChannel addResponseHeader(CharSequence name, CharSequence value) {
		if (statusAndHeadersSent == 0) {
			this.responseHeaders.add(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public String toString() {
		if(isWebsocket()){
			return "ws:" + uri();
		}

		return method().name() + ":" + uri();
	}

	// REQUEST contract

	/**
	 * Register an HTTP request header
	 * @param name Header name
	 * @param value Header content
	 * @return this
	 */
	@Override
	public HttpOutbound header(CharSequence name, CharSequence value) {
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
		return this.headers;
	}

	@Override
	public Flux<Object> receiveObject() {
		// Handle the 'Expect: 100-continue' header if necessary.
		// TODO: Respond with 413 Request Entity Too Large
		//   and discard the traffic or close the connection.
		//       No need to notify the upstream handlers - just log.
		//       If decoding a response, just throw an error.
		if (HttpUtil.is100ContinueExpected(nettyRequest)) {
			return MonoChannelFuture.from(() -> delegate().writeAndFlush(CONTINUE))
			                        .thenMany(super.receiveObject());
		}
		else {
			return super.receiveObject();
		}
	}

	@Override
	public boolean isKeepAlive() {
		return HttpUtil.isKeepAlive(nettyRequest);
	}

	@Override
	public boolean isWebsocket() {
		String isWebsocket = headers.get(HttpHeaderNames.UPGRADE);
		return isWebsocket != null && isWebsocket.toLowerCase().equals("websocket");
	}

	@Override
	public HttpOutbound keepAlive(boolean keepAlive) {
		HttpUtil.setKeepAlive(nettyRequest, keepAlive);
		return this;
	}

	@Override
	public HttpMethod method() {
		return nettyRequest.method();
	}

	// RESPONSE contract

	/**
	 * Read URI param from the given key
	 * @param key matching key
	 * @return the resolved parameter for the given key name
	 */
	@Override
	public Object param(CharSequence key) {
		Map<String, Object> params = null;
		if (paramsResolver != null) {
			params = this.paramsResolver.apply(uri());
		}
		return null != params ? params.get(key) : null;
	}

	/**
	 * Read all URI params
	 * @return a map of resolved parameters against their matching key name
	 */
	@Override
	public Map<String, Object> params() {
		return null != paramsResolver ? paramsResolver.apply(uri()) : null;
	}

	/**
	 *
	 * @param headerResolver
	 */
	@Override
	public HttpChannel paramsResolver(
			Function<? super String, Map<String, Object>> headerResolver) {
		this.paramsResolver = headerResolver;
		return this;
	}

	@Override
	public HttpVersion version() {
		HttpVersion version = this.nettyRequest.protocolVersion();
		if (version.equals(HttpVersion.HTTP_1_0)) {
			return HttpVersion.HTTP_1_0;
		} else if (version.equals(HttpVersion.HTTP_1_1)) {
			return HttpVersion.HTTP_1_1;
		}
		throw new IllegalStateException(version.protocolName() + " not supported");
	}

	@Override
	public HttpChannel responseTransfer(boolean chunked) {
		HttpUtil.setTransferEncodingChunked(nettyResponse, chunked);
		return this;
	}

	@Override
	public HttpOutbound removeTransferEncodingChunked() {
		HttpUtil.setTransferEncodingChunked(nettyRequest, false);
		return this;
	}

	@Override
	public Map<CharSequence, Set<Cookie>> cookies() {
		return null;
	}

	/**
	 * Define the response HTTP header for the given key
	 * @param name the HTTP response header key to override
	 * @param value the HTTP response header content
	 * @return this
	 */
	@Override
	public HttpChannel responseHeader(CharSequence name, CharSequence value) {
		if (statusAndHeadersSent == 0) {
			this.responseHeaders.set(name, value);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	@Override
	public HttpHeaders responseHeaders() {
		return this.responseHeaders;
	}

	@Override
	public HttpResponseStatus status() {
		return HttpResponseStatus.valueOf(this.nettyResponse.status()
		                                                    .code());
	}

	/**
	 * Set the response status to an outgoing response
	 * @param status the status to define
	 * @return this
	 */
	@Override
	public HttpChannel status(HttpResponseStatus status) {
		if (statusAndHeadersSent == 0) {
			this.nettyResponse.setStatus(status);
		}
		else {
			throw new IllegalStateException("Status and headers already sent");
		}
		return this;
	}

	/**
	 * @return the Transfer setting SSE for this http connection (e.g. event-stream)
	 */
	@Override
	public HttpChannel sse() {
		header(HttpHeaderNames.CONTENT_TYPE, EVENT_STREAM);
		return this;
	}

	@Override
	public void subscribe(final Subscriber<? super Void> subscriber) {
		//input.
	}

	@Override
	public Mono<Void> sendFile(File file, long position, long count) {
		Supplier<Mono<Void>> writeFile = () ->
				MonoChannelFuture.from(delegate()
				                                .writeAndFlush(new DefaultFileRegion(file, position, count)));
		return sendHeaders().then(writeFile);
	}

	@Override
	public String uri() {
		return this.nettyRequest.uri();
	}

	@Override
	public Mono<Void> sendObject(final Publisher<?> source) {
		return new MonoOutboundWrite(source);
	}

	@Override
	public Mono<Void> sendString(Publisher<? extends String> dataStream, Charset charset) {

		if (isWebsocket()){
			return new MonoOutboundWrite(Flux.from(dataStream)
			                                 .map(TextWebSocketFrame::new));
		}

		return send(Flux.from(dataStream)
		                .map(s -> delegate().alloc().buffer().writeBytes(s.getBytes(charset))));
	}

	@Override
	public Mono<Void> send(Publisher<? extends ByteBuf> dataStream) {
		return new MonoOutboundWrite(dataStream);
	}

	/**
	 * Flush the headers if not sent. Might be useful for the case
	 * @return Stream to signal error or successful write to the client
	 */
	@Override
	public Mono<Void> sendHeaders() {
		if (statusAndHeadersSent == 0) {
			return new MonoOnlyHeaderWrite();
		}
		else {
			return Mono.empty();
		}
	}

	@Override
	public HttpOutbound flushEach() {
		super.flushEach();
		return this;
	}

	// RESPONSE contract

	protected abstract void doSubscribeHeaders(Subscriber<? super Void> s);

	final boolean markHeadersAsFlushed() {
		return HEADERS_SENT.compareAndSet(this, 0, 1);
	}

	HttpRequest getNettyRequest() {
		return nettyRequest;
	}

	HttpResponse getNettyResponse() {
		return nettyResponse;
	}

	void setNettyResponse(HttpResponse nettyResponse) {
		this.nettyResponse = nettyResponse;
		this.responseHeaders = nettyResponse.headers();
	}
	protected final static AtomicIntegerFieldUpdater<NettyHttpChannel> HEADERS_SENT =
			AtomicIntegerFieldUpdater.newUpdater(NettyHttpChannel.class, "statusAndHeadersSent");
	static final           FullHttpResponse                            CONTINUE     =
			new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER);

	final class MonoOutboundWrite extends Mono<Void> implements Receiver, Loopback {

		final Publisher<?> source;

		public MonoOutboundWrite(Publisher<?> source) {
			this.source = source;
		}

		@Override
		public Object connectedInput() {
			return NettyHttpChannel.this;
		}

		@Override
		public Object connectedOutput() {
			return NettyHttpChannel.this;
		}

		@Override
		public void subscribe(final Subscriber<? super Void> s) {
			if(markHeadersAsFlushed()){
				doSubscribeHeaders(new HttpOutboundSubscriber(s));
			}
			else{
				emitWriter(source, s);
			}
		}

		@Override
		public Object upstream() {
			return source;
		}

		final class HttpOutboundSubscriber implements Subscriber<Void>, Receiver, Producer {

			final Subscriber<? super Void> s;
			Subscription subscription;

			public HttpOutboundSubscriber(Subscriber<? super Void> s) {
				this.s = s;
			}

			@Override
			public Subscriber downstream() {
				return s;
			}

			@Override
			public void onComplete() {
				this.subscription = null;
				emitWriter(source, s);
			}

			@Override
			public void onError(Throwable t) {
				this.subscription = null;
				s.onError(t);
			}

			@Override
			public void onNext(Void aVoid) {
				//Ignore
			}

			@Override
			public void onSubscribe(Subscription sub) {
				this.subscription = sub;
				sub.request(Long.MAX_VALUE);
			}

			@Override
			public Object upstream() {
				return subscription;
			}
		}
	}

	final class MonoOnlyHeaderWrite extends Mono<Void> implements Loopback {

		@Override
		public Object connectedInput() {
			return NettyHttpChannel.this;
		}

		@Override
		public Object connectedOutput() {
			return NettyHttpChannel.this;
		}

		@Override
		public void subscribe(Subscriber<? super Void> s) {
			if (markHeadersAsFlushed()) {
				doSubscribeHeaders(s);
			}
			else {
				Operators.error(s, new IllegalStateException("Status and headers already sent"));
			}
		}
	}
}
