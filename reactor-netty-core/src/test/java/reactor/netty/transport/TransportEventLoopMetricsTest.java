/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.transport;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpServer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.EVENT_LOOP_PREFIX;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.PENDING_TASKS;
import static reactor.netty.micrometer.GaugeAssert.assertGauge;

/**
 * Tests for event loop metrics.
 *
 * @author Pierre De Rop
 * @since 1.0.14
 */
class TransportEventLoopMetricsTest {

	private MeterRegistry registry;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@Test
	void testEventLoopMetrics() throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		DisposableServer server = null;
		Connection client = null;
		LoopResources loop = null;

		try {
			loop = LoopResources.create(TransportEventLoopMetricsTest.class.getName(), 3, true);
			server = TcpServer.create()
					.port(0)
					.metrics(true)
					.runOn(loop)
					.doOnConnection(c -> {
						EventLoop eventLoop = c.channel().eventLoop();
						IntStream.range(0, 10).forEach(i -> eventLoop.execute(() -> {}));
						if (eventLoop instanceof SingleThreadEventExecutor) {
							SingleThreadEventExecutor singleThreadEventExecutor = (SingleThreadEventExecutor) eventLoop;
							String[] tags = new String[]{
									NAME, singleThreadEventExecutor.threadProperties().name(),
							};
							assertGauge(registry, EVENT_LOOP_PREFIX + PENDING_TASKS, tags).hasValueEqualTo(10);
							latch.countDown();
						}
					})
					.wiretap(true)
					.bindNow();

			assertThat(server).isNotNull();

			client = TcpClient.create()
					.port(server.port())
					.wiretap(true)
					.connectNow();

			assertThat(client).isNotNull();
			assertThat(latch.await(5, TimeUnit.SECONDS)).as("Did not find 10 pending tasks from meter").isTrue();
		}

		finally {
			if (client != null) {
				client.disposeNow();
			}
			if (server != null) {
				server.disposeNow();
			}
			if (loop != null) {
				loop.disposeLater().block(Duration.ofSeconds(10));
			}
		}
	}

	// https://github.com/reactor/reactor-netty/issues/2187
	@Test
	void testEventLoopMetricsFailure() throws InterruptedException {
		registry.config().meterFilter(new MeterFilter() {
			@Override
			public Meter.Id map(Meter.Id id) {
				throw new IllegalArgumentException("Test injected Exception");
			}
		});

		final CountDownLatch latch = new CountDownLatch(1);
		DisposableServer server = null;
		Connection client = null;
		LoopResources loop = null;

		try {
			loop = LoopResources.create(TransportEventLoopMetricsTest.class.getName(), 3, true);
			server = TcpServer.create()
					.port(0)
					.metrics(true)
					.runOn(loop)
					.doOnConnection(c -> latch.countDown())
					.bindNow();

			assertThat(server).isNotNull();
			client = TcpClient.create()
					.port(server.port())
					.connectNow();

			assertThat(client).isNotNull();
			assertThat(latch.await(5, TimeUnit.SECONDS)).as("Failed to connect").isTrue();
		}

		finally {
			if (client != null) {
				client.disposeNow();
			}
			if (server != null) {
				server.disposeNow();
			}
			if (loop != null) {
				loop.disposeLater().block(Duration.ofSeconds(10));
			}
		}
	}
}
