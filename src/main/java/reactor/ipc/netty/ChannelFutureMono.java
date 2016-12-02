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
package reactor.ipc.netty;

import java.util.Objects;
import java.util.function.Supplier;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;

/**
 * Convert Netty Future into void {@link Mono}.
 *
 * @param <T> the type of the future result
 * @param <F> the future type
 * @author Stephane Maldini
 */
public final class ChannelFutureMono<T, F extends Future<T>> extends Mono<T> {

	/**
	 * Convert a {@link Future} into {@link Mono}. {@link Mono#subscribe(Subscriber)}
	 * will bridge to {@link Future#addListener(GenericFutureListener)}.
	 *
	 * @param future the future to convert from
	 * @param <T> the type of the future result
	 * @param <F> the future type
	 *
	 * @return A {@link Mono} forwarding {@link Future} success or failure
	 */
	public static <T, F extends Future<T>> Mono<T> from(F future){
		return new ChannelFutureMono<>(future);
	}

	/**
	 * Convert a supplied {@link Future} for each subscriber into {@link Mono}.
	 * {@link Mono#subscribe(Subscriber)}
	 * will bridge to {@link Future#addListener(GenericFutureListener)}.
	 *
	 * @param deferredFuture the future to evaluate and convert from
	 * @param <T> the type of the future result
	 * @param <F> the future type
	 *
	 * @return A {@link Mono} forwarding {@link Future} success or failure
	 */
	public static <T, F extends Future<T>> Mono<T> deferFuture(Supplier<F> deferredFuture){
		Objects.requireNonNull(deferredFuture, "deferredFuture");
		return Mono.defer(() -> new ChannelFutureMono<>(deferredFuture.get()));
	}

	final F future;

	ChannelFutureMono(F future) {
		this.future = Objects.requireNonNull(future, "future");
	}

	@Override
	public final void subscribe(final Subscriber<? super T> s) {
		FutureSubscription<T, F> fs = new FutureSubscription<>(future, s);
		s.onSubscribe(fs);
		future.addListener(fs);
	}

	final static class FutureSubscription<T, F extends Future<T>> implements
	                                                GenericFutureListener<F>,
	                                                Subscription {
		final Subscriber<? super T> s;
		final F future;

		FutureSubscription(F future, Subscriber<? super T> s) {
			this.s = s;
			this.future = future;
		}

		@Override
		public void request(long n) {
			//noop
		}

		@Override
		public void cancel() {
			future.removeListener(this);
		}

		@Override
		@SuppressWarnings("unchecked")
		public void operationComplete(F future) throws Exception {
			if (!future.isSuccess()) {
				s.onError(future.cause());
			}
			else {
				s.onComplete();
			}
		}
	}
}
