/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.http.client;

import io.specto.hoverfly.junit.core.HoverflyConfig;
import io.specto.hoverfly.junit.rule.HoverflyRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.options.ClientProxyOptions;
import reactor.ipc.netty.resources.PoolResources;
import reactor.test.StepVerifier;

import java.time.Duration;

import static io.specto.hoverfly.junit.core.SimulationSource.classpath;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Violeta Georgieva
 */
public class HttpClientProxyTest {

	@ClassRule
	public static HoverflyRule hoverflyRule =
			HoverflyRule.inSimulationMode(
					classpath("simulation.json"),
					HoverflyConfig.localConfigs()
					              .plainHttpTunneling());
	private NettyContext server;

	@Before
	public void setUp() {
		server = HttpServer.create(ops -> ops.port(7000)
		                                     .host("localhost"))
		                   .newHandler((req, res) -> res.sendString(Mono.just("test")))
		                   .block(Duration.ofSeconds(30));
		assertThat(server).isNotNull();
	}

	@After
	public void tearDown() {
		if (server != null) {
			server.dispose();
		}
	}

	@Test
	public void proxy() {
		PoolResources pool = PoolResources.fixed("test", 1);
		StepVerifier.create(
				HttpClient.create(o -> o.proxy(ops -> ops.type(ClientProxyOptions.Proxy.HTTP)
				                                         .host("localhost")
				                                         .port(hoverflyRule.getProxyPort()))
				                        .connectAddress(server::address)
				                        .poolResources(pool))
				          .get("/")
				          .flatMap(response -> Mono.zip(response.receive()
				                                                .aggregate()
				                                                .asString(),
				                                        Mono.just(response.responseHeaders()))))
				    .expectNextMatches(t ->
				            t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		pool.dispose();
	}

	@Test
	public void nonProxyHosts() {
		PoolResources pool = PoolResources.fixed("test", 1);
		StepVerifier.create(
				HttpClient.create(o -> o.proxy(ops -> ops.type(ClientProxyOptions.Proxy.HTTP)
				                                         .host("localhost")
				                                         .port(hoverflyRule.getProxyPort())
				                                         .nonProxyHosts("127.0.0.1"))
				                        .connectAddress(server::address)
				                        .poolResources(pool))
				          .get("/")
				          .flatMap(response -> Mono.zip(response.receive()
				                                                .aggregate()
				                                                .asString(),
				                                        Mono.just(response.responseHeaders()))))
				    .expectNextMatches(t ->
				            !t.getT2().contains("Hoverfly") &&
				                "test".equals(t.getT1()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		pool.dispose();
	}
}
