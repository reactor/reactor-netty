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

import io.netty.util.concurrent.*;
import org.junit.Test;
import reactor.test.StepVerifier;

import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.function.Supplier;

public class FutureMonoTest {

	@Test
	public void testImmediateFutureMonoImmediate() {
		ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
		Future<Void> promise = eventExecutor.newFailedFuture(new ClosedChannelException());

		StepVerifier.create(FutureMono.from(promise))
		            .expectError(ReactorNetty.InternalNettyException.class)
		            .verify(Duration.ofSeconds(30));
	}

	// return value of setFailure not needed
	@SuppressWarnings("FutureReturnValueIgnored")
	@Test
	public void testImmediateFutureMonoLater() {
		ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
		Promise<Void> promise = eventExecutor.newPromise();

		StepVerifier.create(FutureMono.from(promise))
		            .expectSubscription()
		            .then(() -> promise.setFailure(new ClosedChannelException()))
		            .expectError(ReactorNetty.InternalNettyException.class)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testDeferredFutureMonoImmediate() {
		ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
		Supplier<Future<Void>> promiseSupplier = () -> eventExecutor.newFailedFuture(new ClosedChannelException());

		StepVerifier.create(FutureMono.deferFuture(promiseSupplier))
		            .expectError(ReactorNetty.InternalNettyException.class)
		            .verify(Duration.ofSeconds(30));
	}

	// return value of setFailure not needed
	@SuppressWarnings("FutureReturnValueIgnored")
	@Test
	public void testDeferredFutureMonoLater() {
		ImmediateEventExecutor eventExecutor = ImmediateEventExecutor.INSTANCE;
		Promise<Void> promise = eventExecutor.newPromise();
		Supplier<Promise<Void>> promiseSupplier = () -> promise;

		StepVerifier.create(FutureMono.deferFuture(promiseSupplier))
		            .expectSubscription()
		            .then(() -> promise.setFailure(new ClosedChannelException()))
		            .expectError(ReactorNetty.InternalNettyException.class)
		            .verify(Duration.ofSeconds(30));
	}
}
