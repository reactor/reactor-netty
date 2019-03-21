/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.client;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import org.assertj.core.api.Assertions;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

public class HttpRedirectTest {

	private final int numberOfTests = 1000;

	@Test
	@Ignore
	public void deadlockWhenRedirectsToSameUrl(){
		redirectTests("/login");
	}

	@Test
	@Ignore
	public void okWhenRedirectsToOther(){
		redirectTests("/other");
	}

	private void redirectTests(String url) {
		AtomicInteger counter = new AtomicInteger(1);
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> {
				              if (req.uri().contains("/login") &&
				                      req.method().equals(HttpMethod.POST) &&
				                      counter.getAndDecrement() > 0) {
				                  return res.sendRedirect(url);
				              }
				              else {
				                  return res.status(200)
				                            .send();
				              }
				          })
				          .wiretap()
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);

		HttpClient client =
				HttpClient.prepare(pool)
				          .addressSupplier(() -> server.address());

		try {
			Flux.range(0, this.numberOfTests)
			    .concatMap(i -> client.followRedirect()
			                          .post()
			                          .uri("/login")
			                          .responseContent()
			                          .then())
			    .blockLast(Duration.ofSeconds(30));
		}
		finally {
			server.dispose();
		}

	}

	@Test
	public void testIssue253() {
		DisposableServer server =
				HttpServer.create()
				          .port(9991)
				          .tcpConfiguration(tcpServer -> tcpServer.host("localhost"))
				          .wiretap()
				          .route(r -> r.get("/1",
				                                   (req, res) -> res.sendRedirect("http://localhost:9991/3"))
				                       .get("/2",
				                                   (req, res) -> res.status(301)
				                                                    .header(HttpHeaderNames.LOCATION, "http://localhost:9991/3")
				                                                    .send())
				                       .get("/3",
				                                   (req, res) -> res.status(200)
				                                                    .sendString(Mono.just("OK"))))
				          .wiretap()
				          .bindNow();

		HttpClient client =
				HttpClient.prepare()
				          .addressSupplier(() -> server.address())
				          .wiretap();

		String value =
				client.followRedirect()
				      .get()
				      .uri("/1")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block(Duration.ofSeconds(30));
		Assertions.assertThat(value).isEqualTo("OK");

		value = client.get()
		              .uri("/1")
		              .responseContent()
		              .aggregate()
		              .asString()
		              .block(Duration.ofSeconds(30));
		Assertions.assertThat(value).isNull();

		value = client.followRedirect()
		              .get()
		              .uri("/2")
		              .responseContent()
		              .aggregate()
		              .asString()
		              .block(Duration.ofSeconds(30));
		Assertions.assertThat(value).isEqualTo("OK");

		value = client.get()
		              .uri("/2")
		              .responseContent()
		              .aggregate()
		              .asString()
		              .block(Duration.ofSeconds(30));
		Assertions.assertThat(value).isNull();

		server.dispose();
	}

	@Test
	public void testIssue278() {
		DisposableServer server1 =
				HttpServer.create()
				          .tcpConfiguration( tcp -> tcp.host("localhost"))
				          .port(8888)
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
				                       .get("/2", (req, res) -> res.sendRedirect("http://localhost:8888/3"))
				                       .get("/3", (req, res) -> res.sendString(Mono.just("OK")))
				                       .get("/4", (req, res) -> res.sendRedirect("http://localhost:8889/1")))
				          .wiretap()
				          .bindNow();

		DisposableServer server2 =
				HttpServer.create()
				          .tcpConfiguration( tcp -> tcp.host("localhost"))
				          .port(8889)
				          .route(r -> r.get("/1", (req, res) -> res.sendString(Mono.just("Other"))))
				          .wiretap()
				          .bindNow();

		HttpClient client = HttpClient.create("http://localhost:8888");

		Mono<String> response =
				client.followRedirect()
				      .get()
				      .uri("/1")
				      .responseContent()
				      .aggregate()
				      .asString();

		StepVerifier.create(response)
		            .expectNextMatches(s -> "OK".equals(s))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		response = client.followRedirect()
		                 .get()
		                 .uri("/2")
		                 .responseContent()
		                 .aggregate()
		                 .asString();

		StepVerifier.create(response)
		            .expectNextMatches(s -> "OK".equals(s))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		response = client.followRedirect()
		                 .get()
		                 .uri("/4")
		                 .responseContent()
		                 .aggregate()
		                 .asString();

		StepVerifier.create(response)
		            .expectNextMatches(s -> "Other".equals(s))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server1.dispose();
		server2.dispose();
	}
}
