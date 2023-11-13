/*
 * Copyright (c) 2019-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.transport;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.buffer.pool.PooledBufferAllocator;
import io.netty5.channel.ChannelOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty5.BaseHttpTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty5.Metrics.ACTIVE_MEMORY;
import static reactor.netty5.Metrics.BUFFER_ALLOCATOR_PREFIX;
import static reactor.netty5.Metrics.CHUNK_SIZE;
import static reactor.netty5.Metrics.ARENAS;
import static reactor.netty5.Metrics.ID;
import static reactor.netty5.Metrics.NORMAL_CACHE_SIZE;
import static reactor.netty5.Metrics.SMALL_CACHE_SIZE;
import static reactor.netty5.Metrics.THREAD_LOCAL_CACHES;
import static reactor.netty5.Metrics.TYPE;
import static reactor.netty5.Metrics.USED_MEMORY;

/**
 * This test class verifies {@link Buffer} metrics functionality.
 *
 * @author Violeta Georgieva
 */
class BufferAllocatorMetricsTest extends BaseHttpTest {
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

		List<Buffer> buffers = new ArrayList<>();
		boolean releaseBufList = true;
		try (PooledBufferAllocator alloc = (PooledBufferAllocator) BufferAllocator.offHeapPooled()) {
			createClient(disposableServer.port())
			          .option(ChannelOption.BUFFER_ALLOCATOR, alloc)
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
			String[] tags = new String[]{ID, id, TYPE, alloc.getAllocationType().toString()};
			checkExpectations(BUFFER_ALLOCATOR_PREFIX, tags);

			// Verify ACTIVE_DIRECT_MEMORY meters
			double currentActiveDirect = getGaugeValue(BUFFER_ALLOCATOR_PREFIX + ACTIVE_MEMORY, tags);

			IntStream.range(0, 10).mapToObj(i -> alloc.allocate(102400)).forEach(buffers::add);
			assertThat(getGaugeValue(BUFFER_ALLOCATOR_PREFIX + ACTIVE_MEMORY, tags)).isGreaterThan(currentActiveDirect);

			currentActiveDirect = getGaugeValue(BUFFER_ALLOCATOR_PREFIX + ACTIVE_MEMORY, tags);

			buffers.forEach(Buffer::close);
			releaseBufList = false;

			assertThat(getGaugeValue(BUFFER_ALLOCATOR_PREFIX + ACTIVE_MEMORY, tags)).isLessThan(currentActiveDirect);
		}
		finally {
			if (releaseBufList) {
				buffers.forEach(Buffer::close);
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
		assertThat(getGaugeValue(name + USED_MEMORY, tags)).isGreaterThan(0);
		assertThat(getGaugeValue(name + ARENAS, tags)).isGreaterThan(0);
		assertThat(getGaugeValue(name + THREAD_LOCAL_CACHES, tags)).isGreaterThan(0);
		assertThat(getGaugeValue(name + SMALL_CACHE_SIZE, tags)).isGreaterThan(0);
		assertThat(getGaugeValue(name + NORMAL_CACHE_SIZE, tags)).isGreaterThan(0);
		assertThat(getGaugeValue(name + CHUNK_SIZE, tags)).isGreaterThan(0);
	}
}
