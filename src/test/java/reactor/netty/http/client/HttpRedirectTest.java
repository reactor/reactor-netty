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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import org.assertj.core.api.Assertions;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

public class HttpRedirectTest {

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
				          .wiretap(true)
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);

		HttpClient client =
				HttpClient.create(pool)
				          .addressSupplier(server::address);

		try {
			Flux.range(0, 1000)
			    .concatMap(i -> client.followRedirect(true)
			                          .post()
			                          .uri("/login")
			                          .responseContent()
			                          .then())
			    .blockLast(Duration.ofSeconds(30));
		}
		finally {
			server.disposeNow();
		}

	}

	@Test
	public void testIssue253() {
		final int serverPort1 = SocketUtils.findAvailableTcpPort();

		DisposableServer server =
				HttpServer.create()
				          .port(serverPort1)
				          .host("localhost")
				          .wiretap(true)
				          .route(r -> r.get("/1",
				                                   (req, res) -> res.sendRedirect("http://localhost:" + serverPort1 + "/3"))
				                       .get("/2",
				                                   (req, res) -> res.status(301)
				                                                    .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort1 + "/3")
				                                                    .send())
				                       .get("/3",
				                                   (req, res) -> res.status(200)
				                                                    .sendString(Mono.just("OK"))))
				          .wiretap(true)
				          .bindNow();

		HttpClient client =
				HttpClient.create()
				          .addressSupplier(server::address)
				          .wiretap(true);

		String value =
				client.followRedirect(true)
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

		value = client.followRedirect(true)
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

		server.disposeNow();
	}

	@Test
	public void testIssue278() {
		final int serverPort1 = SocketUtils.findAvailableTcpPort();
		final int serverPort2 = SocketUtils.findAvailableTcpPort();

		DisposableServer server1 =
				HttpServer.create()
				          .host("localhost")
				          .port(serverPort1)
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
				                       .get("/2", (req, res) -> res.sendRedirect("http://localhost:" + serverPort1 + "/3"))
				                       .get("/3", (req, res) -> res.sendString(Mono.just("OK")))
				                       .get("/4", (req, res) -> res.sendRedirect("http://localhost:" + serverPort2 + "/1")))
				          .wiretap(true)
				          .bindNow();

		DisposableServer server2 =
				HttpServer.create()
				          .host("localhost")
				          .port(serverPort2)
				          .route(r -> r.get("/1", (req, res) -> res.sendString(Mono.just("Other"))))
				          .wiretap(true)
				          .bindNow();

		HttpClient client = HttpClient.create()
		                              .baseUrl("http://localhost:" + serverPort1);

		Mono<String> response =
				client.followRedirect(true)
				      .get()
				      .uri("/1")
				      .responseContent()
				      .aggregate()
				      .asString();

		StepVerifier.create(response)
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		response = client.followRedirect(true)
		                 .get()
		                 .uri("/2")
		                 .responseContent()
		                 .aggregate()
		                 .asString();

		StepVerifier.create(response)
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		response = client.followRedirect(true)
		                 .get()
		                 .uri("/4")
		                 .responseContent()
		                 .aggregate()
		                 .asString();

		StepVerifier.create(response)
		            .expectNextMatches("Other"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server1.disposeNow();
		server2.disposeNow();
	}

	@Test
	public void testIssue522() {
		final int serverPort = SocketUtils.findAvailableTcpPort();

		DisposableServer server =
				HttpServer.create()
				          .port(serverPort)
				          .host("localhost")
				          .route(r -> r.get("/301", (req, res) ->
				                          res.status(301)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/302", (req, res) ->
				                          res.status(302)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/304", (req, res) -> res.status(304))
				                       .get("/307", (req, res) ->
				                          res.status(307)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/308", (req, res) ->
				                          res.status(308)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/predicate", (req, res) ->
				                          res.header("test", "test")
				                             .status(302)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/redirect", (req, res) -> res.sendString(Mono.just("OK"))))
				          .wiretap(true)
				          .bindNow();

		HttpClient client =
				HttpClient.create()
				          .addressSupplier(server::address)
				          .wiretap(true);

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/301")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/302")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/307")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/308")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect((req, res) -> res.responseHeaders()
		                                                           .contains("test"))
		                          .get()
		                          .uri("/predicate")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/304")
		                          .responseSingle((res, bytes) -> Mono.just(res.status()
		                                                                       .code())))
		            .expectNextMatches(i -> i == 304)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	public void testIssue606() {
		final int serverPort = SocketUtils.findAvailableTcpPort();

		DisposableServer server =
				HttpServer.create()
				          .port(serverPort)
				          .host("localhost")
				          .handle((req, res) -> res.sendRedirect("http://localhost:" + serverPort))
				          .wiretap(true)
				          .bindNow();

		AtomicInteger followRedirects = new AtomicInteger(0);
		HttpClient.create()
		          .addressSupplier(server::address)
		          .wiretap(true)
		          .followRedirect((req, res) -> {
		              boolean result = req.redirectedFrom().length < 4;
		              if (result) {
		                  followRedirects.getAndIncrement();
		              }
		              return result;
		          })
		          .get()
		          .uri("/")
		          .responseContent()
		          .blockLast();

		server.disposeNow();

		Assertions.assertThat(followRedirects.get()).isEqualTo(4);
	}
}
