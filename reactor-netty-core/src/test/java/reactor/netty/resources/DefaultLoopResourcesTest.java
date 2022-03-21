/*
 * Copyright (c) 2017-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import reactor.core.publisher.Mono;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpResources;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class DefaultLoopResourcesTest {

	@Test
	void disposeLaterDefers() {
		DefaultLoopResources loopResources = new DefaultLoopResources(
				"test", 0, false);

		Mono<Void> disposer = loopResources.disposeLater();
		assertThat(loopResources.isDisposed()).isFalse();

		disposer.subscribe();
		assertThat(loopResources.isDisposed()).isTrue();
	}

	@Test
	void disposeLaterSubsequentIsQuick() {
		DefaultLoopResources loopResources = new DefaultLoopResources(
				"test", 0, false);
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
}