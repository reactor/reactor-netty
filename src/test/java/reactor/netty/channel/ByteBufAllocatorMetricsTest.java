/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
package reactor.netty.channel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.channel.ByteBufAllocatorMetrics.*;

/**
 * @author Violeta Georgieva
 */
public class ByteBufAllocatorMetricsTest {
	private MeterRegistry registry;

	@Before
	public void setUp() {
		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@After
	public void tearDown() {
		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@Test
	public void test() throws Exception {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.header("Connection", "close")
				                                   .sendString(Mono.just("test")))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(1);

		PooledByteBufAllocator alloc = new PooledByteBufAllocator(true);
		HttpClient.create()
		          .port(server.port())
		          .tcpConfiguration(tcpClient -> tcpClient.option(ChannelOption.ALLOCATOR, alloc))
		          .doOnResponse((res, conn) -> conn.channel()
		                                           .closeFuture()
		                                           .addListener(f -> latch.countDown()))
		          .metrics(true)
		          .get()
		          .uri("/")
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		String id = alloc.hashCode() + "";
		assertThat(getGaugeValue(NAME + USED_HEAP_MEMORY, id)).isEqualTo(0);
		assertThat(getGaugeValue(NAME + USED_DIRECT_MEMORY, id)).isGreaterThan(0);
		assertThat(getGaugeValue(NAME + HEAP_ARENAS, id)).isGreaterThan(0);
		assertThat(getGaugeValue(NAME + DIRECT_ARENAS, id)).isGreaterThan(0);
		assertThat(getGaugeValue(NAME + THREAD_LOCAL_CACHES, id)).isGreaterThan(0);
		assertThat(getGaugeValue(NAME + TINY_CACHE_SIZE, id)).isGreaterThan(0);
		assertThat(getGaugeValue(NAME + SMALL_CACHE_SIZE, id)).isGreaterThan(0);
		assertThat(getGaugeValue(NAME + NORMAL_CACHE_SIZE, id)).isGreaterThan(0);
		assertThat(getGaugeValue(NAME + CHUNK_SIZE, id)).isGreaterThan(0);

		server.disposeNow();
	}


	private double getGaugeValue(String name, String id) {
		Gauge gauge = registry.find(name).tags(ID, id).gauge();
		double result = -1;
		if (gauge != null) {
			result = gauge.value();
		}
		return result;
	}

	private static final String NAME = "reactor.netty.pooled.bytebuf.allocator";
}