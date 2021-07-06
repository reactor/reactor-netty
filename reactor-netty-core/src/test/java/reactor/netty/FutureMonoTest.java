/*
 * Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Operators;
import reactor.netty.channel.AbortedException;
import reactor.test.StepVerifier;
import reactor.test.util.RaceTestUtils;
import reactor.util.annotation.Nullable;

import java.lang.reflect.Field;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class FutureMonoTest {

	@Test
	void testImmediateFutureMonoImmediate() {
		ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
		Future<Void> promise = eventExecutor.newFailedFuture(new ClosedChannelException());

		StepVerifier.create(FutureMono.from(promise))
		            .expectError(AbortedException.class)
		            .verify(Duration.ofSeconds(30));
	}

	// return value of setFailure not needed
	@SuppressWarnings("FutureReturnValueIgnored")
	@Test
	void testImmediateFutureMonoLater() {
		ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
		Promise<Void> promise = eventExecutor.newPromise();

		StepVerifier.create(FutureMono.from(promise))
		            .expectSubscription()
		            .then(() -> promise.setFailure(new ClosedChannelException()))
		            .expectError(AbortedException.class)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testDeferredFutureMonoImmediate() {
		ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
		Supplier<Future<Void>> promiseSupplier = () -> eventExecutor.newFailedFuture(new ClosedChannelException());

		StepVerifier.create(FutureMono.deferFuture(promiseSupplier))
		            .expectError(AbortedException.class)
		            .verify(Duration.ofSeconds(30));
	}

	// return value of setFailure not needed
	@SuppressWarnings("FutureReturnValueIgnored")
	@Test
	void testDeferredFutureMonoLater() {
		ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
		Promise<Void> promise = eventExecutor.newPromise();
		Supplier<Promise<Void>> promiseSupplier = () -> promise;

		StepVerifier.create(FutureMono.deferFuture(promiseSupplier))
		            .expectSubscription()
		            .then(() -> promise.setFailure(new ClosedChannelException()))
		            .expectError(AbortedException.class)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void raceTestImmediateFutureMonoWithSuccess() {
		for (int i = 0; i < 1000; i++) {
			final TestSubscriber subscriber = new TestSubscriber();
			final ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
			final Promise<Void> promise = eventExecutor.newPromise();

			RaceTestUtils.race(() -> FutureMono.from(promise)
			                                   .subscribe(subscriber),
					subscriber::cancel,
					() -> promise.setSuccess(null));

			assertThat(resolveListeners(promise)).isNullOrEmpty();
			assertThat(subscriber.operations).first()
			                                 .isEqualTo(TestSubscriber.Operation.ON_SUBSCRIBE);
		}
	}

	@Test
	void raceTestDeferredFutureMonoWithSuccess() {
		for (int i = 0; i < 1000; i++) {
			final TestSubscriber subscriber = new TestSubscriber();
			final ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
			final Promise<Void> promise = eventExecutor.newPromise();
			final Supplier<Promise<Void>> promiseSupplier = () -> promise;

			RaceTestUtils.race(() -> FutureMono.deferFuture(promiseSupplier)
			                                   .subscribe(subscriber),
					subscriber::cancel,
					() -> promise.setSuccess(null));

			assertThat(resolveListeners(promise)).isNullOrEmpty();
			assertThat(subscriber.operations).first()
			                                 .isEqualTo(TestSubscriber.Operation.ON_SUBSCRIBE);
		}
	}

	@Test
	void raceTestImmediateFutureMono() {
		for (int i = 0; i < 1000; i++) {
			final TestSubscriber subscriber = new TestSubscriber();
			final ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
			final Promise<Void> promise = eventExecutor.newPromise();

			RaceTestUtils.race(() -> FutureMono.from(promise)
			                                   .subscribe(subscriber), subscriber::cancel);

			assertThat(resolveListeners(promise)).isNullOrEmpty();
			assertThat(subscriber.operations).first()
			                                 .isEqualTo(TestSubscriber.Operation.ON_SUBSCRIBE);
		}
	}

	@Test
	void raceTestDeferredFutureMono() {
		for (int i = 0; i < 1000; i++) {
			final TestSubscriber subscriber = new TestSubscriber();
			final ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
			final Promise<Void> promise = eventExecutor.newPromise();
			final Supplier<Promise<Void>> promiseSupplier = () -> promise;

			RaceTestUtils.race(() -> FutureMono.deferFuture(promiseSupplier)
			                                   .subscribe(subscriber), subscriber::cancel);

			assertThat(resolveListeners(promise)).isNullOrEmpty();
			assertThat(subscriber.operations).first()
			                                 .isEqualTo(TestSubscriber.Operation.ON_SUBSCRIBE);
		}
	}

	@Nullable
	@SuppressWarnings("unchecked")
	static GenericFutureListener<? extends Future<?>>[] resolveListeners(Promise<Void> promise) {
		try {
			final Field listeners = DefaultPromise.class.getDeclaredField("listeners");
			final Class<?> aClass = Class.forName("io.netty.util.concurrent.DefaultFutureListeners");
			final Field listeners1 = aClass.getDeclaredField("listeners");

			listeners.setAccessible(true);
			listeners1.setAccessible(true);

			final Object objListener = listeners.get(promise);
			if (objListener == null) {
				return null;
			}
			return (GenericFutureListener<? extends Future<?>>[]) listeners1.get(objListener);
		}
		catch (NoSuchFieldException | ClassNotFoundException | IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	static class TestSubscriber implements CoreSubscriber<Void> {

		enum Operation {
			ON_ERROR, ON_COMPLETE, ON_SUBSCRIBE
		}

		volatile Subscription                                                  s;
		static final AtomicReferenceFieldUpdater<TestSubscriber, Subscription> S =
				AtomicReferenceFieldUpdater.newUpdater(TestSubscriber.class, Subscription.class, "s");

		final Queue<Operation> operations = new ArrayBlockingQueue<>(3);

		@Override
		public void onSubscribe(Subscription s) {
			Operators.setOnce(S, this, s);
			operations.add(Operation.ON_SUBSCRIBE);
		}

		@Override
		public void onNext(Void unused) {

		}

		@Override
		public void onError(Throwable t) {
			operations.add(Operation.ON_ERROR);
		}

		@Override
		public void onComplete() {
			operations.add(Operation.ON_COMPLETE);
		}

		void cancel() {
			Operators.terminate(S, this);
		}
	}
}
