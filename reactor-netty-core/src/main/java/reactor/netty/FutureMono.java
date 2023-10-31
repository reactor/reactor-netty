/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.function.Supplier;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.netty.channel.AbortedException;
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
		Objects.requireNonNull(future, "future");
		if (future.isDone()) {
			if (!future.isSuccess()) {
				return Mono.error(FutureSubscription.wrapError(future.cause()));
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

	static final class ImmediateFutureMono<F extends Future<Void>> extends FutureMono {

		final F future;

		ImmediateFutureMono(F future) {
			this.future = Objects.requireNonNull(future, "future");
		}

		@Override
		public void subscribe(final CoreSubscriber<? super Void> s) {
			doSubscribe(s, future);
		}
	}

	static final class DeferredFutureMono<F extends Future<Void>> extends FutureMono {

		final Supplier<F> deferredFuture;

		DeferredFutureMono(Supplier<F> deferredFuture) {
			this.deferredFuture =
					Objects.requireNonNull(deferredFuture, "deferredFuture");
		}

		@Override
		public void subscribe(CoreSubscriber<? super Void> s) {
			F f;
			try {
				f = deferredFuture.get();
			}
			catch (Throwable t) {
				Operators.error(s, t);
				return;
			}

			if (f == null) {
				Operators.error(s,
						Operators.onOperatorError(new NullPointerException(
								"Deferred supplied null"), s.currentContext()));
				return;
			}

			doSubscribe(s, f);
		}
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	static <F extends Future<Void>> void doSubscribe(CoreSubscriber<? super Void> s, F future) {
		if (future.isDone()) {
			if (future.isSuccess()) {
				Operators.complete(s);
			}
			else {
				Operators.error(s, FutureSubscription.wrapError(future.cause()));
			}
			return;
		}

		FutureSubscription<F> fs = new FutureSubscription<>(future, s);
		// propagate subscription before adding listener to avoid any race between finishing future and onSubscribe
		// is called
		s.onSubscribe(fs);

		// check if subscription was not cancelled immediately.
		if (fs.cancelled) {
			// if so do nothing anymore
			return;
		}

		// add listener to the future to propagate on complete when future is done
		// addListener likely to be thread safe method
		// Returned value is deliberately ignored
		future.addListener(fs);

		// check once again if is cancelled to see if we need to removeListener in case addListener racing with
		// subscription.cancel (which should remove listener)
		if (fs.cancelled) {
			// Returned value is deliberately ignored
			future.removeListener(fs);
		}
	}

	static final class FutureSubscription<F extends Future<Void>>
			implements GenericFutureListener<F>, Subscription, Supplier<Context> {

		final CoreSubscriber<? super Void> s;

		final F                            future;

		boolean cancelled;

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
			// cancel is not thread safe since we assume that removeListener is thread-safe. That said if we have
			// concurrent addListener and removeListener and if addListener is after removeListener, the other Thread
			// after execution addListener should see changes happened before removeListener. Thus, it should see
			// cancelled flag set to true and should cleanup added handler
			this.cancelled = true;
			// Returned value is deliberately ignored
			future.removeListener(this);
		}

		@Override
		public void operationComplete(F future) {
			if (!future.isSuccess()) {
				s.onError(wrapError(future.cause()));
			}
			else {
				s.onComplete();
			}
		}

		private static Throwable wrapError(Throwable error) {
			if (error instanceof ClosedChannelException) {
				return new AbortedException(error);
			}
			else {
				return error;
			}
		}
	}
}
