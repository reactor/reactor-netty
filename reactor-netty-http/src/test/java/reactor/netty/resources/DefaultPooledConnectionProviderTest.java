/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufMono;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.Http2AllocationStrategy;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;
import reactor.netty.internal.shaded.reactor.pool.PoolShutdownException;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;
import static reactor.netty.http.client.HttpClientState.STREAM_CONFIGURED;

class DefaultPooledConnectionProviderTest extends BaseHttpTest {

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
	void testIssue903() {
		Http11SslContextSpec serverCtx = Http11SslContextSpec.forServer(ssc.key(), ssc.cert());
		disposableServer =
				createServer()
				          .secure(s -> s.sslContext(serverCtx))
				          .handle((req, resp) -> resp.sendHeaders())
				          .bindNow();

		DefaultPooledConnectionProvider provider = (DefaultPooledConnectionProvider) ConnectionProvider.create("testIssue903", 1);
		createClient(provider, disposableServer.port())
		          .get()
		          .uri("/")
		          .response()
		          .onErrorResume(e -> Mono.empty())
		          .block(Duration.ofSeconds(30));

		provider.channelPools.forEach((k, v) -> assertThat(v.metrics().acquiredSize()).isEqualTo(0));

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	void testIssue973() {
		disposableServer =
				createServer()
				          .handle((req, resp) -> resp.sendHeaders())
				          .bindNow();

		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.builder("testIssue973")
				                                                    .maxConnections(2)
				                                                    .forRemoteHost(InetSocketAddress.createUnresolved("localhost", disposableServer.port()),
				                                                            spec -> spec.maxConnections(1))
				                                                    .build();
		AtomicReference<InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection>> pool1 = new AtomicReference<>();
		HttpClient.create(provider)
		          .doOnConnected(conn -> {
		              ConcurrentMap<PooledConnectionProvider.PoolKey, InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection>> pools =
		                      provider.channelPools;
		              for (InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection> pool : pools.values()) {
		                  if (pool.metrics().acquiredSize() == 1) {
		                      pool1.set(pool);
		                      return;
		                  }
		              }
		          })
		          .wiretap(true)
		          .get()
		          .uri("http://localhost:" + disposableServer.port() + "/")
		          .responseContent()
		          .aggregate()
		          .block(Duration.ofSeconds(30));

		assertThat(pool1.get()).isNotNull();

		AtomicReference<InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection>> pool2 = new AtomicReference<>();
		HttpClient.create(provider)
		          .doOnConnected(conn -> {
		              ConcurrentMap<PooledConnectionProvider.PoolKey, InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection>> pools =
		                      provider.channelPools;
		              for (InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection> pool : pools.values()) {
		                  if (pool.metrics().acquiredSize() == 1) {
		                      pool2.set(pool);
		                      return;
		                  }
		              }
		          })
		          .wiretap(true)
		          .get()
		          .uri("https://example.com/")
		          .responseContent()
		          .aggregate()
		          .block(Duration.ofSeconds(30));

		assertThat(pool2.get()).isNotNull();
		assertThat(pool1.get()).as(pool1.get() + " " + pool2.get()).isNotSameAs(pool2.get());

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	void testIssue1012() throws Exception {
		disposableServer =
				createServer()
				          .route(r -> r.get("/1", (req, resp) -> resp.sendString(Mono.just("testIssue1012")))
				                       .get("/2", (req, res) -> Mono.error(new RuntimeException("testIssue1012"))))
				          .bindNow();

		DefaultPooledConnectionProvider provider = (DefaultPooledConnectionProvider) ConnectionProvider.create("testIssue1012", 1);
		CountDownLatch latch = new CountDownLatch(1);
		HttpClient client =
				createClient(provider, disposableServer.port())
				          .doOnConnected(conn -> conn.channel().closeFuture().addListener(f -> latch.countDown()));

		client.get()
		      .uri("/1")
		      .responseContent()
		      .aggregate()
		      .block(Duration.ofSeconds(30));

		client.get()
		      .uri("/2")
		      .responseContent()
		      .aggregate()
		      .onErrorResume(e -> Mono.empty())
		      .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		provider.channelPools.forEach((k, v) -> assertThat(v.metrics().acquiredSize()).isEqualTo(0));

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	void connectionReleasedOnRedirect() throws Exception {
		String redirectedContent = StringUtils.repeat("a", 10000);
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.get("/1", (req, res) -> res.status(HttpResponseStatus.FOUND)
				                                                   .header(HttpHeaderNames.LOCATION, "/2")
				                                                   .sendString(Flux.just(redirectedContent, redirectedContent)))
				                       .get("/2", (req, res) -> res.status(200)
				                                                   .sendString(Mono.just("OK"))))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(2);
		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.create("connectionReleasedOnRedirect", 1);
		String response =
				createClient(provider, disposableServer::address)
				          .followRedirect(true)
				          .observe((conn, state) -> {
				              if (ConnectionObserver.State.RELEASED == state) {
				                  latch.countDown();
				              }
				          })
				          .get()
				          .uri("/1")
				          .responseContent()
				          .aggregate()
				          .asString()
				          .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("OK");

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		provider.channelPools.forEach((k, v) -> assertThat(v.metrics().acquiredSize()).isEqualTo(0));

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	@Disabled
	void testSslEngineClosed() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .bindNow();
		SslContext ctx = SslContextBuilder.forClient()
		                                  .sslProvider(SslProvider.JDK)
		                                  .build();
		HttpClient client =
				createClient(disposableServer.port())
				          .secure(spec -> spec.sslContext(ctx));

		// Connection close happens after `Channel connected`
		// Re-acquiring is not possible
		// The SSLException will be propagated
		doTestSslEngineClosed(client, new AtomicInteger(0), SSLException.class, "SSLEngine is closing/closed");

		// Connection close happens between `Initialized pipeline` and `Channel connected`
		// Re-acquiring
		// Connection close happens after `Channel connected`
		// The SSLException will be propagated, Reactor Netty re-acquire only once
		doTestSslEngineClosed(client, new AtomicInteger(1), SSLException.class, "SSLEngine is closing/closed");

		// Connection close happens between `Initialized pipeline` and `Channel connected`
		// Re-acquiring
		// Connection close happens between `Initialized pipeline` and `Channel connected`
		// The IOException will be propagated, Reactor Netty re-acquire only once
		doTestSslEngineClosed(client, new AtomicInteger(2), IOException.class, "Error while acquiring from");
	}

	private void doTestSslEngineClosed(HttpClient client, AtomicInteger closeCount, Class<? extends Throwable> expectedExc, String expectedMsg) {
		Mono<String> response =
				client.doOnChannelInit(
				        (o, c, address) ->
				            c.pipeline()
				             .addFirst(new ChannelOutboundHandlerAdapter() {

				                 @Override
				                 public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
				                         SocketAddress localAddress, ChannelPromise promise) throws Exception {
				                     super.connect(ctx, remoteAddress, localAddress,
				                             new TestPromise(ctx.channel(), promise, closeCount));
				                 }
				             }))
				      .get()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString();

		StepVerifier.create(response)
		            .expectErrorMatches(t -> t.getClass().isAssignableFrom(expectedExc) && t.getMessage().startsWith(expectedMsg))
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testConnectionIdleWhenNoActiveStreams() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		disposableServer =
				createServer()
				        .wiretap(false)
				        .protocol(HttpProtocol.H2)
				        .secure(spec -> spec.sslContext(serverCtx))
				        .route(routes -> routes.post("/", (req, res) -> res.send(req.receive().retain())))
				        .bindNow();

		int requestsNum = 10;
		CountDownLatch latch = new CountDownLatch(1);
		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.create("testConnectionIdleWhenNoActiveStreams", 5);
		AtomicInteger counter = new AtomicInteger();
		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		HttpClient client =
				createClient(provider, disposableServer.port())
				        .wiretap(false)
				        .protocol(HttpProtocol.H2)
				        .secure(spec -> spec.sslContext(clientCtx))
				        .metrics(true, Function.identity())
				        .doAfterRequest((req, conn) -> serverAddress.set(conn.channel().remoteAddress()))
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

		try {
			Flux.range(0, requestsNum)
			    .flatMap(i ->
			        client.post()
			              .uri("/")
			              .send(ByteBufMono.fromString(Mono.just("testConnectionIdleWhenNoActiveStreams")))
			              .responseContent()
			              .aggregate()
			              .asString())
			    .blockLast(Duration.ofSeconds(5));

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

			InetSocketAddress sa = (InetSocketAddress) serverAddress.get();
			String address = sa.getHostString() + ":" + sa.getPort();

			assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS,
					REMOTE_ADDRESS, address, NAME, "http2.testConnectionIdleWhenNoActiveStreams")).isEqualTo(0);
			double idleConn = getGaugeValue(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS,
					REMOTE_ADDRESS, address, NAME, "http2.testConnectionIdleWhenNoActiveStreams");
			double totalConn = getGaugeValue(CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS,
					REMOTE_ADDRESS, address, NAME, "testConnectionIdleWhenNoActiveStreams");
			assertThat(totalConn).isEqualTo(idleConn);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@ParameterizedTest
	@MethodSource("gracefulShutdownCombinations")
	void testPoolGracefulShutdown(boolean enableGracefulShutdown, boolean isHttp2) {
		disposableServer =
				createServer()
				        .protocol(isHttp2 ? HttpProtocol.H2C : HttpProtocol.HTTP11)
				        .handle((req, res) -> res.sendString(Mono.just("testPoolGracefulShutdown")
				                                                 .delayElement(Duration.ofMillis(50))))
				        .bindNow();

		ConnectionProvider.Builder providerBuilder =
				ConnectionProvider.builder("testPoolGracefulShutdown")
				                  .maxConnections(1);
		if (isHttp2) {
			providerBuilder.allocationStrategy(
					Http2AllocationStrategy.builder().maxConnections(1).maxConcurrentStreams(1).build());
		}
		if (enableGracefulShutdown) {
			providerBuilder.disposeTimeout(Duration.ofMillis(200));
		}
		ConnectionProvider provider = providerBuilder.build();

		HttpClient client =
				createClient(provider, disposableServer.port())
				        .protocol(isHttp2 ? HttpProtocol.H2C : HttpProtocol.HTTP11)
				        .doAfterResponseSuccess((res, conn) -> {
				            if (!provider.isDisposed()) {
				                provider.dispose();
				            }
				        });

		List<Signal<String>> result =
				Flux.range(0, 2)
				    .flatMap(i ->
				        client.get()
				              .uri("/")
				              .responseContent()
				              .aggregate()
				              .asString())
				    .materialize()
				    .collectList()
				    .block(Duration.ofSeconds(5));

		assertThat(result).isNotNull();

		int onNext = 0;
		int onError = 0;
		for (Signal<String> signal : result) {
			if (signal.isOnNext()) {
				onNext++;
				assertThat(signal.get()).isEqualTo("testPoolGracefulShutdown");
			}
			else if (signal.getThrowable() instanceof PoolShutdownException) {
				onError++;
			}
		}

		if (enableGracefulShutdown) {
			assertThat(onNext).isEqualTo(2);
			assertThat(onError).isEqualTo(0);
		}
		else {
			assertThat(onNext).isEqualTo(1);
			assertThat(onError).isEqualTo(1);
		}
	}

	static Stream<Arguments> gracefulShutdownCombinations() {
		return Stream.of(
				Arguments.of(false, false),
				Arguments.of(false, true),
				Arguments.of(true, false),
				Arguments.of(true, true)
		);
	}

	@ParameterizedTest
	@MethodSource("h2cCompatibleCombinations")
	void testIssue1982H2C(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		doTestIssue1982(serverProtocols, clientProtocols, null, null);
	}

	@ParameterizedTest
	@MethodSource("h2CompatibleCombinations")
	void testIssue1982H2(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		doTestIssue1982(serverProtocols, clientProtocols, serverCtx, clientCtx);
	}

	private void doTestIssue1982(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		HttpServer server = serverCtx != null ?
				HttpServer.create().secure(sslContextSpec -> sslContextSpec.sslContext(serverCtx)) :
				HttpServer.create();
		disposableServer =
				server.protocol(serverProtocols)
				      .http2Settings(h2 -> h2.maxConcurrentStreams(20))
				      .handle((req, res) ->
				          res.sendString(Mono.just("testIssue1982")
				                             .delayElement(Duration.ofMillis(100))))
				      .bindNow();

		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.create("doTestIssue1982", 5);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicInteger counter = new AtomicInteger();
		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		HttpClient mainClient = clientCtx != null ?
				HttpClient.create(provider).port(disposableServer.port()).secure(sslContextSpec -> sslContextSpec.sslContext(clientCtx)) :
				HttpClient.create(provider).port(disposableServer.port());

		HttpClient client =
				mainClient.protocol(clientProtocols)
				          .metrics(true, Function.identity())
				          .doAfterRequest((req, conn) -> serverAddress.set(conn.channel().remoteAddress()))
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
		try {
			Flux.range(0, 80)
			    .flatMap(i ->
			        client.get()
			              .uri("/")
			              .responseContent()
			              .aggregate()
			              .asString())
			    .blockLast(Duration.ofSeconds(10));

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

			InetSocketAddress sa = (InetSocketAddress) serverAddress.get();
			String address = sa.getHostString() + ":" + sa.getPort();

			assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS,
					REMOTE_ADDRESS, address, NAME, "http2.doTestIssue1982")).isEqualTo(0);
			double idleConn = getGaugeValue(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS,
					REMOTE_ADDRESS, address, NAME, "http2.doTestIssue1982");
			double totalConn = getGaugeValue(CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS,
					REMOTE_ADDRESS, address, NAME, "doTestIssue1982");
			assertThat(totalConn).isEqualTo(idleConn);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	//https://github.com/reactor/reactor-netty/issues/1808
	@Test
	void testMinConnections() throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		disposableServer =
				createServer()
				        .wiretap(false)
				        .protocol(HttpProtocol.H2)
				        .secure(spec -> spec.sslContext(serverCtx))
				        .route(routes -> routes.post("/", (req, res) -> res.send(req.receive().retain())))
				        .bindNow();

		int requestsNum = 100;
		CountDownLatch latch = new CountDownLatch(1);
		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.builder("testMinConnections")
						.allocationStrategy(Http2AllocationStrategy.builder().maxConnections(20).minConnections(5).build())
						.build();
		AtomicInteger counter = new AtomicInteger();
		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		HttpClient client =
				createClient(provider, disposableServer.port())
				        .wiretap(false)
				        .protocol(HttpProtocol.H2)
				        .secure(spec -> spec.sslContext(clientCtx))
				        .metrics(true, Function.identity())
				        .doAfterRequest((req, conn) -> serverAddress.set(conn.channel().remoteAddress()))
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

		try {
			Flux.range(0, requestsNum)
			    .flatMap(i ->
			        client.post()
			              .uri("/")
			              .send(ByteBufMono.fromString(Mono.just("testMinConnections")))
			              .responseContent()
			              .aggregate()
			              .asString())
			    .blockLast(Duration.ofSeconds(5));

			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

			InetSocketAddress sa = (InetSocketAddress) serverAddress.get();
			String address = sa.getHostString() + ":" + sa.getPort();

			assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS,
					REMOTE_ADDRESS, address, NAME, "http2.testMinConnections")).isEqualTo(0);
			double idleConn = getGaugeValue(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS,
					REMOTE_ADDRESS, address, NAME, "http2.testMinConnections");
			double totalConn = getGaugeValue(CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS,
					REMOTE_ADDRESS, address, NAME, "testMinConnections");
			assertThat(totalConn).isEqualTo(idleConn);
			assertThat(totalConn).isLessThan(10);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@ParameterizedTest
	@MethodSource("disposeInactivePoolsInBackgroundCombinations")
	void testDisposeInactivePoolsInBackground(boolean enableEvictInBackground, boolean isHttp2, boolean isBuiltInMetrics) throws Exception {
		disposableServer =
				createServer()
				        .wiretap(false)
				        .protocol(isHttp2 ? HttpProtocol.H2C : HttpProtocol.HTTP11)
				        .http2Settings(settings -> settings.maxConcurrentStreams(1))
				        .handle((req, res) -> res.sendString(Mono.just("testDisposeInactivePoolsInBackground")))
				        .bindNow();

		ConnectionProvider.Builder builder =
				ConnectionProvider.builder("testDisposeInactivePoolsInBackground")
				                  .maxConnections(10)
				                  .maxIdleTime(Duration.ofMillis(10))
				                  .disposeInactivePoolsInBackground(Duration.ofMillis(200), Duration.ofMillis(500));

		if (enableEvictInBackground) {
			builder.evictInBackground(Duration.ofMillis(50));
		}

		MeterRegistrarImpl meterRegistrar;
		String metricsName = "";
		if (isBuiltInMetrics) {
			meterRegistrar = null;
			builder.metrics(true);

			metricsName = isHttp2 ? "http2.testDisposeInactivePoolsInBackground" : "testDisposeInactivePoolsInBackground";
		}
		else {
			meterRegistrar = new MeterRegistrarImpl();
			builder.metrics(true, () -> meterRegistrar);
		}

		CountDownLatch latch = new CountDownLatch(10);
		DefaultPooledConnectionProvider provider = (DefaultPooledConnectionProvider) builder.build();
		HttpClient client =
				createClient(provider, disposableServer.port())
				        .protocol(isHttp2 ? HttpProtocol.H2C : HttpProtocol.HTTP11)
				        .doOnResponse((res, conn) -> {
				            Channel channel = conn.channel() instanceof Http2StreamChannel ?
				                    conn.channel().parent() : conn.channel();
				            channel.closeFuture()
				                   .addListener(future -> latch.countDown());
				        });

		try {
			Flux.range(0, 10)
			    .flatMap(i -> client.get()
			                        .uri("/")
			                        .responseContent()
			                        .aggregate()
			                        .asString())
			    .collectList()
			    .as(StepVerifier::create)
			    .assertNext(l -> assertThat(l.size()).isEqualTo(10))
			    .expectComplete()
			    .verify(Duration.ofSeconds(5));

			assertThat(provider.channelPools.size()).isEqualTo(1);
			if (meterRegistrar != null) {
				assertThat(meterRegistrar.registered.get()).isTrue();
			}
			else {
				assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, NAME, metricsName)).isNotEqualTo(-1);
			}

