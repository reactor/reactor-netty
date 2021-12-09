/*
 * Copyright (c) 2019-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.ACTIVE_STREAMS;
import static reactor.netty.Metrics.MAX_CONNECTIONS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.PENDING_CONNECTIONS;
import static reactor.netty.Metrics.MAX_PENDING_CONNECTIONS;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.PENDING_STREAMS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;

/**
 * @author Violeta Georgieva
 */
class PooledConnectionProviderDefaultMetricsTest extends BaseHttpTest {

	static SelfSignedCertificate ssc;

	private MeterRegistry registry;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

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
	void testConnectionProviderMetricsDisabledAndHttpClientMetricsEnabledHttp1() throws Exception {
		// by default, when the max number of pending acquire is not specified, it will bet set to 2 * max-connection
		// (see PoolFactory from PoolConnectionProvider.java)
		ConnectionProvider provider = ConnectionProvider.create("test1", 1);
		try {
			doTest(createServer(), createClient(provider, () -> disposableServer.address()), "test1", true, 1, 2);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testConnectionProviderMetricsDisabledAndHttpClientMetricsEnabledHttp2() throws Exception {
		// by default, when the max number of pending acquire is not specified, it will bet set to 2 * max-connection
		// (see PoolFactory from PoolConnectionProvider.java)
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		ConnectionProvider provider = ConnectionProvider.create("test2", 1);
		try {
			doTest(createServer().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
					createClient(provider, () -> disposableServer.address()).protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
					"test2", true, 1, 2);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testConnectionProviderMetricsEnableAndHttpClientMetricsDisabledHttp1() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("test3")
				                  .maxConnections(1)
				                  .pendingAcquireMaxCount(10)
				                  .metrics(true)
				                  .lifo()
				                  .build();
		try {
			doTest(createServer(), createClient(provider, () -> disposableServer.address()), "test3", false, 1, 10);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testConnectionProviderMetricsEnableAndHttpClientMetricsDisabledHttp2() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		ConnectionProvider provider =
				ConnectionProvider.builder("test4")
				                  .maxConnections(1)
				                  .pendingAcquireMaxCount(10)
				                  .metrics(true)
				                  .lifo()
				                  .build();
		try {
			doTest(createServer().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
					createClient(provider, () -> disposableServer.address()).protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)),
					"test4", false, 1, 10);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	private void doTest(HttpServer server, HttpClient client, String poolName, boolean clientMetricsEnabled,
			int expectedMaxConnection, int expectedMaxPendingAcquire) throws Exception {
		disposableServer =
				server.handle((req, res) -> res.header("Connection", "close")
				                               .sendString(Mono.just("test")))
				      .bindNow();

		AtomicBoolean metrics = new AtomicBoolean(false);

		CountDownLatch latch = new CountDownLatch(1);

		boolean isSecured = client.configuration().sslProvider() != null;
		client.doOnResponse((res, conn) -> {
		          conn.channel()
		              .closeFuture()
		              .addListener(f -> latch.countDown());

		          double totalConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, poolName);
		          double activeConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, poolName);
		          double maxConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + MAX_CONNECTIONS, poolName);
		          double idleConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, poolName);
		          double pendingConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS, poolName);
		          double maxPendingConnections = getGaugeValue(CONNECTION_PROVIDER_PREFIX + MAX_PENDING_CONNECTIONS, poolName);

		          if (totalConnections == 1 && activeConnections == 1 && idleConnections == 0 && pendingConnections == 0 &&
		                  maxConnections == expectedMaxConnection && maxPendingConnections == expectedMaxPendingAcquire) {
		              metrics.set(true);
		          }

		          if (isSecured) {
		              double activeStreams = getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_STREAMS, "http2." + poolName);
		              double pendingStreams = getGaugeValue(CONNECTION_PROVIDER_PREFIX + PENDING_STREAMS, "http2." + poolName);
		              if (activeStreams == 1 && pendingStreams == 0) {
		                  metrics.set(true);
		              }
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
		if (isSecured) {
			assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, poolName)).isEqualTo(1);
			assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, poolName)).isEqualTo(1);
			assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_STREAMS, "http2." + poolName)).isEqualTo(0);
			assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + PENDING_STREAMS, "http2." + poolName)).isEqualTo(0);
		}
		else {
			assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, poolName)).isEqualTo(0);
			assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, poolName)).isEqualTo(0);
		}
		assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, poolName)).isEqualTo(0);
		assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS, poolName)).isEqualTo(0);
		assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + MAX_CONNECTIONS, poolName)).isEqualTo(expectedMaxConnection);
		assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + MAX_PENDING_CONNECTIONS, poolName)).isEqualTo(expectedMaxPendingAcquire);
	}


	private double getGaugeValue(String gaugeName, String poolName) {
		Gauge gauge = registry.find(gaugeName).tag(NAME, poolName).gauge();
		double result = -1;
		if (gauge != null) {
			result = gauge.value();
		}
		return result;
	}

}