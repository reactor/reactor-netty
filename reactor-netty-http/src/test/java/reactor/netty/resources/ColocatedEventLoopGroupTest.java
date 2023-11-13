/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This test class verifies {@link EventLoopGroup} colocation.
 *
 * @author Violeta Georgieva
 */
class ColocatedEventLoopGroupTest extends BaseHttpTest {
	static final ThreadLocal<HttpClient> httpClientThreadLocal = new ThreadLocal<>();
	static final LoopResources loop = LoopResources.create("ColocatedEventLoopGroupTest");
	static final AtomicReference<List<ConnectionProvider>> providers = new AtomicReference<>(new ArrayList<>());

	@AfterAll
	static void tearDown() {
		loop.disposeLater()
		    .block(Duration.ofSeconds(5));
		providers.get().forEach(provider ->
				provider.disposeLater()
				        .block(Duration.ofSeconds(5)));
	}

	@Test
	void testIssue1679() {
		DisposableServer server1 = null;
		DisposableServer server2 = null;
		try {
			server1 =
					createServer()
					        .runOn(loop)
					        .handle((req, res) -> res.sendString(Mono.just("testIssue1679")))
					        .bindNow();

			int server1Port = server1.port();
			server2 =
					createServer()
					        .handle((req, res) -> {
					            HttpClient client = httpClientThreadLocal.get();
					            if (client == null) {
					                ConnectionProvider provider =
					                        ConnectionProvider.create("client_conn_pool" + Thread.currentThread(), 500);
					                providers.get().add(provider);
					                client = createClient(provider, server1Port);
					                httpClientThreadLocal.set(client);
					            }
					            String serverThread = Thread.currentThread().getName();
					            return client.get()
					                         .uri("/")
					                         .responseSingle((clientRes, bytes) -> Mono.just(Thread.currentThread().getName()))
					                         .flatMap(s ->
					                                 serverThread.equals(s) ?
					                                     res.sendString(Mono.just("OK")).then() :
					                                     res.sendString(Mono.just("KO")).then());
					        })
					        .bindNow();

			HttpClient client2 = createClient(server2.port()).runOn(loop);
			Mono<String> response2 =
					client2.get()
					       .uri("/")
					       .responseContent()
					       .aggregate()
					       .asString();

			int count = LoopResources.DEFAULT_IO_WORKER_COUNT * 2;
			List<String> expectedResult = Collections.nCopies(count, "OK");
			Flux.range(0, count)
			    .concatMap(i -> response2)
			    .collectList()
			    .as(StepVerifier::create)
			    .expectNext(expectedResult)
			    .expectComplete()
			    .verify(Duration.ofSeconds(30));
		}
		finally {
			if (server1 != null) {
				server1.disposeNow();
			}
			if (server2 != null) {
				server2.disposeNow();
			}
		}
	}
}
