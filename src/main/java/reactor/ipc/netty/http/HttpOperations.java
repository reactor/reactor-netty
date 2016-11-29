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
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiFunction;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Loopback;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ContextHandler;

/**
 * An HTTP ready {@link ChannelOperations} with state management for status and headers
 * (first HTTP response packet).
 *
 * @author Stephane Maldini
 */
public abstract class HttpOperations<INBOUND extends HttpInbound, OUTBOUND extends HttpOutbound>
		extends ChannelOperations<INBOUND, OUTBOUND> implements HttpInbound, HttpOutbound {


	volatile int statusAndHeadersSent = 0;

	protected HttpOperations(Channel ioChannel, HttpOperations<INBOUND, OUTBOUND> replaced) {
		super(ioChannel, replaced);
		this.statusAndHeadersSent = replaced.statusAndHeadersSent;
	}

	protected HttpOperations(Channel ioChannel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		super(ioChannel, handler, context);
	}

	@Override
	public final boolean hasSentHeaders() {
		return statusAndHeadersSent == 1;
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
		if (!hasSentHeaders()) {
			if (!HttpUtil.isContentLengthSet(outboundHttpMessage()) && !outboundHttpMessage().headers()
			                                                                                 .contains(
					                                                                                 HttpHeaderNames.TRANSFER_ENCODING)) {
				HttpUtil.setTransferEncodingChunked(outboundHttpMessage(), true);
			}
			return new MonoHttpSendWithHeaders(dataStream);
		}
		return super.send(dataStream);
	}

	@Override
	public final Mono<Void> sendAggregate(Publisher<? extends ByteBuf> source) {
		ByteBufAllocator alloc = channel().alloc();
		return Flux.from(source)
		           .doOnNext(ByteBuf::retain)
		           .collect(alloc::buffer, ByteBuf::writeBytes)
		           .then(agg -> {
			           if (!hasSentHeaders() && !HttpUtil.isTransferEncodingChunked(
					           outboundHttpMessage()) && !HttpUtil.isContentLengthSet(
					           outboundHttpMessage())) {
				           outboundHttpMessage().headers()
				                                .setInt(HttpHeaderNames.CONTENT_LENGTH,
						                                agg.readableBytes());
			           }
			           return sendHeaders().then(super.send(Mono.just(agg)));
		           });
	}

	@Override
	public final Mono<Void> sendHeaders() {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (!hasSentHeaders()) {
			return new MonoSendHeaders();
		}
		else {
			return Mono.empty();
		}
	}

	@Override
	public final Mono<Void> sendFile(Path file, long position, long count) {
		Objects.requireNonNull(file);

		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}

		if (!hasSentHeaders()) {
			if (!HttpUtil.isTransferEncodingChunked(outboundHttpMessage()) && !HttpUtil.isContentLengthSet(
					outboundHttpMessage()) && count < Integer.MAX_VALUE) {
				outboundHttpMessage().headers()
				                     .setInt(HttpHeaderNames.CONTENT_LENGTH, (int) count);
			}
			else if (!HttpUtil.isContentLengthSet(outboundHttpMessage())) {
				outboundHttpMessage().headers()
				                     .remove(HttpHeaderNames.CONTENT_LENGTH)
				                     .remove(HttpHeaderNames.TRANSFER_ENCODING);
				HttpUtil.setTransferEncodingChunked(outboundHttpMessage(), true);
			}
			return sendHeaders().then(() -> super.sendFile(file, position, count));
		}
		return super.sendFile(file, position, count);
	}

	@Override
	public final Mono<Void> sendObject(final Publisher<?> source) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (hasSentHeaders()) {
			return super.sendObject(source);
		}
		return new MonoHttpSendWithHeaders(source);
	}

	@Override
	public final Mono<Void> sendString(Publisher<? extends String> dataStream,
			Charset charset) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		if (isWebsocket()) {
			return sendObject(Flux.from(dataStream)
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
	 * Mark the headers sent
	 *
	 * @return true if marked for the first time
	 */
	protected final boolean markHeadersAsSent() {
		return HEADERS_SENT.compareAndSet(this, 0, 1);
	}

	/**
	 * Outbound Netty HttpMessage
	 *
	 * @return Outbound Netty HttpMessage
	 */
	protected abstract HttpMessage outboundHttpMessage();

	/**
	 * Write and send the initial {@link io.netty.handler.codec.http.HttpRequest}
	 * mssage that will commit the status and response headers.
	 *
	 * @param s the {@link Subscriber} callback completing on confirmed commit or
	 * failing with root cause.
	 */
	final void sendHeadersAndSubscribe(Subscriber<? super Void> s) {
		ChannelFutureMono.from(channel().writeAndFlush(outboundHttpMessage()))
		                 .subscribe(s);
	}


	final static AtomicIntegerFieldUpdater<HttpOperations> HEADERS_SENT =
			AtomicIntegerFieldUpdater.newUpdater(HttpOperations.class,
					"statusAndHeadersSent");

	final class MonoHttpSendWithHeaders
			extends Mono<Void> implements Receiver, Loopback {

		final Publisher<?> source;

		public MonoHttpSendWithHeaders(Publisher<?> source) {
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
				sendHeadersAndSubscribe(new HttpSendSubscriber(s));
			}
			else {
				onOuboundSend(source, s);
			}
		}

		@Override
		public Object upstream() {
			return source;
		}

		final class HttpSendSubscriber implements Subscriber<Void>, Receiver, Producer {

			final Subscriber<? super Void> s;
			Subscription subscription;

			public HttpSendSubscriber(Subscriber<? super Void> s) {
				this.s = s;
			}

			@Override
			public Subscriber downstream() {
				return s;
			}

			@Override
			public void onComplete() {
				this.subscription = null;
				onOuboundSend(source, s);
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

	final class MonoSendHeaders extends Mono<Void> implements Loopback {

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
			if(markHeadersAsSent()) {
				sendHeadersAndSubscribe(s);
			}
			else{
				Operators.complete(s);
			}
		}
	}

}
