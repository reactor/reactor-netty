/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Hooks;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that connection pool maintenance tasks (background eviction and inactive
 * pool disposal) do not capture and retain the scheduling caller's ThreadLocal state
 * when automatic context propagation is enabled.
 */
class PooledConnectionProviderContextPropagationTest {

	DisposableServer disposableServer;
	ConnectionProvider provider;
	ContextRegistry registry;

	@BeforeEach
	void setUp() {
		registry = ContextRegistry.getInstance();
		registry.registerThreadLocalAccessor(new TestThreadLocalAccessor());
		Hooks.enableAutomaticContextPropagation();
		disposableServer =
				TcpServer.create()
				         .handle((in, out) -> out.send(in.receive().retain()))
				         .bindNow();
	}

	@AfterEach
	void tearDown() {
		Hooks.disableAutomaticContextPropagation();
		TestThreadLocalHolder.reset();
		registry.removeThreadLocalAccessor(TestThreadLocalAccessor.KEY);
		if (provider != null) {
			provider.disposeLater()
			        .block(Duration.ofSeconds(30));
		}
		disposableServer.disposeNow();
	}

	@Test
	void backgroundEvictionTaskDoesNotRetainCallerContext() throws Exception {
		provider = ConnectionProvider.builder("evictionContextCapture")
		                             .maxConnections(10)
		                             .maxIdleTime(Duration.ofSeconds(30))
		                             .evictInBackground(Duration.ofMillis(50))
		                             .build();

		// The pool is created lazily on the first acquire, i.e. on the "request" thread below,
		// so the background eviction task is scheduled while the caller's ThreadLocal is set.
		WeakReference<String> callerScope = runOnDedicatedThread(() -> {
			Connection connection =
					TcpClient.create(provider)
					         .host("localhost")
					         .port(disposableServer.port())
					         .connectNow(Duration.ofSeconds(5));
			connection.disposeNow();
			return null;
		});

		assertScopeCollected(callerScope, "background eviction task");
	}

	@Test
	void inactivePoolsDisposalTaskDoesNotRetainCallerContext() throws Exception {
		// The disposal task is scheduled in the ConnectionProvider constructor,
		// i.e. on the "request" thread below.
		AtomicReference<ConnectionProvider> providerRef = new AtomicReference<>();
		WeakReference<String> callerScope = runOnDedicatedThread(() -> {
			providerRef.set(ConnectionProvider.builder("inactivePoolsContextCapture")
			                                  .maxConnections(10)
			                                  .disposeInactivePoolsInBackground(Duration.ofMillis(50), Duration.ofMillis(10))
			                                  .build());
			return null;
		});
		provider = providerRef.get();

		assertScopeCollected(callerScope, "inactive pools disposal task");
	}

	/**
	 * Runs the given action on a dedicated thread with a ThreadLocal value set, resets the
	 * ThreadLocal and lets the thread terminate, so that afterwards no stack frame or
	 * ThreadLocalMap entry can keep the value alive. Returns a {@link WeakReference} to
	 * the value so tests can assert whether it is still strongly reachable elsewhere.
	 */
	static WeakReference<String> runOnDedicatedThread(Supplier<?> action) throws InterruptedException {
		AtomicReference<WeakReference<String>> scopeRef = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread thread = new Thread(() -> {
			String callerScope = new String("caller-scope-value".toCharArray());
			scopeRef.set(new WeakReference<>(callerScope));
			TestThreadLocalHolder.value(callerScope);
			try {
				action.get();
			}
			catch (Throwable t) {
				error.set(t);
			}
			finally {
				TestThreadLocalHolder.reset();
			}
		}, "test-caller-thread");
		thread.start();
		thread.join();
		assertThat(error.get()).isNull();
		return scopeRef.get();
	}

	static void assertScopeCollected(WeakReference<String> callerScope, String taskDescription) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			System.gc();
			if (callerScope.get() == null) {
				return;
			}
			Thread.sleep(100);
		}
		assertThat(callerScope.get())
				.as("caller ThreadLocal state should not be retained by the " + taskDescription)
				.isNull();
	}
}
