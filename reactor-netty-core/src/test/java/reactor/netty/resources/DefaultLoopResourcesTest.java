/*
 * Copyright (c) 2017-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.FutureMono;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpResources;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class DefaultLoopResourcesTest {

	@Test
	void disposeLaterDefers() {
		DefaultLoopResources loopResources = (DefaultLoopResources) LoopResources.builder("test")
				.workerCount(1)
				.daemon(false)
				.build();

		Mono<Void> disposer = loopResources.disposeLater();
		assertThat(loopResources.isDisposed()).isFalse();

		disposer.subscribe();
		assertThat(loopResources.isDisposed()).isTrue();
	}

	@Test
	void disposeLaterSubsequentIsQuick() {
		DefaultLoopResources loopResources = (DefaultLoopResources) LoopResources.builder("test")
				.workerCount(1)
				.daemon(false)
				.build();
		loopResources.onServer(true);

		assertThat(loopResources.isDisposed()).isFalse();

		Duration firstInvocation = StepVerifier.create(loopResources.disposeLater())
		                                       .verifyComplete();
		assertThat(loopResources.isDisposed()).isTrue();
		if (!LoopResources.hasNativeSupport()) {
			assertThat(loopResources.serverLoops.get().isTerminated()).isTrue();
		}
		else {
			assertThat(loopResources.cacheNativeServerLoops.get().isTerminated()).isTrue();
		}

		Duration secondInvocation = StepVerifier.create(loopResources.disposeLater())
		                                        .verifyComplete();

		assertThat(secondInvocation).isLessThan(firstInvocation);
	}

	@Test
	void testIssue416() {
		TestResources resources = TestResources.get();

		TestResources.set(ConnectionProvider.create("testIssue416"));
		assertThat(resources.provider.isDisposed()).isTrue();
		assertThat(resources.loops.isDisposed()).isFalse();

		TestResources.set(LoopResources.create("test"));
		assertThat(resources.loops.isDisposed()).isTrue();

		assertThat(resources.isDisposed()).isTrue();
	}

	static final class TestResources extends TcpResources {
		final LoopResources loops;
		final ConnectionProvider provider;

		TestResources(LoopResources defaultLoops, ConnectionProvider defaultProvider) {
			super(defaultLoops, defaultProvider);
			this.loops = defaultLoops;
			this.provider = defaultProvider;
		}

		public static TestResources get() {
			return getOrCreate(testResources, null, null, TestResources::new,  "test");
		}
		public static TestResources set(LoopResources loops) {
			return getOrCreate(testResources, loops, null, TestResources::new, "test");
		}
		public static TestResources set(ConnectionProvider pools) {
			return getOrCreate(testResources, null, pools, TestResources::new, "test");
		}

		static final AtomicReference<TestResources> testResources = new AtomicReference<>();
	}

	@Test
	void testClientTransportWarmupNative() throws Exception {
		testClientTransportWarmup(true);
	}

	@Test
	void testClientTransportWarmupNio() throws Exception {
		testClientTransportWarmup(false);
	}

	private void testClientTransportWarmup(boolean preferNative) throws Exception {
		final DefaultLoopResources loop1 =
				(DefaultLoopResources) LoopResources.create("testClientTransportWarmup", 1, true);
		final EventLoopGroup loop2 = new NioEventLoopGroup(1);
		try {
			TcpClient tcpClient = TcpClient.create()
			                               .resolver(spec -> spec.runOn(loop2))
			                               .runOn(loop1, preferNative);

			Mono<Void> warmupMono = tcpClient.warmup();

			assertThat(loop1.cacheNativeClientLoops.get()).isNull();
			assertThat(loop1.clientLoops.get()).isNull();

			warmupMono.block(Duration.ofSeconds(5));

			if (preferNative && LoopResources.hasNativeSupport()) {
				assertThat(loop1.cacheNativeClientLoops.get()).isNotNull();
				assertThat(loop1.clientLoops.get()).isNull();
			}
			else {
				assertThat(loop1.cacheNativeClientLoops.get()).isNull();
				assertThat(loop1.clientLoops.get()).isNotNull();
			}
		}
		finally {
			loop1.disposeLater()
			     .block(Duration.ofSeconds(5));
			loop2.shutdownGracefully()
			     .get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void testServerTransportWarmupNative() {
		testServerTransportWarmup(true);
	}

	@Test
	void testServerTransportWarmupNio() {
		testServerTransportWarmup(false);
	}

	private void testServerTransportWarmup(boolean preferNative) {
		final DefaultLoopResources loop =
				(DefaultLoopResources) LoopResources.create("testServerTransportWarmup", 1, true);
		try {
			TcpServer tcpServer = TcpServer.create()
			                               .runOn(loop, preferNative);

			Mono<Void> warmupMono = tcpServer.warmup();

			assertThat(loop.cacheNativeServerLoops.get()).isNull();
			assertThat(loop.cacheNativeSelectLoops.get()).isNull();
			assertThat(loop.serverLoops.get()).isNull();
			assertThat(loop.serverSelectLoops.get()).isNull();

			warmupMono.block(Duration.ofSeconds(5));

			if (preferNative && LoopResources.hasNativeSupport()) {
				assertThat(loop.cacheNativeServerLoops.get()).isNotNull();
				assertThat(loop.cacheNativeSelectLoops.get()).isNotNull();
				assertThat(loop.serverLoops.get()).isNull();
				assertThat(loop.serverSelectLoops.get()).isNull();
			}
			else {
				assertThat(loop.cacheNativeServerLoops.get()).isNull();
				assertThat(loop.cacheNativeSelectLoops.get()).isNull();
				assertThat(loop.serverLoops.get()).isNotNull();
				assertThat(loop.serverSelectLoops.get()).isNotNull();
			}
		}
		finally {
			loop.disposeLater()
			    .block(Duration.ofSeconds(5));
		}
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void testEpollIsAvailable() {
		assumeThat(System.getProperty("forceTransport")).isEqualTo("native");
		assertThat(Epoll.isAvailable()).isTrue();
	}

	@Test
	@EnabledOnOs(OS.MAC)
	void testKQueueIsAvailable() {
		assumeThat(System.getProperty("forceTransport")).isEqualTo("native");
		assertThat(KQueue.isAvailable()).isTrue();
	}

	@Test
	void testDisableColocation() {
		LoopResources colocated = LoopResources.builder("myloop")
				.workerCount(2)
				.colocate(true)
				.build();

		try {
			LoopResources uncolocated = LoopResources.builder(colocated)
					.colocate(false)
					.build();
			Sinks.One<Thread> t1 = Sinks.unsafe().one();
			Sinks.One<Thread> t2 = Sinks.unsafe().one();

			EventLoopGroup group = uncolocated.onClient(false);
			group.execute(() -> {
				t1.tryEmitValue(Thread.currentThread());
				group.execute(() -> t2.tryEmitValue(Thread.currentThread()));
			});

			StepVerifier.create(t1.asMono()
							.zipWith(t2.asMono()))
					.expectNextMatches(tuple -> !tuple.getT1().equals(tuple.getT2()))
					.expectComplete()
					.verify(Duration.ofSeconds(30));

			uncolocated.disposeLater()
					.block(Duration.ofSeconds(5));

			assertThat(uncolocated.isDisposed()).isFalse();
			assertThat(colocated.isDisposed()).isFalse();
		}

		finally {
			colocated.disposeLater()
					.block(Duration.ofSeconds(5));
			assertThat(colocated.isDisposed()).isTrue();
		}
	}

	@Test
	void testEnableColocation() {
		LoopResources uncolocated = LoopResources.builder("myloop")
				.workerCount(2)
				.colocate(false)
				.build();

		try {
			LoopResources colocated = LoopResources.builder(uncolocated)
					.colocate(true)
					.build();
			Sinks.One<Thread> t1 = Sinks.unsafe().one();
			Sinks.One<Thread> t2 = Sinks.unsafe().one();

			EventLoopGroup group = colocated.onClient(false);
			group.execute(() -> {
				t1.tryEmitValue(Thread.currentThread());
				group.execute(() -> t2.tryEmitValue(Thread.currentThread()));
			});

			StepVerifier.create(t1.asMono()
							.zipWith(t2.asMono()))
					.expectNextMatches(tuple -> tuple.getT1().equals(tuple.getT2()))
					.expectComplete()
					.verify(Duration.ofSeconds(30));

			colocated.disposeLater()
					.block(Duration.ofSeconds(5));

			assertThat(colocated.isDisposed()).isFalse();
			assertThat(uncolocated.isDisposed()).isFalse();
		}

		finally {
			uncolocated.disposeLater()
					.block(Duration.ofSeconds(5));
			assertThat(uncolocated.isDisposed()).isTrue();
		}
	}

	/**
	 * This test checks if we can enable colocation on a LoopResources created using a custom external LoopsResourceFactory
	 * (like https://github.com/turms-im/turms/blob/b2a817058b45b5e8bbb8c0a6e5808e92bfbbb884/turms-server-common/src/main/java/im/turms/server/common/access/common/LoopResourcesFactory.java#L30)
	 */
	@Test
	void colocateCustomLoopResourcesFactory() {
		LoopResources custom = CustomLoopResourcesFactory.createForClient("custom");

		try {
			LoopResources colocated = LoopResources.builder(custom)
					.colocate(true)
					.build();

			Sinks.One<Thread> t1 = Sinks.unsafe().one();
			Sinks.One<Thread> t2 = Sinks.unsafe().one();

			EventLoopGroup group = colocated.onClient(false);
			group.execute(() -> {
				t1.tryEmitValue(Thread.currentThread());
				group.execute(() -> t2.tryEmitValue(Thread.currentThread()));
			});

			StepVerifier.create(t1.asMono()
							.zipWith(t2.asMono()))
					.expectNextMatches(tuple -> tuple.getT1().equals(tuple.getT2()))
					.expectComplete()
					.verify(Duration.ofSeconds(30));

			colocated.disposeLater()
					.block(Duration.ofSeconds(5));
			assertThat(colocated.isDisposed()).isFalse();
			assertThat(custom.isDisposed()).isFalse();
		}

		finally {
			custom.disposeLater()
					.block(Duration.ofSeconds(5));
			assertThat(custom.isDisposed()).isTrue();
		}
	}

	static final class CustomLoopResourcesFactory {
		private static final int DEFAULT_WORKER_THREADS = Math.max(Runtime.getRuntime()
				.availableProcessors(), 1);
		private static final AtomicReference<EventLoopGroup> cache = new AtomicReference<>();
		private static final AtomicBoolean running = new AtomicBoolean(true);

		private CustomLoopResourcesFactory() {
		}

		public static LoopResources createForClient(String prefix) {
			return new LoopResources() {
				@Override
				public EventLoopGroup onServer(boolean useNative) {
					EventLoopGroup group = cache.get();
					if (group == null) {
						ThreadFactory threadFactory = new DefaultThreadFactory(
								prefix
										+ "-worker",
								false);
						group = new NioEventLoopGroup(DEFAULT_WORKER_THREADS, threadFactory);
						if (!cache.compareAndSet(null, group)) {
							group = onServer(useNative);
						}
					}

					running.set(true);
					return group;
				}

				@Override
				public boolean isDisposed() {
					return !running.get();
				}

				@Override
				@SuppressWarnings("unchecked")
				public Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
					Mono<Void> mono = Mono.empty();

					EventLoopGroup group = cache.get();
					if (group == null) {
						return mono;
					}
					if (running.compareAndSet(true, false)) {
						mono = FutureMono.from((Future<Void>) group.shutdownGracefully(0L, 5000, TimeUnit.MILLISECONDS));
					}
					return mono;
				}
			};
		}
	}
}