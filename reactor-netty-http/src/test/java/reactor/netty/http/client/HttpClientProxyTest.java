/*
 * Copyright (c) 2018-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.specto.hoverfly.junit.core.Hoverfly;
import io.specto.hoverfly.junit.core.HoverflyMode;
import io.specto.hoverfly.junit5.HoverflyExtension;
import io.specto.hoverfly.junit5.api.HoverflyConfig;
import io.specto.hoverfly.junit5.api.HoverflyCore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.NettyPipeline;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.net.SocketAddress;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.specto.hoverfly.junit.core.SimulationSource.dsl;
import static io.specto.hoverfly.junit.dsl.HoverflyDsl.service;
import static io.specto.hoverfly.junit.dsl.ResponseCreators.success;
import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.HTTP_CLIENT_PREFIX;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.MAX_CONNECTIONS;
import static reactor.netty.Metrics.MAX_PENDING_CONNECTIONS;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.PENDING_CONNECTIONS;
import static reactor.netty.Metrics.PROXY_ADDRESS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;
import static reactor.netty.Metrics.URI;
import static reactor.netty.micrometer.DistributionSummaryAssert.assertDistributionSummary;
import static reactor.netty.micrometer.GaugeAssert.assertGauge;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

/**
 * This test class verifies {@link HttpClient} proxy functionality.
 *
 * @author Violeta Georgieva
 */
@HoverflyCore(mode = HoverflyMode.SIMULATE, config = @HoverflyConfig(plainHttpTunneling = true))
@ExtendWith(HoverflyExtension.class)
class HttpClientProxyTest extends BaseHttpTest {

	private int port;
	private static final String LOCALLY_NOT_RESOLVABLE_ADDRESS =
			"https://some-random-address-that-is-only-resolvable-by-the-proxy-1234.com";

	@BeforeEach
	void setUp(Hoverfly hoverfly) {
		disposableServer = createServer()
		                   .host("localhost")
		                   .handle((req, res) -> res.sendString(Mono.just("test")))
		                   .bindNow();

		port = disposableServer.port();

		hoverfly.simulate(
				dsl(service("http://127.0.0.1:" + port)
				        .get("/")
				        .willReturn(success()
				                .body("test")
				                .header("Hoverfly", "Was-Here")),
				    service(LOCALLY_NOT_RESOLVABLE_ADDRESS)
				        .get("/")
				        .willReturn(success().body("Hi from " + LOCALLY_NOT_RESOLVABLE_ADDRESS))));
	}