			if (enableEvictInBackground) {
				assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			}

			await().atMost(1000, TimeUnit.MILLISECONDS)
			       .with()
			       .pollInterval(50, TimeUnit.MILLISECONDS)
			       .untilAsserted(() -> assertThat(provider.channelPools.size())
			               .isEqualTo(enableEvictInBackground ? 0 : 1));

			assertThat(provider.isDisposed()).isEqualTo(enableEvictInBackground);
			if (meterRegistrar != null) {
				assertThat(meterRegistrar.deRegistered.get()).isEqualTo(enableEvictInBackground);
			}
			else {
				if (enableEvictInBackground) {
					assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, NAME, metricsName)).isEqualTo(-1);
				}
				else {
					assertThat(getGaugeValue(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, NAME, metricsName)).isNotEqualTo(-1);
				}
			}
		}
		finally {
			if (!enableEvictInBackground) {
				provider.disposeLater()
				        .block(Duration.ofSeconds(5));
			}
		}
	}

	static Stream<Arguments> disposeInactivePoolsInBackgroundCombinations() {
		return Stream.of(
				// enableEvictInBackground, isHttp2, isBuiltInMetrics
				Arguments.of(false, false, false),
				Arguments.of(false, false, true),
				Arguments.of(false, true, false),
				Arguments.of(false, true, true),
				Arguments.of(true, false, false),
				Arguments.of(true, false, true),
				Arguments.of(true, true, false),
				Arguments.of(true, true, true)
		);
	}

	static final class TestPromise extends DefaultChannelPromise {

		final ChannelPromise parent;
		final AtomicInteger closeCount;

		public TestPromise(Channel channel, ChannelPromise parent, AtomicInteger closeCount) {
			super(channel);
			this.parent = parent;
			this.closeCount = closeCount;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public boolean trySuccess(Void result) {
			boolean r;
			if (closeCount.getAndDecrement() > 0) {
				//"FutureReturnValueIgnored" this is deliberate
				channel().close();
				r = parent.trySuccess(result);
			}
			else {
				r = parent.trySuccess(result);
				//"FutureReturnValueIgnored" this is deliberate
				channel().close();
			}
			return r;
		}
	}

	private double getGaugeValue(String gaugeName, String... tags) {
		Gauge gauge = registry.find(gaugeName).tags(tags).gauge();
		double result = -1;
		if (gauge != null) {
			result = gauge.value();
		}
		return result;
	}

	@Test
	@SuppressWarnings("FutureReturnValueIgnored")
	void testHttp2PoolAndGoAway() {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		Sinks.Empty<Void> startSending = Sinks.empty();
		disposableServer =
				createServer()
				        .protocol(HttpProtocol.H2)
				        .secure(spec -> spec.sslContext(serverCtx))
				        .route(r -> r.get("/1", (req, res) -> res.sendString(startSending.asMono().then(Mono.just("/1"))))
				                     .get("/2", (req, res) -> {
				                         //"FutureReturnValueIgnored" this is deliberate
				                         req.withConnection(conn -> conn.channel().parent().close());
				                         startSending.tryEmitEmpty();
				                         return res.sendString(Mono.just("/2"));
				                     })
				                     .get("/3", (req, res) -> res.sendString(Mono.just("/3"))))
				        .bindNow();

		ConnectionProvider provider = ConnectionProvider.create("testHttp2PoolAndGoAway", 1);
		Sinks.Empty<Void> goAwayReceived = Sinks.empty();
		HttpClient client =
				createClient(provider, disposableServer.port())
				        .protocol(HttpProtocol.H2)
				        .secure(spec -> spec.sslContext(clientCtx))
				        .doOnChannelInit((observer, channel, address) -> {
				            Http2FrameCodec http2FrameCodec = channel.pipeline().get(Http2FrameCodec.class);

				            http2FrameCodec.gracefulShutdownTimeoutMillis(-1);

				            Http2Connection.Listener goAwayFrameListener = Mockito.mock(Http2Connection.Listener.class);
				            Mockito.doAnswer(invocation -> {
				                       goAwayReceived.tryEmitEmpty();
				                       return null;
				                   })
				                   .when(goAwayFrameListener)
				                   .onGoAwayReceived(Mockito.anyInt(), Mockito.anyLong(), Mockito.any());
				            http2FrameCodec.connection().addListener(goAwayFrameListener);
				        });

		try {
			Flux.range(1, 3)
			    .flatMap(i -> {
			        Mono<String> request = client.get()
			                                     .uri("/" + i)
			                                     .responseContent()
			                                     .aggregate()
			                                     .asString();
			        if (i == 3) {
			            return goAwayReceived.asMono().then(request);
			        }
			        return request;
			    })
			    .collectList()
			    .as(StepVerifier::create)
			    .expectNext(Arrays.asList("/1", "/2", "/3"))
			    .expectComplete()
			    .verify(Duration.ofSeconds(5));
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	static final class MeterRegistrarImpl implements ConnectionProvider.MeterRegistrar {
		AtomicBoolean registered = new AtomicBoolean();
		AtomicBoolean deRegistered = new AtomicBoolean();

		MeterRegistrarImpl() {
		}

		@Override
		public void registerMetrics(String poolName, String id, SocketAddress remoteAddress, ConnectionPoolMetrics metrics) {
			registered.compareAndSet(false, true);
		}

		@Override
		public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
			deRegistered.compareAndSet(false, true);
		}
	}
}
