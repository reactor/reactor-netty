/*
 * Copyright (c) 2019-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.ACTIVE_DIRECT_MEMORY;
import static reactor.netty.Metrics.ACTIVE_HEAP_MEMORY;
import static reactor.netty.Metrics.BYTE_BUF_ALLOCATOR_PREFIX;
import static reactor.netty.Metrics.CHUNK_SIZE;
import static reactor.netty.Metrics.DIRECT_ARENAS;
import static reactor.netty.Metrics.HEAP_ARENAS;
import static reactor.netty.Metrics.ID;
import static reactor.netty.Metrics.NORMAL_CACHE_SIZE;
import static reactor.netty.Metrics.SMALL_CACHE_SIZE;
import static reactor.netty.Metrics.THREAD_LOCAL_CACHES;
import static reactor.netty.Metrics.TYPE;
import static reactor.netty.Metrics.USED_DIRECT_MEMORY;
import static reactor.netty.Metrics.USED_HEAP_MEMORY;
import static reactor.netty.micrometer.GaugeAssert.assertGauge;

/**
 * This test class verifies {@link ByteBuf} metrics functionality.
 *
 * @author Violeta Georgieva
 */
class ByteBufAllocatorMetricsTest extends BaseHttpTest {
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
	void test() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.header("Connection", "close")
				                                   .sendString(Mono.just("test")))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(1);

		PooledByteBufAllocator alloc = new PooledByteBufAllocator(true);
		createClient(disposableServer.port())
		          .option(ChannelOption.ALLOCATOR, alloc)
		          .doOnResponse((res, conn) -> conn.channel()
		                                           .closeFuture()
		                                           .addListener(f -> latch.countDown()))
		          .metrics(true, Function.identity())
		          .get()
		          .uri("/")
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		String id = alloc.metric().hashCode() + "";
		String[] tags = new String[]{ID, id, TYPE, "pooled"};
		checkExpectations(BYTE_BUF_ALLOCATOR_PREFIX, tags);

		// Verify ACTIVE_HEAP_MEMORY and ACTIVE_DIRECT_MEMORY meters
		List<ByteBuf> buffers = new ArrayList<>();
		boolean releaseBufList = true;

		try {
			double currentActiveHeap = getGaugeValue(BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_HEAP_MEMORY, tags);
			double currentActiveDirect = getGaugeValue(BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_DIRECT_MEMORY, tags);

			IntStream.range(0, 10).mapToObj(i -> alloc.heapBuffer(102400)).forEach(buffers::add);
			IntStream.range(0, 10).mapToObj(i -> alloc.directBuffer(102400)).forEach(buffers::add);
			assertGauge(registry, BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_HEAP_MEMORY, tags).hasValueGreaterThan(currentActiveHeap);
			assertGauge(registry, BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_DIRECT_MEMORY, tags).hasValueGreaterThan(currentActiveDirect);

			currentActiveHeap = getGaugeValue(BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_HEAP_MEMORY, tags);
			currentActiveDirect = getGaugeValue(BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_DIRECT_MEMORY, tags);

			buffers.forEach(ByteBuf::release);
			releaseBufList = false;

			assertGauge(registry, BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_HEAP_MEMORY, tags).hasValueLessThan(currentActiveHeap);
			assertGauge(registry, BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_DIRECT_MEMORY, tags).hasValueLessThan(currentActiveDirect);
		}
		finally {
			if (releaseBufList) {
				buffers.forEach(ByteBuf::release);
			}
		}
	}

	private double getGaugeValue(String name, String... tags) {
		Gauge gauge = registry.find(name).tags(tags).gauge();
		double result = -1;
		if (gauge != null) {
			result = gauge.value();
		}
		return result;
	}

	private void checkExpectations(String name, String... tags) {
		assertGauge(registry, name + USED_HEAP_MEMORY, tags).hasValueEqualTo(0);
		assertGauge(registry, name + USED_DIRECT_MEMORY, tags).hasValueGreaterThan(0);
		assertGauge(registry, name + HEAP_ARENAS, tags).hasValueGreaterThan(0);
		assertGauge(registry, name + DIRECT_ARENAS, tags).hasValueGreaterThan(0);
		assertGauge(registry, name + THREAD_LOCAL_CACHES, tags).hasValueGreaterThan(0);
		assertGauge(registry, name + SMALL_CACHE_SIZE, tags).hasValueGreaterThan(0);
		assertGauge(registry, name + NORMAL_CACHE_SIZE, tags).hasValueGreaterThan(0);
		assertGauge(registry, name + CHUNK_SIZE, tags).hasValueGreaterThan(0);
	}
}
