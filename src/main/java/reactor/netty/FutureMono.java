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
package reactor.netty;

import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
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

	final static class ImmediateFutureMono<F extends Future<Void>> extends FutureMono {

		final F future;

		ImmediateFutureMono(F future) {
			this.future = Objects.requireNonNull(future, "future");
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
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
			// Returned value is deliberately ignored
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
		@SuppressWarnings("FutureReturnValueIgnored")
		public void subscribe(CoreSubscriber<? super Void> s) {
			F f;
			try {
				f = deferredFuture.get();
			}
			catch(Throwable t) {
				Operators.error(s, t);
				return;
			}

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
			// Returned value is deliberately ignored
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
		@SuppressWarnings("FutureReturnValueIgnored")
		public void cancel() {
			// Returned value is deliberately ignored
			future.removeListener(this);
		}

		@Override
		@SuppressWarnings("unchecked")
		public void operationComplete(F future) {
			if (!future.isSuccess()) {//Avoid singleton
				if (future.cause() instanceof ClosedChannelException) {
					//Update with a common aborted exception?
					s.onError(ReactorNetty.wrapException(future.cause()));
				}
				else {
					s.onError(future.cause());
				}
			}
			else {
				s.onComplete();
			}
		}

	}
}
