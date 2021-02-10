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
package reactor.netty.resources;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.ID;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.PENDING_CONNECTIONS;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;

/**
 * @author Violeta Georgieva
 */
class PooledConnectionProviderDefaultMetricsTest extends BaseHttpTest {
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
	void testConnectionProviderMetricsDisabledAndHttpClientMetricsEnabled() throws Exception {
		doTest(ConnectionProvider.create("test", 1), true);
	}

	@Test
	void testConnectionProviderMetricsEnableAndHttpClientMetricsDisabled() throws Exception {
		doTest(ConnectionProvider.builder("test").maxConnections(1).metrics(true).lifo().build(), false);
	}

	private void doTest(ConnectionProvider provider, boolean clientMetricsEnabled) throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.header("Connection", "close")
				                                   .sendString(Mono.just("test")))
				          .bindNow();

		AtomicBoolean metrics = new AtomicBoolean(false);

		CountDownLatch latch = new CountDownLatch(1);

		DefaultPooledConnectionProvider fixed = (DefaultPooledConnectionProvider) provider;
		AtomicReference<String[]> tags = new AtomicReference<>();

		createClient(fixed, disposableServer.port())
		          .doOnResponse((res, conn) -> {
		              conn.channel()
		                  .closeFuture()
		                  .addListener(f -> latch.countDown());

		              PooledConnectionProvider.PoolKey key = (PooledConnectionProvider.PoolKey) fixed.channelPools.keySet().toArray()[0];
		              InetSocketAddress sa = (InetSocketAddress) conn.channel().remoteAddress();
		              String[] tagsArr = new String[]{ID, key.hashCode() + "", REMOTE_ADDRESS, sa.getHostString() + ":" + sa.getPort(), NAME, "test"};
		              tags.set(tagsArr);

		              double totalConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, tagsArr);
		              double activeConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, tagsArr);
		              double idleConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, tagsArr);
		              double pendingConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS, tagsArr);

		              if (totalConnections == 1 && activeConnections == 1 &&
		                      idleConnections == 0 && pendingConnections == 0) {
		                  metrics.set(true);
		              }
		          })
		          .metrics(clientMetricsEnabled, Function.identity())
		          .get()
		          .uri("/")
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(metrics.get()).isTrue();
		String[] tagsArr = tags.get();
		assertThat(tagsArr).isNotNull();
		assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, tagsArr)).isEqualTo(0);
		assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, tagsArr)).isEqualTo(0);
		assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, tagsArr)).isEqualTo(0);
		assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS, tagsArr)).isEqualTo(0);

		fixed.disposeLater()
		     .block(Duration.ofSeconds(30));
	}


	private double getGaugeValue(String name, String[] tags) {
		Gauge gauge = registry.find(name).tags(tags).gauge();
		double result = -1;
		if (gauge != null) {
			result = gauge.value();
		}
		return result;
	}

}