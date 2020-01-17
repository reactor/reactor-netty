/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
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
package reactor.netty.resources;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.resources.PooledConnectionProviderMetrics.*;

/**
 * @author Violeta Georgieva
 */
public class PooledConnectionProviderMetricsTest {
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

		AtomicBoolean metrics = new AtomicBoolean(false);

		CountDownLatch latch = new CountDownLatch(1);

		PooledConnectionProvider fixed = (PooledConnectionProvider) ConnectionProvider.create("test", 1);
		AtomicReference<String[]> tags = new AtomicReference<>();

		HttpClient.create(fixed)
		          .port(server.port())
		          .doOnResponse((res, conn) -> {
		              conn.channel()
		                  .closeFuture()
		                  .addListener(f -> latch.countDown());

		              PooledConnectionProvider.PoolKey key = (PooledConnectionProvider.PoolKey) fixed.channelPools.keySet().toArray()[0];
		              InetSocketAddress sa = (InetSocketAddress) conn.channel().remoteAddress();
		              String[] tagsArr = new String[]{ID, key.hashCode() + "", REMOTE_ADDRESS, sa.getHostString() + ":" + sa.getPort()};
		              tags.set(tagsArr);

		              double totalConnections = getGaugeValue(NAME + TOTAL_CONNECTIONS, tagsArr);
		              double activeConnections = getGaugeValue(NAME + ACTIVE_CONNECTIONS, tagsArr);
		              double idleConnections = getGaugeValue(NAME + IDLE_CONNECTIONS, tagsArr);
		              double pendingConnections = getGaugeValue(NAME + PENDING_CONNECTIONS, tagsArr);

		              if (totalConnections == 1 && activeConnections == 1 &&
		                      idleConnections == 0 && pendingConnections == 0) {
		                  metrics.set(true);
		              }
		          })
		          .metrics(true)
		          .get()
		          .uri("/")
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block(Duration.ofSeconds(30));

		assertTrue(latch.await(30, TimeUnit.SECONDS));
		assertTrue(metrics.get());
		String[] tagsArr = tags.get();
		assertNotNull(tagsArr);
		assertEquals(0, getGaugeValue(NAME + TOTAL_CONNECTIONS, tagsArr), 0.0);
		assertEquals(0, getGaugeValue(NAME + ACTIVE_CONNECTIONS, tagsArr), 0.0);
		assertEquals(0, getGaugeValue(NAME + IDLE_CONNECTIONS, tagsArr), 0.0);
		assertEquals(0, getGaugeValue(NAME + PENDING_CONNECTIONS, tagsArr), 0.0);

		fixed.disposeLater()
		     .block(Duration.ofSeconds(30));

		server.disposeNow();
	}


	private double getGaugeValue(String name, String[] tags) {
		Gauge gauge = registry.find(name).tags(tags).gauge();
		double result = -1;
		if (gauge != null) {
			result = gauge.value();
		}
		return result;
	}

	private static final String NAME = "reactor.netty.connection.provider.test";
}
