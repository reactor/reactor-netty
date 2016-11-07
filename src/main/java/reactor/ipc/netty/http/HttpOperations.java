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
import io.netty.channel.Channel;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Loopback;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.NettyState;
import reactor.ipc.netty.channel.NettyOperations;

/**
 * An HTTP ready {@link NettyOperations} with state management for status and headers
 * (first HTTP response packet).
 *
 * @author Stephane Maldini
 */
public abstract class HttpOperations<INBOUND extends HttpInbound, OUTBOUND extends HttpOutbound>
		extends NettyOperations<INBOUND, OUTBOUND> implements HttpInbound, HttpOutbound {


	volatile int statusAndHeadersSent = 0;

	protected HttpOperations(Channel ioChannel, HttpOperations<INBOUND, OUTBOUND> replaced) {
		super(ioChannel, replaced);
		this.statusAndHeadersSent = replaced.statusAndHeadersSent;
	}

	protected HttpOperations(Channel ioChannel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> clientSink) {
		super(ioChannel, handler, clientSink);

	}

	@Override
	public HttpOutbound flushEach() {
		super.flushEach();
		return this;
	}

	/**
	 * Return  true if headers and status have been sent to the client
	 *
	 * @return true if headers and status have been sent to the client
	 */
	public boolean hasSentHeaders() {
		return statusAndHeadersSent == 1;
	}

	@Override
	public boolean isWebsocket() {
		return false;
	}

	/**
	 * Mark the headers sent
	 *
	 * @return true if marked for the first time
	 */
	public final boolean markHeadersAsSent() {
		return HEADERS_SENT.compareAndSet(this, 0, 1);
	}

	@Override
	public Mono<Void> send(Publisher<? extends ByteBuf> dataStream) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		return new MonoHttpWithHeadersWriter(dataStream);
	}

	@Override
	public Mono<Void> sendFile(File file, long position, long count) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		Supplier<Mono<Void>> writeFile =
				() -> ChannelFutureMono.from(channel().writeAndFlush(new DefaultFileRegion(
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
		if (markHeadersAsSent()) {
			return new MonoHeadersSender();
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
		return new MonoHttpWithHeadersWriter(source);
	}

	@Override
	public Mono<Void> sendString(Publisher<? extends String> dataStream,
			Charset charset) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (isWebsocket()) {
			return new MonoHttpWithHeadersWriter(Flux.from(dataStream)
			                                         .map(TextWebSocketFrame::new));
		}

		return send(Flux.from(dataStream)
		                .map(s -> channel().alloc()
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

	/**
	 * Write and send the initial {@link io.netty.handler.codec.http.HttpRequest}
	 * mssage that will commit the status and response headers.
	 *
	 * @param s the {@link Subscriber} callback completing on confirmed commit or
	 * failing with root cause.
	 */
	protected abstract void sendHeadersAndSubscribe(Subscriber<? super Void> s);
	final static AtomicIntegerFieldUpdater<HttpOperations> HEADERS_SENT =
			AtomicIntegerFieldUpdater.newUpdater(HttpOperations.class,
					"statusAndHeadersSent");

	final class MonoHttpWithHeadersWriter
			extends Mono<Void> implements Receiver, Loopback {

		final Publisher<?> source;

		public MonoHttpWithHeadersWriter(Publisher<?> source) {
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
			if (markHeadersAsSent()) {
				sendHeadersAndSubscribe(new HttpWriterSubscriber(s));
			}
			else {
				emitWriter(source, s);
			}
		}

		@Override
		public Object upstream() {
			return source;
		}

		final class HttpWriterSubscriber implements Subscriber<Void>, Receiver, Producer {

			final Subscriber<? super Void> s;
			Subscription subscription;

			public HttpWriterSubscriber(Subscriber<? super Void> s) {
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

	final class MonoHeadersSender extends Mono<Void> implements Loopback {

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
			sendHeadersAndSubscribe(s);
		}
	}
}
