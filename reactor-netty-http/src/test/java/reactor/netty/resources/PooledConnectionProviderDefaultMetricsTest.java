/*
 * Copyright (c) 2019-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.ACTIVE_STREAMS;
import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.MAX_CONNECTIONS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.PENDING_CONNECTIONS;
import static reactor.netty.Metrics.MAX_PENDING_CONNECTIONS;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.PENDING_STREAMS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;
import static reactor.netty.http.client.HttpClientState.STREAM_CONFIGURED;
import static reactor.netty.micrometer.GaugeAssert.assertGauge;
import static reactor.netty.micrometer.TimerAssert.assertTimer;
import static reactor.netty.resources.ConnectionProviderMeters.PENDING_CONNECTIONS_TIME;

/**
 * This test class verifies {@link ConnectionProvider} metrics functionality.
 *
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
	@SuppressWarnings("deprecation")
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
	@SuppressWarnings("deprecation")
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
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, NAME, poolName).hasValueEqualTo(1);
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, NAME, poolName).hasValueEqualTo(1);
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, NAME, "http2." + poolName).hasValueEqualTo(0);
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, NAME, "http2." + poolName).hasValueEqualTo(1);
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + ACTIVE_STREAMS, NAME, "http2." + poolName).hasValueEqualTo(0);
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + PENDING_STREAMS, NAME, "http2." + poolName).hasValueEqualTo(0);
		}
		else {
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, NAME, poolName).hasValueEqualTo(0);
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, NAME, poolName).hasValueEqualTo(0);
		}
		assertGauge(registry, CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, NAME, poolName).hasValueEqualTo(0);
		assertGauge(registry, CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS, NAME, poolName).hasValueEqualTo(0);
		assertGauge(registry, CONNECTION_PROVIDER_PREFIX + MAX_CONNECTIONS, NAME, poolName).hasValueEqualTo(expectedMaxConnection);
		assertGauge(registry, CONNECTION_PROVIDER_PREFIX + MAX_PENDING_CONNECTIONS, NAME, poolName).hasValueEqualTo(expectedMaxPendingAcquire);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, 50, 500})
	@SuppressWarnings("deprecation")
	void testConnectionPoolPendingAcquireSize(long disposeTimeoutMillis) throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		disposableServer =
				HttpServer.create()
				          .port(0)
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(serverCtx))
				          .handle((req, res) ->
				              res.sendString(Flux.range(0, 10)
				                                 .map(i -> "test")
				                                 .delayElements(Duration.ofMillis(4))))
				          .bindNow();

		ConnectionProvider.Builder builder =
				ConnectionProvider.builder("testConnectionPoolPendingAcquireSize")
				                  .pendingAcquireMaxCount(1000)
				                  .maxConnections(500)
				                  .metrics(true);
		ConnectionProvider provider = disposeTimeoutMillis == 0 ?
				builder.build() : builder.disposeTimeout(Duration.ofMillis(disposeTimeoutMillis)).build();

		CountDownLatch meterRemoved = new CountDownLatch(1);
		String name = CONNECTION_PROVIDER_PREFIX + PENDING_STREAMS;
		registry.config().onMeterRemoved(meter -> {
			if (name.equals(meter.getId().getName())) {
				meterRemoved.countDown();
			}
		});
		try {
			CountDownLatch latch = new CountDownLatch(1);
			AtomicInteger counter = new AtomicInteger();
			HttpClient client =
					HttpClient.create(provider)
					          .port(disposableServer.port())
					          .protocol(HttpProtocol.H2)
					          .secure(spec -> spec.sslContext(clientCtx))
					          .observe((conn, state) -> {
					              if (state == STREAM_CONFIGURED) {
					                  counter.incrementAndGet();
					                  conn.onTerminate()
					                      .subscribe(null,
					                          t -> conn.channel().eventLoop().execute(() -> {
					                              if (counter.decrementAndGet() == 0) {
					                                  latch.countDown();
					                              }
					                          }),
					                          () -> conn.channel().eventLoop().execute(() -> {
					                              if (counter.decrementAndGet() == 0) {
					                                  latch.countDown();
					                              }
					                          }));
					              }
					          });


			Flux.range(0, 1000)
			    .flatMap(i ->
			        client.get()
			              .uri("/")
			              .responseContent()
			              .aggregate()
			              .asString()
			              .timeout(Duration.ofMillis(ThreadLocalRandom.current().nextInt(1, 35)), Mono.just("timeout")))
			    .blockLast(Duration.ofSeconds(30));

			assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

			await().atMost(1000, TimeUnit.MILLISECONDS)
			       .with()
			       .pollInterval(50, TimeUnit.MILLISECONDS)
			       .untilAsserted(() -> assertGauge(registry, name, NAME, "http2.testConnectionPoolPendingAcquireSize").hasValueEqualTo(0));

			assertGauge(registry, name, NAME, "http2.testConnectionPoolPendingAcquireSize").hasValueEqualTo(0);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(30));
		}

		assertThat(meterRemoved.await(30, TimeUnit.SECONDS)).isTrue();

		// deRegistered
		assertGauge(registry, name, NAME, "http2.testConnectionPoolPendingAcquireSize").isNull();
	}

	@Test
	void testIssue3060PendingAcquireMaxCountReached() throws Exception {
		testIssue3060(
				ConnectionProvider.builder("testIssue3060")
				                  .maxConnections(1)
				                  .pendingAcquireMaxCount(1)
				                  .metrics(true)
				                  .build());
	}

	@Test
	void testIssue3060PendingAcquireTimeoutReached() throws Exception {
		testIssue3060(
				ConnectionProvider.builder("testIssue3060")
				                  .maxConnections(2)
				                  .pendingAcquireTimeout(Duration.ofMillis(10))
				                  .metrics(true)
				                  .build());
	}

	private void testIssue3060(ConnectionProvider provider) throws Exception {
		try {
			disposableServer =
					createServer().handle((req, res) -> res.header("Connection", "close")
					                                       .sendString(Mono.just("testIssue3060")
					                                                       .delayElement(Duration.ofMillis(50))))
					              .bindNow();

			CountDownLatch latch = new CountDownLatch(1);
			HttpClient client =
					createClient(provider, () -> disposableServer.address())
					        .doOnResponse((res, conn) -> conn.channel().closeFuture().addListener(f -> latch.countDown()));

			Flux.range(0, 3)
			    .flatMapDelayError(i ->
			        client.get()
			              .uri("/")
			              .responseContent()
			              .aggregate()
			              .asString(), 256, 32)
			    .materialize()
			    .blockLast(Duration.ofSeconds(30));

			assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
			assertTimer(registry, PENDING_CONNECTIONS_TIME.getName(), NAME, "testIssue3060", STATUS, ERROR).hasCountEqualTo(1);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	/* https://github.com/reactor/reactor-netty/issues/3519 */
	@Test
	public void testConnectionProviderDisableAllBuiltInMetrics() throws Exception {
		REGISTRY.clear();

		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("testConnectionProviderDisableAllBuiltInMetrics")))
				        .bindNow();

		ConnectionProvider provider =
				ConnectionProvider.builder("testConnectionProviderDisableAllBuiltInMetrics")
				                  .metrics(true, () -> mock(ConnectionProvider.MeterRegistrar.class))
				                  .build();

		try {
			CountDownLatch latch = new CountDownLatch(1);
			createClient(provider, disposableServer.port())
			        .metrics(true, () -> mock(ChannelMetricsRecorder.class))
			        .headers(h -> h.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE))
			        .doOnRequest((req, conn) -> conn.channel().closeFuture().addListener(f -> latch.countDown()))
			        .get()
			        .uri("/")
			        .responseContent()
			        .aggregate()
			        .asString()
			        .as(StepVerifier::create)
			        .expectNext("testConnectionProviderDisableAllBuiltInMetrics")
			        .expectComplete()
			        .verify(Duration.ofSeconds(5));

			assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();

			AtomicInteger count = new AtomicInteger();
			REGISTRY.forEachMeter(meter -> {
				if (meter.getId().getName().startsWith("reactor.netty.connection")) {
					count.incrementAndGet();
				}
			});

			assertThat(count).hasValue(0);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
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
