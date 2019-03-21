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
package reactor.ipc.netty;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoOperator;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * Convert Netty Future into void {@link Mono}.
 *
 * @author Stephane Maldini
 */
public abstract class FutureMono extends Mono<Void> {

	/**
	 * Convert a {@link Future} into {@link Mono}. {@link Mono#subscribe(Subscriber)}
	 * will bridge to {@link Future#addListener(GenericFutureListener)}.
	 *
	 * @param future the future to convert from
	 * @param <F> the future type
	 *
	 * @return A {@link Mono} forwarding {@link Future} success or failure
	 */
	public static <F extends Future<Void>> Mono<Void> from(F future) {
		if(future.isDone()){
			if(!future.isSuccess()){
				return Mono.error(future.cause());
			}
			return Mono.empty();
		}
		return new ImmediateFutureMono<>(future);
	}

	/**
	 * Convert a supplied {@link Future} for each subscriber into {@link Mono}.
	 * {@link Mono#subscribe(Subscriber)}
	 * will bridge to {@link Future#addListener(GenericFutureListener)}.
	 *
	 * @param deferredFuture the future to evaluate and convert from
	 * @param <F> the future type
	 *
	 * @return A {@link Mono} forwarding {@link Future} success or failure
	 */
	public static <F extends Future<Void>> Mono<Void> deferFuture(Supplier<F> deferredFuture) {
		return new DeferredFutureMono<>(deferredFuture);
	}

	/**
	 * Write the passed {@link Publisher} and return a disposable {@link Mono}.
	 * <p>
	 * In addition, current method allows interaction with downstream context, so it
	 * may be transferred to implicitly connected upstream
	 * <p>
	 * Example:
	 * <p>
	 * <pre><code>
	 *   Flux&lt;String&gt; dataStream = Flux.just("a", "b", "c");
	 *   FutureMono.deferFutureWithContext((subscriberContext) ->
	 *  	context().channel()
	 * 		 .writeAndFlush(PublisherContext.withContext(dataStream, subscriberContext)));
	 * </code></pre>
	 *
	 * @param dataStream the publisher to write
	 *
	 * @return A {@link Mono} forwarding {@link Future} success, failure and cancel
	 */
	public static Mono<Void> disposableWriteAndFlush(Channel channel,
			Publisher<?> dataStream) {
		return new DeferredWriteMono(channel, dataStream);
	}

	/**
	 * Convert a supplied {@link Future} for each subscriber into {@link Mono}.
	 * {@link Mono#subscribe(Subscriber)}
	 * will bridge to {@link Future#addListener(GenericFutureListener)}.
	 *
	 * In addition, current method allows interaction with downstream context, so it
	 * may be transferred to implicitly connected upstream
	 *
	 * Example:
	 *
	 * <pre><code>
	 *   Flux&lt;String&gt; dataStream = Flux.just("a", "b", "c");
	 *   FutureMono.deferFutureWithContext((subscriberContext) ->
     *  	context().channel()
	 * 		 .writeAndFlush(PublisherContext.withContext(dataStream, subscriberContext)));
	 * </code></pre>
	 *
	 * @param deferredFuture the future to evaluate and convert from
	 * @param <F> the future type
	 *
	 * @return A {@link Mono} forwarding {@link Future} success or failure
	 */
	public static <F extends Future<Void>> Mono<Void> deferFutureWithContext(Function<Context, F> deferredFuture) {
		return new DeferredContextFutureMono<>(deferredFuture);
	}

	static <T> Publisher<T> wrapContextAndDispose(Publisher<T> publisher, ChannelFutureSubscription cfs) {
		if (publisher instanceof Callable) {
			return publisher;
		}
		else if (publisher instanceof Flux) {
			return new FluxOperator<T, T>((Flux<T>) publisher) {

				@Override
				public void subscribe(CoreSubscriber<? super T> actual) {
					cfs.ioActual = actual;
					source.subscribe(actual);
				}
			};
		}
		else if (publisher instanceof Mono) {
			return new MonoOperator<T, T>((Mono<T>) publisher) {

				@Override
				public void subscribe(CoreSubscriber<? super T> actual) {
					cfs.ioActual = actual;
					source.subscribe(actual);
				}
			};
		}
		else {
			return publisher;
		}
	}

	final static class ImmediateFutureMono<F extends Future<Void>> extends FutureMono {

		final F future;

		ImmediateFutureMono(F future) {
			this.future = Objects.requireNonNull(future, "future");
		}

		@Override
		public final void subscribe(final CoreSubscriber<? super Void> s) {
			if(future.isDone()){
				if(future.isSuccess()){
					Operators.complete(s);
				}
				else{
					Operators.error(s, future.cause());
				}
				return;
			}

			FutureSubscription<F> fs = new FutureSubscription<>(future, s);
			future.addListener(fs);
			s.onSubscribe(fs);
		}
	}

