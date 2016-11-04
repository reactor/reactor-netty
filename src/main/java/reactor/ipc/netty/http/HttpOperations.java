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
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
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
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.NettyState;
import reactor.ipc.netty.channel.NettyOperations;

/**
 * @author Stephane Maldini
 */
abstract class HttpOperations<INBOUND extends HttpInbound, OUTBOUND extends HttpOutbound>
		extends NettyOperations<INBOUND, OUTBOUND> implements HttpInbound, HttpOutbound {


	volatile int statusAndHeadersSent = 0;

	HttpOperations(Channel ioChannel, HttpOperations<INBOUND, OUTBOUND> replaced) {
		super(ioChannel, replaced);
		this.statusAndHeadersSent = replaced.statusAndHeadersSent;
	}

	HttpOperations(Channel ioChannel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> clientSink) {
		super(ioChannel, handler, clientSink);

	}

	@Override
	public HttpOutbound flushEach() {
		super.flushEach();
		return this;
	}

	@Override
	public boolean isWebsocket() {
		return false;
	}

	@Override
	public Mono<Void> send(Publisher<? extends ByteBuf> dataStream) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		return new MonoOutboundWrite(dataStream);
	}

	@Override
	public Mono<Void> sendFile(File file, long position, long count) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		Supplier<Mono<Void>> writeFile =
				() -> ChannelFutureMono.from(delegate().writeAndFlush(new DefaultFileRegion(
						file,
						position,
						count)));
		return sendHeaders().then(writeFile);
	}

	/**
	 * Flush the headers if not sent. Might be useful for the case
	 *
	 * @return Stream to signal error or successful write to the client
	 */
	@Override
	public Mono<Void> sendHeaders() {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (markHeadersAsFlushed()) {
			return new MonoOnlyHeaderWrite();
		}
		else {
			return Mono.empty();
		}
	}

	@Override
	public Mono<Void> sendObject(final Publisher<?> source) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		return new MonoOutboundWrite(source);
	}

	@Override
	public Mono<Void> sendString(Publisher<? extends String> dataStream,
			Charset charset) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (isWebsocket()) {
			return new MonoOutboundWrite(Flux.from(dataStream)
			                                 .map(TextWebSocketFrame::new));
		}

		return send(Flux.from(dataStream)
		                .map(s -> delegate().alloc()
		                                    .buffer()
		                                    .writeBytes(s.getBytes(charset))));
	}

	@Override
	public String toString() {
		if (isWebsocket()) {
			return "ws:" + uri();
		}

		return method().name() + ":" + uri();
	}


	protected abstract void doSubscribeHeaders(Subscriber<? super Void> s);

	final boolean markHeadersAsFlushed() {
		return HEADERS_SENT.compareAndSet(this, 0, 1);
	}

	final static AtomicIntegerFieldUpdater<HttpOperations> HEADERS_SENT =
			AtomicIntegerFieldUpdater.newUpdater(HttpOperations.class,
					"statusAndHeadersSent");
	final static AsciiString                               EVENT_STREAM =
			new AsciiString("text/event-stream");
	final static FullHttpResponse                          CONTINUE     =
			new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.CONTINUE,
					Unpooled.EMPTY_BUFFER);

	final class MonoOutboundWrite extends Mono<Void> implements Receiver, Loopback {

		final Publisher<?> source;

		public MonoOutboundWrite(Publisher<?> source) {
			this.source = source;
		}

		@Override
		public Object connectedInput() {
			return HttpOperations.this;
		}

		@Override
		public Object connectedOutput() {
			return HttpOperations.this;
		}

		@Override
		public void subscribe(final Subscriber<? super Void> s) {
			if (markHeadersAsFlushed()) {
				doSubscribeHeaders(new HttpOutboundSubscriber(s));
			}
			else {
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
			return HttpOperations.this;
		}

		@Override
		public Object connectedOutput() {
			return HttpOperations.this;
		}

		@Override
		public void subscribe(Subscriber<? super Void> s) {
			doSubscribeHeaders(s);
		}
	}
}
