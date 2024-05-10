/*
 * Copyright (c) 2017-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.ChannelOperationsId;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * This test class verifies basic HTTP functionality.
 *
 * @author Violeta Georgieva
 */
class HttpTests extends BaseHttpTest {

	@Test
	void httpRespondsEmpty() {
		disposableServer =
				createServer()
				          .route(r ->
				              r.post("/test/{param}", (req, res) -> Mono.empty()))
				          .bindNow();

		HttpClient client = createClient(disposableServer.port());

		Mono<ByteBuf> content =
				client.headers(h -> h.add("Content-Type", "text/plain"))
				      .post()
				      .uri("/test/World")
				      .send(ByteBufFlux.fromString(Mono.just("Hello")
				                                       .log("client-send")))
				      .responseContent()
				      .log("client-received")
				      .next()
				      .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(content)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void httpRespondsToRequestsFromClients() {
		disposableServer =
				createServer()
				          .route(r ->
				              r.post("/test/{param}", (req, res) ->
				                  res.sendString(req.receive()
				                                    .asString()
				                                    .log("server-received")
				                                    .map(it -> it + ' ' + req.param("param") + '!')
				                                    .log("server-reply"))))
				          .bindNow();

		HttpClient client = createClient(disposableServer.port());

		Mono<String> content =
				client.headers(h -> h.add("Content-Type", "text/plain"))
				      .post()
				      .uri("/test/World")
				      .send(ByteBufFlux.fromString(Flux.just("Hello")
				                                       .log("client-send")))
				      .responseContent()
				      .aggregate()
				      .asString()
				      .log("client-received")
				      .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(content)
				    .expectNextMatches(s -> s.equals("Hello World!"))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	void httpErrorWithRequestsFromClients() throws Exception {
		CountDownLatch errored1 = new CountDownLatch(1);
		CountDownLatch errored2 = new CountDownLatch(1);
		CountDownLatch errored3 = new CountDownLatch(1);
		CountDownLatch errored4 = new CountDownLatch(1);
		CountDownLatch errored5 = new CountDownLatch(1);

		Flux<ByteBuf> flux1 = Flux.range(0, 257)
		                         .flatMap(i -> {
		                             if (i == 4) {
		                                 // this is deliberate
		                                 throw new RuntimeException("test");
		                             }
		                             return Mono.just(Unpooled.copyInt(i));
		                         });

		Flux<ByteBuf> flux2 = Flux.range(0, 257)
		                          .flatMap(i -> {
			                          if (i == 4) {
				                          return Mono.error(new Exception("test"));
			                          }
			                          return Mono.just(Unpooled.copyInt(i));
		                          });

		disposableServer =
				createServer()
						  .route(r -> r.get("/test", (req, res) -> {
						                   throw new RuntimeException("test");
						               })
						               .get("/test2", (req, res) -> res.send(Flux.error(new Exception("test2")))
						                                                 .then()
						                                                 .log("send-1")
						                                                 .doOnError(t -> errored1.countDown()))
						               .get("/test3", (req, res) -> Flux.error(new Exception("test3")))
						               .get("/issue231_1", (req, res) -> res.send(flux1)
						                                                      .then()
						                                                      .log("send-2")
						                                                      .doOnError(t -> errored2.countDown()))
						               .get("/issue231_2", (req, res) -> res.send(flux2)
						                                                      .then()
						                                                      .log("send-3")
						                                                      .doOnError(t -> errored3.countDown()))
						               .get("/issue237_1", (req, res) -> res.send(flux1)
						                                                      .then()
						                                                      .log("send-4")
						                                                      .doOnError(t -> errored4.countDown()))
						               .get("/issue237_2", (req, res) -> res.send(flux2)
						                                                      .then()
						                                                      .log("send-5")
						                                                      .doOnError(t -> errored5.countDown())))
						  .bindNow();

		HttpClient client = createClient(disposableServer.port());

		Mono<Integer> code =
				client.get()
				      .uri("/test")
				      .responseSingle((res, buf) -> Mono.just(res.status().code()))
				      .log("received-status-1");

		StepVerifier.create(code)
				    .expectNext(500)
				    .expectComplete()
				    .verify(Duration.ofSeconds(5));

		Mono<ByteBuf> content =
				client.get()
				      .uri("/test2")
				      .responseContent()
				      .log("received-status-2")
				      .next();

		StepVerifier.create(content)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Assertions.assertThat(errored1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		client.get()
		      .uri("/issue231_1")
		      .responseContent()
		      .log("received-status-3")
		      .next()
		      .block(Duration.ofSeconds(30));

		Assertions.assertThat(errored2.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		client.get()
		      .uri("/issue231_2")
		      .responseContent()
		      .log("received-status-4")
		      .next()
		      .block(Duration.ofSeconds(30));

		Assertions.assertThat(errored3.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		Flux<ByteBuf> content2 = client.get()
		                               .uri("/issue237_1")
		                               .responseContent()
		                               .log("received-status-5");

		StepVerifier.create(content2)
		            .expectNextCount(4)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Assertions.assertThat(errored4.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		content2 = client.get()
		                 .uri("/issue237_2")
		                 .responseContent()
		                 .log("received-status-6");

		StepVerifier.create(content2)
		            .expectNextCount(4)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Assertions.assertThat(errored5.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		code = client.get()
				     .uri("/test3")
				     .responseSingle((res, buf) -> Mono.just(res.status().code())
				                                       .log("received-status-7"));

		StepVerifier.create(code)
		            .expectNext(500)
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));
	}

	/*
	@Test
	public void webSocketRespondsToRequestsFromClients() {
		AtomicInteger clientRes = new AtomicInteger();
		AtomicInteger serverRes = new AtomicInteger();

		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .router(r -> r.get("/test/{param}", (req, res) -> {
				              System.out.println(req.requestHeaders().get("test"));
				              return res.header("content-type", "text/plain")
				                        .sendWebsocket((in, out) ->
				                            out.options(c -> c.flushOnEach())
				                               .sendString(in.receive()
				                                             .asString()
				                                             .publishOn(Schedulers.single())
				                                             .doOnNext(s -> serverRes.incrementAndGet())
				                                             .map(it -> it + ' ' + req.param("param") + '!')
				                                             .log("server-reply")));
				          }))
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(5));

		HttpClient client = HttpClient.create()
				                      .port(server.address().getPort())
				                      .wiretap(true);

		Mono<List<String>> response =
		    client.request(HttpMethod.GET)
		          .uri("/test/World")
		          .send((req, out) ->
		              req.header("Content-Type", "text/plain")
		                 .header("test", "test")
		                 .options(c -> c.flushOnEach())
		                 .sendWebsocket()
		                 .sendString(Flux.range(1, 1000)
		                                 .log("client-send")
		                                 .map(i -> "" + i)))
		          .responseContent()
		          .asString()
		          .log("client-received")
		          .publishOn(Schedulers.parallel())
		          .doOnNext(s -> clientRes.incrementAndGet())
		          .take(1000)
		          .collectList()
		          .cache()
		          .doOnError(i -> System.err.println("Failed requesting server: " + i));

		System.out.println("STARTING: server[" + serverRes.get() + "] / client[" + clientRes.get() + "]");

		StepVerifier.create(response)
		            .expectNextMatches(list -> "1000 World!".equals(list.get(999)))
		            .expectComplete()
		            .verify(Duration.ofSeconds(10));

		System.out.println("FINISHED: server[" + serverRes.get() + "] / client[" + clientRes + "]");

		server.dispose();
	}
	*/

	@Test
	void streamAndPoolExplicitCompression() {
		Sinks.Many<String> ep = Sinks.unsafe().many().unicast().onBackpressureBuffer();

		disposableServer =
				createServer()
				          .route(r -> r.post("/hi", (req, res) -> req.receive()
				                                                     .aggregate()
				                                                     .asString()
				                                                     .log()
				                                                     .then(res.sendString(Flux.just("test")).then()))
				                       .get("/stream", (req, res) ->
						                           req.receive()
						                              .then(res.compression(true)
						                                       .sendString(ep.asFlux().log()).then())))
				          .bindNow();


		String content =
				createClient(disposableServer.port())
				          .compress(true)
				          .post()
				          .uri("/hi")
				          .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
				          .responseContent()
				          .aggregate()
				          .asString()
				          .log()
				          .block(Duration.ofSeconds(5));

		Flux<String> f = createClient(disposableServer.port())
		                           .compress(true)
		                           .get()
		                           .uri("/stream")
		                           .responseContent()
		                           .asString();
		System.out.println(content);

		StepVerifier.create(f)
		            .then(() -> ep.tryEmitNext("test1").orThrow())
		            .expectNext("test1")
		            .thenAwait(Duration.ofMillis(30))
		            .then(() -> ep.tryEmitNext("test2").orThrow())
		            .thenAwait(Duration.ofMillis(30))
		            .expectNext("test2")
		            .thenAwait(Duration.ofMillis(30))
		            .then(() -> ep.tryEmitComplete().orThrow())
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));



		createClient(disposableServer.port())
		          .compress(true)
		          .post()
		          .uri("/hi")
		          .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .log()
		          .block(Duration.ofSeconds(5));
	}


	@Test
	void streamAndPoolDefaultCompression() {
		Sinks.Many<String> ep = Sinks.unsafe().many().unicast().onBackpressureBuffer();

		disposableServer =
				createServer()
				          .compress(true)
				          .route(r -> r.post("/hi", (req, res) -> req.receive()
				                                                     .aggregate()
				                                                     .asString()
				                                                     .log()
				                                                     .then(res.compression(false)
				                                                                  .sendString(Flux.just("test")).then()))
				                       .get("/stream", (req, res) ->
						                           req.receive()
						                              .then(res.sendString(ep.asFlux().log()).then())))
				          .bindNow();


		String content =
				createClient(disposableServer.port())
				          .compress(true)
				          .post()
				          .uri("/hi")
				          .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
				          .responseContent()
				          .aggregate()
				          .asString()
				          .log()
				          .block(Duration.ofSeconds(5));

		Flux<String> f = createClient(disposableServer.port())
		                           .compress(true)
		                           .get()
		                           .uri("/stream")
		                           .responseContent()
		                           .asString();
		System.out.println(content);

		StepVerifier.create(f)
		            .then(() -> ep.tryEmitNext("test1").orThrow())
		            .expectNext("test1")
		            .thenAwait(Duration.ofMillis(30))
		            .then(() -> ep.tryEmitNext("test2").orThrow())
		            .thenAwait(Duration.ofMillis(30))
		            .expectNext("test2")
		            .thenAwait(Duration.ofMillis(30))
		            .then(() -> ep.tryEmitComplete().orThrow())
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));



		createClient(disposableServer.port())
		          .compress(true)
		          .post()
		          .uri("/hi")
		          .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .log()
		          .block(Duration.ofSeconds(5));
	}

	@Test
	void testIssue387() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> HttpServer.create()
		                                    .secure(sslContextSpec -> System.out.println())
		                                    .bindNow());
	}

	@Test
	void testHttpClientDefaultSslProvider() {
		HttpClient client = HttpClient.create()
		                              .wiretap(true);

		doTestHttpClientDefaultSslProvider(client);
		doTestHttpClientDefaultSslProvider(client.secure());
		doTestHttpClientDefaultSslProvider(client.protocol(HttpProtocol.H2)
		                               .secure()
		                               .protocol(HttpProtocol.HTTP11));
	}

	private void doTestHttpClientDefaultSslProvider(HttpClient client) {
		AtomicBoolean channel = new AtomicBoolean();
		StepVerifier.create(client.doOnRequest((req, conn) -> channel.set(conn.channel().parent() == null))
		                          .get()
		                          .uri("https://example.com/")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches(s -> s.contains("Example Domain"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(channel.get()).isTrue();
	}

	@Test
	void testIds() throws Exception {
		CountDownLatch latch = new CountDownLatch(2);

		AtomicReference<String> serverOpsShortId = new AtomicReference<>();
		AtomicReference<String> serverChannelShortId = new AtomicReference<>();
		AtomicReference<String> serverOpsLongId = new AtomicReference<>();
		AtomicReference<String> serverChannelId = new AtomicReference<>();
		AtomicReference<String> serverRequestId = new AtomicReference<>();
		disposableServer =
				HttpServer.create()
				          .wiretap(true)
				          .doOnConnection(conn -> {
				              serverOpsShortId.set(((ChannelOperationsId) conn).asShortText());
				              serverChannelShortId.set(conn.channel().id().asShortText());

				              serverOpsLongId.set(((ChannelOperationsId) conn).asLongText());
				              serverChannelId.set(conn.channel().toString());

				              latch.countDown();
				          })
				          .handle((req, res) -> {
				              serverRequestId.set(req.requestId());
				              return res.sendString(Mono.just("testIds"));
				          })
				          .bindNow();

		AtomicReference<String> clientOpsShortId = new AtomicReference<>();
		AtomicReference<String> clientChannelShortId = new AtomicReference<>();
		AtomicReference<String> clientOpsLongId = new AtomicReference<>();
		AtomicReference<String> clientChannelId = new AtomicReference<>();
		AtomicReference<String> clientRequestId = new AtomicReference<>();
		ConnectionProvider provider = ConnectionProvider.create("testIds", 1);
		HttpClient client =
				createClient(provider, disposableServer.port())
				        .doOnRequest((req, conn) -> {
				            clientOpsShortId.set(((ChannelOperationsId) conn).asShortText());
				            clientChannelShortId.set(conn.channel().id().asShortText());

				            clientOpsLongId.set(((ChannelOperationsId) conn).asLongText());
				            clientChannelId.set(conn.channel().toString());

				            clientRequestId.set(req.requestId());

				            latch.countDown();
				        });

		client.get()
		      .uri("/")
		      .responseContent()
		      .aggregate()
		      .asString()
		      .as(StepVerifier::create)
		      .expectNext("testIds")
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(serverChannelShortId.get()).isNotNull();
		assertThat(serverRequestId.get()).isNotNull();
		assertThat(serverOpsShortId.get()).isNotNull()
				.isEqualTo(serverChannelShortId.get() + "-1")
				.isEqualTo(serverRequestId.get());

		int originalChannelIdPrefixLength = "[id: 0x".length();
		assertThat(serverChannelId.get()).isNotNull();
		assertThat(serverOpsLongId.get() + ']').isNotNull()
				.isEqualTo(serverChannelId.get()
						.substring(originalChannelIdPrefixLength)
						.replace(serverChannelShortId.get(), serverChannelShortId.get() + "-1"));

		assertThat(clientChannelShortId.get()).isNotNull();
		assertThat(clientRequestId.get()).isNotNull();
		assertThat(clientOpsShortId.get()).isNotNull()
				.isEqualTo(clientChannelShortId.get() + "-1")
				.isEqualTo(clientRequestId.get());

		assertThat(clientChannelId.get()).isNotNull();
		assertThat(clientOpsLongId.get() + ']').isNotNull()
				.isEqualTo(clientChannelId.get()
						.substring(originalChannelIdPrefixLength)
						.replace(clientChannelShortId.get(), clientChannelShortId.get() + "-1"));

		client.get()
		      .uri("/")
		      .responseContent()
		      .aggregate()
		      .asString()
		      .as(StepVerifier::create)
		      .expectNext("testIds")
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(serverChannelShortId.get()).isNotNull();
		assertThat(serverRequestId.get()).isNotNull();
		assertThat(serverOpsShortId.get()).isNotNull()
				.isEqualTo(serverChannelShortId.get() + "-2")
				.isEqualTo(serverRequestId.get());

		assertThat(serverChannelId.get()).isNotNull();
		assertThat(serverOpsLongId.get() + ']').isNotNull()
				.isEqualTo(serverChannelId.get()
						.substring(originalChannelIdPrefixLength)
						.replace(serverChannelShortId.get(), serverChannelShortId.get() + "-2"));

		assertThat(clientChannelShortId.get()).isNotNull();
		assertThat(clientRequestId.get()).isNotNull();
		assertThat(clientOpsShortId.get()).isNotNull()
				.isEqualTo(clientChannelShortId.get() + "-2")
				.isEqualTo(clientRequestId.get());

		assertThat(clientChannelId.get()).isNotNull();
		assertThat(clientOpsLongId.get() + ']').isNotNull()
				.isEqualTo(clientChannelId.get()
						.substring(originalChannelIdPrefixLength)
						.replace(clientChannelShortId.get(), clientChannelShortId.get() + "-2"));
	}
}