	final static class DeferredFutureMono<F extends Future<Void>> extends FutureMono {

		final Supplier<F> deferredFuture;

		DeferredFutureMono(Supplier<F> deferredFuture) {
			this.deferredFuture =
					Objects.requireNonNull(deferredFuture, "deferredFuture");
		}

		@Override
		public void subscribe(CoreSubscriber<? super Void> s) {
			F f = deferredFuture.get();

			if (f == null) {
				Operators.error(s,
						Operators.onOperatorError(new NullPointerException(
								"Deferred supplied null"), s.currentContext()));
				return;
			}

			if (f.isDone()) {
				if (f.isSuccess()) {
					Operators.complete(s);
				}
				else {
					Operators.error(s, f.cause());
				}
				return;
			}

			FutureSubscription<F> fs = new FutureSubscription<>(f, s);
			s.onSubscribe(fs);
			f.addListener(fs);
		}
	}

	final static class DeferredContextFutureMono<F extends Future<Void>> extends
	                                                                     FutureMono {

		final Function<Context, F> deferredFuture;

		DeferredContextFutureMono(Function<Context, F> deferredFuture) {
			this.deferredFuture = Objects.requireNonNull(deferredFuture, "deferredFuture");
		}

		@Override
		public void subscribe(CoreSubscriber<? super Void> s) {
			F f = deferredFuture.apply(s.currentContext());

			if (f == null) {
				Operators.error(s,
						Operators.onOperatorError(new NullPointerException("Deferred supplied null"),
								s.currentContext()));
				return;
			}

			if(f.isDone()){
				if(f.isSuccess()){
					Operators.complete(s);
				}
				else{
					Operators.error(s, f.cause());
				}
				return;
			}

			FutureSubscription<F> fs = new FutureSubscription<>(f, s);
			s.onSubscribe(fs);
			f.addListener(fs);
		}
	}



	final static class FutureSubscription<F extends Future<Void>>
			implements GenericFutureListener<F>, Subscription, Supplier<Context> {

		final CoreSubscriber<? super Void> s;

		final F                            future;
		FutureSubscription(F future, CoreSubscriber<? super Void> s) {
			this.s = s;
			this.future = future;
		}

		@Override
		public void request(long n) {
			//noop
		}

		@Override
		public Context get() {
			return s.currentContext();
		}

		@Override
		public void cancel() {
			future.removeListener(this);
		}

		@Override
		@SuppressWarnings("unchecked")
		public void operationComplete(F future) {
			if (!future.isSuccess()) {
				s.onError(future.cause());
			}
			else {
				s.onComplete();
			}
		}

	}

	final static class DeferredWriteMono extends FutureMono {

		final Channel      channel;
		final Publisher<?> dataStream;

		DeferredWriteMono(Channel channel, Publisher<?> dataStream) {
			this.channel = Objects.requireNonNull(channel, "channel");
			this.dataStream = Objects.requireNonNull(dataStream, "dataStream");
		}

		@Override
		public void subscribe(CoreSubscriber<? super Void> s) {
			ChannelFutureSubscription cfs = new ChannelFutureSubscription(channel, s);

			s.onSubscribe(cfs);

			channel.writeAndFlush(wrapContextAndDispose(dataStream, cfs), cfs);
		}
	}

	final static class ChannelFutureSubscription extends DefaultChannelPromise
			implements Subscription, Function<Void, Context> {

		final CoreSubscriber<? super Void> actual;
		CoreSubscriber<?> ioActual;

		ChannelFutureSubscription(Channel channel, CoreSubscriber<? super Void> actual) {
			super(channel, channel.eventLoop());
			this.actual = actual;
		}

		@Override
		public void request(long n) {
			//noop
		}

		@Override
		public Context apply(Void aVoid) {
			return actual.currentContext();
		}

		@Override
		@SuppressWarnings("unchecked")
		public void cancel() {
			if (!executor().inEventLoop()) {
				//must defer to be sure about ioActual field (assigned on event loop)
				executor().execute(this::cancel);
				return;
			}
			CoreSubscriber<?> ioActual = this.ioActual;
			this.ioActual = null;
			if (ioActual instanceof Consumer) {
				((Consumer<ChannelFuture>)ioActual).accept(this);
			}
		}

		@Override
		public boolean trySuccess(Void result) {
			this.ioActual = null;
			boolean r = super.trySuccess(result);
			actual.onComplete();
			return r;
		}

		@Override
		public ChannelPromise setSuccess(Void result) {
			this.ioActual = null;
			super.setSuccess(result);
			actual.onComplete();
			return this;
		}

		@Override
		public boolean tryFailure(Throwable cause) {
			this.ioActual = null;
			boolean r = super.tryFailure(cause);
			actual.onError(cause);
			return r;
		}

		@Override
		public ChannelPromise setFailure(Throwable cause) {
			this.ioActual = null;
			super.setFailure(cause);
			actual.onError(cause);
			return this;
		}
	}
}
