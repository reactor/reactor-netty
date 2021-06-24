/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.specto.hoverfly.junit.core.Hoverfly;
import io.specto.hoverfly.junit.core.HoverflyMode;
import io.specto.hoverfly.junit5.HoverflyExtension;
import io.specto.hoverfly.junit5.api.HoverflyConfig;
import io.specto.hoverfly.junit5.api.HoverflyCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.http.server.HttpServer;
import reactor.netty.tcp.ProxyProvider;
import reactor.netty.tcp.TcpClient;
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
@HoverflyCore(mode = HoverflyMode.SIMULATE, config = @HoverflyConfig(plainHttpTunneling = true))
@ExtendWith(HoverflyExtension.class)
public class HttpClientProxyTest {

	private DisposableServer server;
	private int port;
	private static final String LOCALLY_NOT_RESOLVABLE_ADDRESS =
			"https://some-random-address-that-is-only-resolvable-by-the-proxy-1234.com";

	@BeforeEach
	public void setUp(Hoverfly hoverfly) {
		server = HttpServer.create()
		                   .port(port)
		                   .host("localhost")
		                   .handle((req, res) -> res.sendString(Mono.just("test")))
		                   .wiretap(true)
		                   .bindNow();

		port = server.port();

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

	@AfterEach
	public void tearDown() {
		if (server != null) {
			server.disposeNow();
		}
	}

	@Test
	public void proxy_1(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
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
	public void proxy_2(Hoverfly hoverfly) {
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
	public void nonProxyHosts_1(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort())
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
	public void nonProxyHosts_2(Hoverfly hoverfly) {
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
	public void testIssue804(Hoverfly hoverfly) {
		StepVerifier.create(
				sendRequest(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                      .host("localhost")
				                      .port(hoverfly.getHoverflyConfig().getProxyPort()),
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
	public void shouldNotResolveTargetHostnameWhenMetricsEnabled(Hoverfly hoverfly) {
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
	public void shouldNotResolveTargetHostnameWhenMetricsDisabled(Hoverfly hoverfly) {
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

	private Mono<Tuple2<String, HttpHeaders>>  sendRequest(
			Consumer<? super ProxyProvider.TypeSpec> proxyOptions,
			Supplier<? extends SocketAddress> connectAddressSupplier,
			String uri,
			boolean wiretap) {
		return sendRequest(proxyOptions, connectAddressSupplier, uri, wiretap, false, false);
	}

	private Mono<Tuple2<String, HttpHeaders>>  sendRequest(
			Consumer<? super ProxyProvider.TypeSpec> proxyOptions,
			Supplier<? extends SocketAddress> connectAddressSupplier,
			String uri,
			boolean wiretap,
			boolean metricsEnabled,
			boolean securityEnabled) {
		HttpClient client =
				HttpClient.create()
				          .metrics(metricsEnabled, () -> MicrometerHttpClientMetricsRecorder.INSTANCE)
				          .tcpConfiguration(tcpClient -> tcpClient.proxy(proxyOptions))
				          .doOnResponse((res, conn) -> {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ProxyLoggingHandler);
				              res.responseHeaders()
				                 .add("Logging-Handler", handler != null? "FOUND" : "NOT FOUND");
				          });

		if (connectAddressSupplier != null) {
			client = client.remoteAddress(server::address);
		}

		if (securityEnabled) {
			client = client.secure(spec ->
			        spec.sslContext(SslContextBuilder.forClient()
			                                         .trustManager(InsecureTrustManagerFactory.INSTANCE)));
		}

		return client.wiretap(wiretap)
		             .get()
		             .uri(uri)
		             .responseSingle((response, body) -> Mono.zip(body.asString(),
		                                                          Mono.just(response.responseHeaders())));
	}

	@Test
	public void testIssue1261(Hoverfly hoverfly) {
		AtomicReference<AddressResolverGroup<?>> resolver = new AtomicReference<>();
		HttpClient client =
				HttpClient.create()
				          .tcpConfiguration(tcpClient -> tcpClient.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                                                                   .host("localhost")
				                                                                   .port(hoverfly.getHoverflyConfig().getProxyPort()))
				                                                  .doOnConnect(b -> resolver.set(b.config().resolver())));

		client.get()
		      .uri("http://localhost:" + port + "/")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(30));

		assertThat(resolver.get()).isNotNull();
		assertThat(resolver.get()).isInstanceOf(NoopAddressResolverGroup.class);

		client.tcpConfiguration(TcpClient::noProxy)
		      .get()
		      .uri("http://localhost:" + port + "/")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(30));

		assertThat(resolver.get()).isNotNull();
		assertThat(resolver.get()).isInstanceOf(DefaultAddressResolverGroup.class);

		client.get()
		      .uri("http://localhost:" + port + "/")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(30));

		assertThat(resolver.get()).isNotNull();
		assertThat(resolver.get()).isInstanceOf(NoopAddressResolverGroup.class);
	}
}