	@Test
	void proxy_1(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            disposableServer::address,
				            "/",
				            true))
				    .expectNextMatches(t ->
				            t.getT2().contains("Hoverfly") &&
				                "FOUND".equals(t.getT2().get("Logging-Handler")) &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void proxy_2(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            null,
				            "http://127.0.0.1:" + port + "/",
				            true))
				    .expectNextMatches(t ->
				            t.getT2().contains("Hoverfly") &&
				                "FOUND".equals(t.getT2().get("Logging-Handler")) &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void proxyWithDeferredConfiguration(Hoverfly hoverfly) {
		HttpClient client =
				HttpClient.create()
				          .proxyWhen((config, spec) ->
				                  Mono.delay(Duration.ofMillis(10))
				                      .map(noOp -> spec.type(ProxyProvider.Proxy.HTTP)
				                                       .host("localhost")
				                                       .port(hoverfly.getHoverflyConfig().getProxyPort())))
				          .doOnResponse((res, conn) -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", handler != null ? "FOUND" : "NOT FOUND");
				          });

		client.wiretap(true)
		      .get()
		      .uri("http://127.0.0.1:" + port + "/")
		      .responseSingle((response, body) -> Mono.zip(body.asString(), Mono.just(response.responseHeaders())))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> {
		          assertThat(t.getT1()).isEqualTo("test");
		          assertThat(t.getT2().get("Logging-Handler")).isEqualTo("FOUND");
		          assertThat(t.getT2().contains("Hoverfly")).isTrue();
		          return true;
		      })
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	@Test
	void errorOccursWhenDeferredProxyConfigurationIsInvalid() {
		HttpClient client =
				HttpClient.create()
				          .proxyWhen((config, spec) ->
				                  Mono.just(spec.type(ProxyProvider.Proxy.HTTP)
				                                .host("invalid-domain")))
				          .doOnResponse((res, conn) -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", handler != null ? "FOUND" : "NOT FOUND");
				          });

		client.wiretap(true)
		      .get()
		      .uri("http://127.0.0.1:" + port + "/")
		      .responseSingle((response, body) -> Mono.zip(body.asString(), Mono.just(response.responseHeaders())))
		      .as(StepVerifier::create)
		      .expectError(UnresolvedAddressException.class)
		      .verify(Duration.ofSeconds(30));
	}

	@Test
	void proxyWithDeferredConfigurationByConditions(Hoverfly hoverfly) {
		HttpClient client =
				HttpClient.create()
				          .proxyWhen((config, spec) -> {
					          String uri = config.uri();
					          if (uri != null && uri.startsWith("http://127.0.0.1")) {
				                  ProxyProvider.Builder builder =
				                          spec.type(ProxyProvider.Proxy.HTTP)
				                              .host("localhost")
				                              .port(hoverfly.getHoverflyConfig().getProxyPort());

				                  return Mono.just(builder);
				              }

				              return Mono.empty();
				          })
				          .doOnResponse((res, conn) -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", handler != null ? "FOUND" : "NOT FOUND");
				          });

		client.wiretap(true)
		      .get()
		      .uri("http://127.0.0.1:" + port + "/")
		      .responseSingle((response, body) -> Mono.zip(body.asString(), Mono.just(response.responseHeaders())))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> {
		          assertThat(t.getT1()).isEqualTo("test");
		          assertThat(t.getT2().get("Logging-Handler")).isEqualTo("FOUND");
		          assertThat(t.getT2().contains("Hoverfly")).isTrue();
		          return true;
		      })
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		client.wiretap(true)
		      .get()
		      .uri("http://localhost:" + port + "/")
		      .responseSingle((response, body) -> Mono.zip(body.asString(), Mono.just(response.responseHeaders())))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> {
		          assertThat(t.getT1()).isEqualTo("test");
		          assertThat(t.getT2().get("Logging-Handler")).isEqualTo("NOT FOUND");
		          assertThat(t.getT2().contains("Hoverfly")).isFalse();
		          return true;
		      })
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	@Test
	void proxyIgnoredInStaticConfiguration(Hoverfly hoverfly) {
		HttpClient client =
				HttpClient.create()
				          .proxyWhen((config, spec) ->
				                  Mono.just(spec.type(ProxyProvider.Proxy.HTTP)
				                                .host("localhost")
				                                .port(hoverfly.getHoverflyConfig().getProxyPort())))
				          .proxy((spec) -> spec.type(ProxyProvider.Proxy.HTTP)
				                               .host("localhost")
				                               .port(9999))
				          .doOnResponse((res, conn) -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", handler != null ? "FOUND" : "NOT FOUND");
				          });

		client.wiretap(true)
		      .get()
		      .uri("http://127.0.0.1:" + port + "/")
		      .responseSingle((response, body) -> Mono.zip(body.asString(), Mono.just(response.responseHeaders())))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> {
		          assertThat(t.getT1()).isEqualTo("test");
		          assertThat(t.getT2().get("Logging-Handler")).isEqualTo("FOUND");
		          assertThat(t.getT2().contains("Hoverfly")).isTrue();
		          return true;
		      })
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	@Test
	void proxyIgnoredInStaticNoProxyConfiguration(Hoverfly hoverfly) {
		HttpClient client =
				HttpClient.create()
				          .proxyWhen((config, spec) ->
				                  Mono.just(spec.type(ProxyProvider.Proxy.HTTP)
				                                .host("localhost")
				                                .port(hoverfly.getHoverflyConfig().getProxyPort())))
				          .noProxy()
				          .doOnResponse((res, conn) -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", handler != null ? "FOUND" : "NOT FOUND");
				          });

		client.wiretap(true)
		      .get()
		      .uri("http://127.0.0.1:" + port + "/")
		      .responseSingle((response, body) -> Mono.zip(body.asString(), Mono.just(response.responseHeaders())))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> {
		          assertThat(t.getT1()).isEqualTo("test");
		          assertThat(t.getT2().get("Logging-Handler")).isEqualTo("FOUND");
		          assertThat(t.getT2().contains("Hoverfly")).isTrue();
		          return true;
		      })
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	@Test
	void proxyNotEnabledDeferredWithoutNecessaryConfiguration() {
		HttpClient client =
				HttpClient.create()
				          .proxyWhen((config, spec) -> Mono.empty())
				          .doOnResponse((res, conn) -> {
				              ChannelHandler proxyLoggingHandler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Proxy-Logging-Handler", proxyLoggingHandler != null ? "FOUND" : "NOT FOUND");

				              ChannelHandler loggingHandler = conn.channel().pipeline().get(NettyPipeline.LoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", loggingHandler != null ? "FOUND" : "NOT FOUND");
				          });

		client.wiretap(true)
		      .get()
		      .uri("http://localhost:" + port + "/")
		      .responseSingle((response, body) -> Mono.zip(body.asString(), Mono.just(response.responseHeaders())))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> {
		          assertThat(t.getT1()).isEqualTo("test");
		          assertThat(t.getT2().get("Logging-Handler")).isEqualTo("FOUND");
		          assertThat(t.getT2().get("Proxy-Logging-Handler")).isEqualTo("NOT FOUND");
		          assertThat(t.getT2().contains("Hoverfly")).isFalse();
		          return true;
		      })
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	@Test
	void nonProxyHosts_1(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort())
				                      .nonProxyHosts("127.0.0.1"),
				            disposableServer::address,
				            "/",
				            true))
				    .expectNextMatches(t ->
				            !t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void nonProxyHosts_2(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort())
				                      .nonProxyHosts("localhost"),
				            null,
				             "http://localhost:" + port + "/",
				             true))
				    .expectNextMatches(t ->
				            !t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue804(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            disposableServer::address,
				            "/",
				            false))
				    .expectNextMatches(t ->
				           t.getT2().contains("Hoverfly") &&
				                "NOT FOUND".equals(t.getT2().get("Logging-Handler")) &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void shouldNotResolveTargetHostnameWhenMetricsEnabled(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            null,
				            LOCALLY_NOT_RESOLVABLE_ADDRESS,
				            true,
				            true,
				            true))
				    .expectNextMatches(t -> ("Hi from " + LOCALLY_NOT_RESOLVABLE_ADDRESS).equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void shouldNotResolveTargetHostnameWhenMetricsDisabled(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
				            null,
				            LOCALLY_NOT_RESOLVABLE_ADDRESS,
				            true,
				            false,
				            true))
				    .expectNextMatches(t -> ("Hi from " + LOCALLY_NOT_RESOLVABLE_ADDRESS).equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("deprecation")
	void shouldUseDifferentResolvers(Hoverfly hoverfly) {
		Http11SslContextSpec http11SslContextSpec =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		HttpClient client =
				createClient(disposableServer::address)
				          .secure(spec -> spec.sslContext(http11SslContextSpec))
				          .metrics(true, () -> MicrometerHttpClientMetricsRecorder.INSTANCE);

		AtomicReference<@Nullable AddressResolverGroup<?>> resolver1 = new AtomicReference<>();
		client.doOnConnect(config -> resolver1.set(config.resolver()))
		      .get()
		      .uri("https://example.com")
		      .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                   Mono.just(response.responseHeaders())))
		      .block(Duration.ofSeconds(30));

		AtomicReference<@Nullable AddressResolverGroup<?>> resolver2 = new AtomicReference<>();
		client.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
		                       .host("localhost")
		                       .port(hoverfly.getHoverflyConfig().getProxyPort()))
		      .doOnConnect(config -> resolver2.set(config.resolver()))
		      .get()
		      .uri(LOCALLY_NOT_RESOLVABLE_ADDRESS)
		      .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                   Mono.just(response.responseHeaders())))
		      .block(Duration.ofSeconds(30));

		AtomicReference<@Nullable AddressResolverGroup<?>> resolver3 = new AtomicReference<>();
		client.doOnConnect(config -> resolver3.set(config.resolver()))
		      .get()
		      .uri("https://example.com")
		      .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                   Mono.just(response.responseHeaders())))
		      .block(Duration.ofSeconds(30));

		// Uses the default resolver
		assertThat(resolver1.get()).isNull();
		assertThat(resolver2.get()).isNotNull()
			.isEqualTo(NoopAddressResolverGroup.INSTANCE);
		// Uses the default resolver
		assertThat(resolver3.get()).isNull();
	}

	private Mono<Tuple2<String, HttpHeaders>>  sendRequest(
			Consumer<? super ProxyProvider.TypeSpec> proxyOptions,
			@Nullable Supplier<? extends SocketAddress> connectAddressSupplier,
			String uri,
			boolean wiretap) {
		return sendRequest(proxyOptions, connectAddressSupplier, uri, wiretap, false, false);
	}

	@SuppressWarnings("deprecation")
	private Mono<Tuple2<String, HttpHeaders>>  sendRequest(
			Consumer<? super ProxyProvider.TypeSpec> proxyOptions,
			@Nullable Supplier<? extends SocketAddress> connectAddressSupplier,
			String uri,
			boolean wiretap,
			boolean metricsEnabled,
			boolean securityEnabled) {
		HttpClient client =
				HttpClient.create()
				          .proxy(proxyOptions)
				          .metrics(metricsEnabled, () -> MicrometerHttpClientMetricsRecorder.INSTANCE)
				          .doOnResponse((res, conn) -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", handler != null ? "FOUND" : "NOT FOUND");
				          });

		if (connectAddressSupplier != null) {
			client = client.remoteAddress(disposableServer::address);
		}

		if (securityEnabled) {
			Http11SslContextSpec http11SslContextSpec =
					Http11SslContextSpec.forClient()
					                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
			client = client.secure(spec -> spec.sslContext(http11SslContextSpec));
		}

		return client.wiretap(wiretap)
		             .get()
		             .uri(uri)
		             .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                          Mono.just(response.responseHeaders())));
	}

	@Test
	void testIssue1261(Hoverfly hoverfly) {
		AtomicReference<@Nullable AddressResolverGroup<?>> resolver = new AtomicReference<>();
		HttpClient client =
				HttpClient.create()
				          .proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                           .host("localhost")
				                           .port(hoverfly.getHoverflyConfig().getProxyPort()))
				          .doOnConnect(conf -> resolver.set(conf.resolver()));

		client.get()
		      .uri("http://localhost:" + port + "/")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(30));

		assertThat(resolver.get()).isNotNull();
		assertThat(resolver.get()).isInstanceOf(NoopAddressResolverGroup.class);

		client.noProxy()
		      .get()
		      .uri("http://localhost:" + port + "/")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(30));

		assertThat(resolver.get()).isNull();

		client.get()
		      .uri("http://localhost:" + port + "/")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(30));

		assertThat(resolver.get()).isNotNull();
		assertThat(resolver.get()).isInstanceOf(NoopAddressResolverGroup.class);
	}

	@Test
	void testIssue3060(Hoverfly hoverfly) {
		MeterRegistry registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);

		try {
			StepVerifier.create(
					sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
					                      .host("localhost")
					                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
					            disposableServer::address,
					            "/",
					            true,
					            true,
					            false))
					    .expectNextMatches(t ->
					            t.getT2().contains("Hoverfly") &&
					                "FOUND".equals(t.getT2().get("Logging-Handler")) &&
					                "test".equals(t.getT1()))
					    .expectComplete()
					    .verify(Duration.ofSeconds(30));

			String serverAddress = disposableServer.host() + ":" + disposableServer.port();
			String proxyAddress = "localhost:" + hoverfly.getHoverflyConfig().getProxyPort();

			String[] summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, proxyAddress, URI, "/"};
			assertDistributionSummary(registry, HTTP_CLIENT_PREFIX + DATA_RECEIVED, summaryTags1).isNotNull();
			assertDistributionSummary(registry, HTTP_CLIENT_PREFIX + DATA_SENT, summaryTags1).isNotNull();

			String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, proxyAddress, URI, "http"};
			assertDistributionSummary(registry, HTTP_CLIENT_PREFIX + DATA_RECEIVED, summaryTags2).isNotNull();
			assertDistributionSummary(registry, HTTP_CLIENT_PREFIX + DATA_SENT, summaryTags2).isNotNull();

			String[] timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, proxyAddress, STATUS, SUCCESS};
			assertTimer(registry, HTTP_CLIENT_PREFIX + CONNECT_TIME, timerTags1).isNotNull();

			String[] timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, proxyAddress, STATUS, "200", URI, "/", METHOD, "GET"};
			assertTimer(registry, HTTP_CLIENT_PREFIX + DATA_RECEIVED_TIME, timerTags2).isNotNull();
			assertTimer(registry, HTTP_CLIENT_PREFIX + RESPONSE_TIME, timerTags2).isNotNull();

			String[] timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, proxyAddress, URI, "/", METHOD, "GET"};
			assertTimer(registry, HTTP_CLIENT_PREFIX + DATA_SENT_TIME, timerTags3).isNotNull();

			String[] gaugeTags = new String[] {REMOTE_ADDRESS, serverAddress, NAME, "http"};
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + MAX_CONNECTIONS, gaugeTags).isNotNull();
			assertGauge(registry, CONNECTION_PROVIDER_PREFIX + MAX_PENDING_CONNECTIONS, gaugeTags).isNotNull();
		}
		finally {
			Metrics.removeRegistry(registry);
			registry.clear();
			registry.close();
		}
	}

	@Test
	void testIssue3501(Hoverfly hoverfly) {
		ConnectionProvider provider = ConnectionProvider.create("testIssue3501", 1);

		AtomicInteger invocations = new AtomicInteger();
		HttpClient client1 = testIssue3501ConfigureProxy(createClient(provider, port), hoverfly,
				h -> {
					h.set("Test-Issue-3501", "test1");
					invocations.getAndIncrement();
				});
		List<Tuple2<String, Channel>> response1 =
				Flux.range(0, 2)
				    .concatMap(i -> testIssue3501SendRequest(client1, port))
				    .collectList()
				    .block(Duration.ofSeconds(10));

		assertThat(response1).isNotNull().hasSize(2);
		assertThat(response1.get(0).getT1()).isEqualTo("test");
		assertThat(response1.get(1).getT1()).isEqualTo("test");

		assertThat(response1.get(0).getT2()).isSameAs(response1.get(1).getT2());

		assertThat(invocations).hasValue(2);

		invocations.set(0);
		HttpClient client2 = testIssue3501ConfigureProxy(client1, hoverfly,
				h -> {
					h.set("Test-Issue-3501", "test1");
					invocations.getAndIncrement();
				});
		List<Tuple2<String, Channel>> response2 =
				testIssue3501SendRequest(client2, port)
				        .collectList()
				        .block(Duration.ofSeconds(10));

		assertThat(response2).isNotNull().hasSize(1);
		assertThat(response2.get(0).getT1()).isEqualTo("test");

		assertThat(response2.get(0).getT2()).isSameAs(response1.get(0).getT2());

		assertThat(invocations).hasValue(1);

		invocations.set(0);
		HttpClient client3 = testIssue3501ConfigureProxy(client1, hoverfly,
				h -> {
					h.set("Test-Issue-3501", "test2");
					invocations.getAndIncrement();
				});
		List<Tuple2<String, Channel>> response3 =
				testIssue3501SendRequest(client3, port)
				        .collectList()
				        .block(Duration.ofSeconds(10));

		assertThat(response3).isNotNull().hasSize(1);
		assertThat(response3.get(0).getT1()).isEqualTo("test");

		assertThat(response3.get(0).getT2()).isNotSameAs(response1.get(0).getT2());

		assertThat(invocations).hasValue(1);

		provider.disposeLater()
		        .block(Duration.ofSeconds(5));
	}

	private static HttpClient testIssue3501ConfigureProxy(HttpClient baseClient, Hoverfly hoverfly, Consumer<HttpHeaders> headers) {
		return baseClient.proxy(spec -> spec.type(ProxyProvider.Proxy.HTTP)
		                                    .host("localhost")
		                                    .port(hoverfly.getHoverflyConfig().getProxyPort())
		                                    .httpHeaders(headers));
	}

	private static Flux<Tuple2<String, Channel>> testIssue3501SendRequest(HttpClient client, int port) {
		return client.get()
		             .uri("http://127.0.0.1:" + port + "/")
		             .responseConnection((res, conn) ->
		                 conn.inbound()
		                     .receive()
		                     .aggregate()
		                     .asString()
		                     .zipWith(Mono.just(conn.channel())));
	}
}
