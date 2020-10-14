/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;
import reactor.util.context.Context;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * This test class verifies that a stream's {@link Context} is propagated to
 * {@link ConnectionObserver} in {@link TcpClient#doOnChannelInit}.
 *
 * The test is specifically interested in verifying that the available
 * {@link Context} is propagated to {@link ConnectionObserver}, not really
 * interested in verifying that {@link Context} is propagated from outer stream
 * to the inner stream.  That's why it uses {@link Hooks} to propagate
 * {@link Context} from outer stream to the inner stream.
 */
public class ConnectionObserverContextTest {

	private static final String CONTEXT_KEY = "marcels-key";
	private static final String CONTEXT_VALUE = "marcels-context";
	private static final ThreadLocal<String> helloWorld = new ThreadLocal<>();

	private static final DisposableServer server = TcpServer.create().port(0).bindNow();

	@Before
	public void before() {
		// Ideally, we would like to add the context to the stream in test via
		// contextWrite(Context.of(CONTEXT_KEY, CONTEXT_VALUE))
		// and let it propagate to the innermost stream.  Only then the propagation
		// to the ConnectionObserver is possible.  However, there is a propagation issue
		// as mentioned in the first item of this comment
		// https://github.com/reactor/reactor-netty/issues/1327#issuecomment-707849473
		// Once https://github.com/reactor/reactor-pool/issues/103 is addressed, we should
		// hopefully not need the `Hooks` anymore.
		helloWorld.set(CONTEXT_VALUE);
		Hooks.onLastOperator(HelloWorldPropagatorSubscriber.class.getName(), HelloWorldPropagatorSubscriber.asOperator());
	}

	@After
	public void after() {
		helloWorld.remove();
		Hooks.resetOnLastOperator(HelloWorldPropagatorSubscriber.class.getName());
	}

	@AfterClass
	public static void afterClass() {
		server.disposeNow();
	}

	@Test
	public void testContextIsPropagatedToConnectionObserver() throws Exception {

		final AtomicReference<String> contextualData = new AtomicReference<>();
		final CountDownLatch channelInitialized = new CountDownLatch(1);

		TcpClient.create()
				.doOnChannelInit((connectionObserver, channel, remoteAddress) -> {
					if (connectionObserver.currentContext().hasKey(CONTEXT_KEY)) {
						contextualData.set(connectionObserver.currentContext().get(CONTEXT_KEY));
					}
					channelInitialized.countDown();
				})
				.remoteAddress(server::address)
				.wiretap(true)
				.connect()
				.subscribe();

		assertTrue(channelInitialized.await(30, TimeUnit.SECONDS));
		assertNotNull(contextualData.get());
		assertEquals(CONTEXT_VALUE, contextualData.get());
	}

	/**
	 * This class is used to decorate other subscribers via {@link reactor.core.publisher.Hooks#onLastOperator}.
	 * It captures the {@link ConnectionObserverContextTest#helloWorld} from thread locals (if exists) and
	 * puts into {@link Context}.
	 */
	private static class HelloWorldPropagatorSubscriber {

		static <T> Function<? super Publisher<T>, ? extends Publisher<T>> asOperator() {
			return ContextModifyingSubscriber.asOperator(
					ctx ->
							Optional.ofNullable(helloWorld.get())
									.map(s -> ctx.put(CONTEXT_KEY, s))
									.orElse(ctx)
			);
		}
	}

	/**
	 * The main functionality is provided by {@link #asOperator(UnaryOperator)}.
	 */
	private static class ContextModifyingSubscriber<T> implements CoreSubscriber<T> {
		private final Subscriber<? super T> delegate;
		private final Context context;

		private ContextModifyingSubscriber(
				final Subscriber<? super T> delegate,
				final Context modifiedContext
		) {
			this.delegate = requireNonNull(delegate);
			this.context  = requireNonNull(modifiedContext);
		}

		@Override
		public void onSubscribe(final Subscription subscription) {
			this.delegate.onSubscribe(subscription);
		}

		@Override
		public void onNext(T t) {
			delegate.onNext(t);
		}

		@Override
		public void onError(Throwable throwable) {
			delegate.onError(throwable);
		}

		@Override
		public void onComplete() {
			delegate.onComplete();
		}

		@Override
		public Context currentContext() {
			return this.context;
		}

		/**
		 * This intended to be used with reactor functions such as {@link Hooks#onLastOperator(Function)}.
		 *
		 * @param contextModifier a function that modifies the {@link Context} given to it.
		 */
		@SuppressWarnings("rawtypes")
		public static <T> Function<? super Publisher<T>, ? extends Publisher<T>> asOperator(
				final UnaryOperator<Context> contextModifier
		) {
			return Operators.liftPublisher((Publisher publisher, CoreSubscriber<? super T> sub) -> {
						// if Flux/Mono #just, #empty, #error
						if (publisher instanceof Fuseable.ScalarCallable) {
							return sub;
						}

						return new ContextModifyingSubscriber<>(sub, contextModifier.apply(sub.currentContext()));
					}
			);
		}
	}
}
