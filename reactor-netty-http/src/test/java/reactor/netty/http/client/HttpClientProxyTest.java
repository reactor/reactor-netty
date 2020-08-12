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
package reactor.netty.http.client;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.specto.hoverfly.junit.core.HoverflyConfig;
import io.specto.hoverfly.junit.rule.HoverflyRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.http.server.HttpServer;
import reactor.netty.transport.ProxyProvider;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.specto.hoverfly.junit.core.SimulationSource.dsl;
import static io.specto.hoverfly.junit.dsl.HoverflyDsl.service;
import static io.specto.hoverfly.junit.dsl.ResponseCreators.success;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Violeta Georgieva
 */
public class HttpClientProxyTest {

	@ClassRule
	public static final HoverflyRule hoverflyRule =
			HoverflyRule.inSimulationMode(
					HoverflyConfig.localConfigs()
					              .plainHttpTunneling());

	private DisposableServer server;
	private int port;
	private static final String LOCALLY_NOT_RESOLVABLE_ADDRESS =
			"http://some-random-address-that-is-only-resolvable-by-the-proxy-1234.com";

	@Before
	public void setUp() {
		server = HttpServer.create()
		                   .port(port)
		                   .host("localhost")
		                   .handle((req, res) -> res.sendString(Mono.just("test")))
		                   .wiretap(true)
		                   .bindNow();

		port = server.port();

		hoverflyRule.simulate(
				dsl(service("http://127.0.0.1:" + port)
				        .get("/")
				        .willReturn(success()
				                .body("test")
				                .header("Hoverfly", "Was-Here")),
				    service(LOCALLY_NOT_RESOLVABLE_ADDRESS)
				        .get("/")
				        .willReturn(success().body("Hi from " + LOCALLY_NOT_RESOLVABLE_ADDRESS))));
	}

	@After
	public void tearDown() {
		if (server != null) {
			server.disposeNow();
		}
	}

	@Test
	public void proxy_1() {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverflyRule.getProxyPort()),
				            server::address,
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
	public void proxy_2() {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverflyRule.getProxyPort()),
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
	public void nonProxyHosts_1() {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverflyRule.getProxyPort())
				                      .nonProxyHosts("127.0.0.1"),
				            server::address,
				            "/",
				            true))
				    .expectNextMatches(t ->
				            !t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void nonProxyHosts_2() {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverflyRule.getProxyPort())
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
	public void testIssue804() {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverflyRule.getProxyPort()),
				            server::address,
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
	public void shouldNotResolveTargetHostnameWhenMetricsEnabled() {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverflyRule.getProxyPort()),
				            null,
				            LOCALLY_NOT_RESOLVABLE_ADDRESS,
				            true,
				            true))
				    .expectNextMatches(t -> ("Hi from " + LOCALLY_NOT_RESOLVABLE_ADDRESS).equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void shouldNotResolveTargetHostnameWhenMetricsDisabled() {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverflyRule.getProxyPort()),
				            null,
				            LOCALLY_NOT_RESOLVABLE_ADDRESS,
				            true,
				            false))
				    .expectNextMatches(t -> ("Hi from " + LOCALLY_NOT_RESOLVABLE_ADDRESS).equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void shouldUseDifferentResolvers() {
		HttpClient client =
				HttpClient.create()
				          .remoteAddress(server::address)
				          .metrics(true, () -> MicrometerHttpClientMetricsRecorder.INSTANCE)
				          .wiretap(true);

		AtomicReference<AddressResolverGroup<?>> resolver1 = new AtomicReference<>();
		client.doOnConnect(config -> resolver1.set(config.resolver()))
		      .get()
		      .uri("https://example.com")
		      .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                   Mono.just(response.responseHeaders())))
		      .block(Duration.ofSeconds(30));

		AtomicReference<AddressResolverGroup<?>> resolver2 = new AtomicReference<>();
		client.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
		                       .host("localhost")
		                       .port(hoverflyRule.getProxyPort()))
		      .doOnConnect(config -> resolver2.set(config.resolver()))
		      .get()
		      .uri(LOCALLY_NOT_RESOLVABLE_ADDRESS)
		      .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                   Mono.just(response.responseHeaders())))
		      .block(Duration.ofSeconds(30));

		AtomicReference<AddressResolverGroup<?>> resolver3 = new AtomicReference<>();
		client.doOnConnect(config -> resolver3.set(config.resolver()))
		      .get()
		      .uri("https://example.com")
		      .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                   Mono.just(response.responseHeaders())))
		      .block(Duration.ofSeconds(30));

		assertThat(resolver1.get()).isNotNull()
			.isEqualTo(HttpClientConfig.DEFAULT_RESOLVER);
		assertThat(resolver2.get()).isNotNull()
			.isEqualTo(NoopAddressResolverGroup.INSTANCE);
		assertThat(resolver3.get()).isNotNull()
			.isSameAs(resolver1.get());
	}

	private Mono<Tuple2<String, HttpHeaders>>  sendRequest(
			Consumer<? super ProxyProvider.TypeSpec> proxyOptions,
			Supplier<? extends SocketAddress> connectAddressSupplier,
			String uri,
			boolean wiretap) {
		return sendRequest(proxyOptions, connectAddressSupplier, uri, wiretap, false);
	}

	private Mono<Tuple2<String, HttpHeaders>>  sendRequest(
			Consumer<? super ProxyProvider.TypeSpec> proxyOptions,
			Supplier<? extends SocketAddress> connectAddressSupplier,
			String uri,
			boolean wiretap,
			boolean metricsEnabled) {
		HttpClient client =
				HttpClient.create()
				          .proxy(proxyOptions)
				          .metrics(metricsEnabled, () -> MicrometerHttpClientMetricsRecorder.INSTANCE)
				          .doOnResponse((res, conn) -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", handler != null? "FOUND" : "NOT FOUND");
				          });

		if (connectAddressSupplier != null) {
			client = client.remoteAddress(server::address);
		}

		return client.wiretap(wiretap)
		             .get()
		             .uri(uri)
		             .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                          Mono.just(response.responseHeaders())));
	}
}
