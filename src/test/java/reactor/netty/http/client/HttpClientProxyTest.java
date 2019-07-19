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
package reactor.netty.http.client;

import io.specto.hoverfly.junit.core.HoverflyConfig;
import io.specto.hoverfly.junit.rule.HoverflyRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.http.server.HttpServer;
import reactor.netty.tcp.ProxyProvider;
import reactor.test.StepVerifier;

import java.time.Duration;

import static io.specto.hoverfly.junit.core.SimulationSource.dsl;
import static io.specto.hoverfly.junit.dsl.HoverflyDsl.service;
import static io.specto.hoverfly.junit.dsl.ResponseCreators.success;

/**
 * @author Violeta Georgieva
 */
public class HttpClientProxyTest {

	private static final int port = SocketUtils.findAvailableTcpPort();

	@ClassRule
	public static final HoverflyRule hoverflyRule =
			HoverflyRule.inSimulationMode(
					dsl(service("http://127.0.0.1:" + port)
					        .get("/")
					        .willReturn(success()
					                .body("test")
					                .header("Hoverfly", "Was-Here"))),
					HoverflyConfig.localConfigs()
					              .plainHttpTunneling());

	private DisposableServer server;

	@Before
	public void setUp() {
		server = HttpServer.create()
		                   .port(port)
		                   .host("localhost")
		                   .handle((req, res) -> res.sendString(Mono.just("test")))
		                   .wiretap(true)
		                   .bindNow();
	}

	@After
	public void tearDown() {
		if (server != null) {
			server.dispose();
		}
	}

	@Test
	public void proxy_1() {
		StepVerifier.create(
				HttpClient.create()
				          .tcpConfiguration(tcpClient -> tcpClient.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                                                                   .host("localhost")
				                                                                   .port(hoverflyRule.getProxyPort())))
				          .addressSupplier(server::address)
				          .wiretap(true)
				          .get()
				          .uri("/")
				          .responseSingle((response, body) -> Mono.zip(body.asString(),
				                  Mono.just(response.responseHeaders()))))
				    .expectNextMatches(t ->
				            t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void proxy_2() {
		StepVerifier.create(
				HttpClient.create()
				          .tcpConfiguration(tcpClient -> tcpClient.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                                                                   .host("localhost")
				                                                                   .port(hoverflyRule.getProxyPort())))
				          .wiretap(true)
				          .get()
				          .uri("http://127.0.0.1:" + port + "/")
				          .responseSingle((response, body) -> Mono.zip(body.asString(),
				                  Mono.just(response.responseHeaders()))))
				    .expectNextMatches(t ->
				            t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void nonProxyHosts_1() {
		StepVerifier.create(
				HttpClient.create()
				          .tcpConfiguration(tcpClient -> tcpClient.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                                                                   .host("localhost")
				                                                                   .port(hoverflyRule.getProxyPort())
				                                                                   .nonProxyHosts("127.0.0.1")))
				          .addressSupplier(server::address)
				          .wiretap(true)
				          .get()
				          .uri("/")
				          .responseSingle((response, body) -> Mono.zip(body.asString(),
				                  Mono.just(response.responseHeaders()))))
				    .expectNextMatches(t ->
				            !t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void nonProxyHosts_2() {
		StepVerifier.create(
				HttpClient.create()
				          .tcpConfiguration(tcpClient -> tcpClient.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                                                                   .host("localhost")
				                                                                   .port(hoverflyRule.getProxyPort())
				                                                                   .nonProxyHosts("localhost")))
				          .wiretap(true)
				          .get()
				          .uri("http://localhost:" + port + "/")
				          .responseSingle((response, body) -> Mono.zip(body.asString(),
				                  Mono.just(response.responseHeaders()))))
				    .expectNextMatches(t ->
				            !t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}
}
