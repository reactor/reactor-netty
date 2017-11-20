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
package reactor.ipc.netty.http;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.Unpooled;
import org.assertj.core.api.Assertions;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

/**
 * @author Violeta Georgieva
 */
public class HttpTests {

	@Test
	public void httpRespondsEmpty() {
		Connection server =
				HttpServer.create()
				          .port(0)
				          .router(r ->
				              r.post("/test/{param}", (req, res) -> Mono.empty()))
				          .wiretap()
				          .bindNow();

		HttpClient client =
				HttpClient.create("localhost", server.address().getPort());

		Mono<ByteBuf> content = client
				            .post("/test/World", req -> req.header("Content-Type", "text/plain")
				                                           .sendString(Mono.just("Hello")
				                                           .log("client-send")))
				            .flatMap(res -> res.receive()
				                               .log("client-received")
				                               .next())
				            .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(content)
				    .expectComplete()
				    .verify(Duration.ofSeconds(5000));

		server.dispose();
	}

	@Test
	public void httpRespondsToRequestsFromClients() {
		Connection server =
				HttpServer.create()
				          .port(0)
				          .router(r ->
				              r.post("/test/{param}", (req, res) ->
				                  res.sendString(req.receive()
				                                    .asString()
				                                    .log("server-received")
				                                    .map(it -> it + ' ' + req.param("param") + '!')
				                                    .log("server-reply"))))
				          .wiretap()
				          .bindNow();

		HttpClient client =
				HttpClient.create("localhost", server.address().getPort());

		Mono<String> content =
				client.post("/test/World", req -> req.header("Content-Type", "text/plain")
				                                     .sendString(Flux.just("Hello")
				                                     .log("client-send")))
				      .flatMap(res -> res.receive()
				                         .aggregate()
				                         .asString()
				                         .log("client-received"))
				      .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(content)
				    .expectNextMatches(s -> s.equals("Hello World!"))
				    .expectComplete()
				    .verify(Duration.ofSeconds(5000));

		server.dispose();
	}

	@Test
	public void httpErrorWithRequestsFromClients() throws Exception {
		CountDownLatch errored1 = new CountDownLatch(1);
		CountDownLatch errored2 = new CountDownLatch(1);
		CountDownLatch errored3 = new CountDownLatch(1);
		CountDownLatch errored4 = new CountDownLatch(1);
		CountDownLatch errored5 = new CountDownLatch(1);

		Flux<ByteBuf> flux1 = Flux.range(0, 257)
		                         .flatMap(i -> {
		                             if (i == 4) {
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
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .router(r -> r.get("/test", (req, res) -> {throw new
						          RuntimeException();})
				                           .get("/test2", (req, res) -> res.send(Flux.error(new Exception()))
				                                                           .then()
				                                                           .log("send-1")
				                                                           .doOnError(t -> errored1.countDown()))
				                           .get("/test3", (req, res) -> Flux.error(new Exception()))
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
				          .bindNow(Duration.ofSeconds(30));

		HttpClient client =
				HttpClient.create("localhost", server.address().getPort());

		Mono<Integer> code =
				client.get("/test")
				      .flatMap(res -> {
				          res.dispose();
				          return Mono.just(res.status().code());
				      })
				      .log("received-status-1");

		StepVerifier.create(code)
				    .expectNext(500)
				    .verifyComplete();

		Mono<ByteBuf> content =
				client.get("/test2")
				      .flatMapMany(res -> res.receive()
				                             .log("received-status-2"))
				      .next();

		StepVerifier.create(content)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Assertions.assertThat(errored1.await(30, TimeUnit.SECONDS)).isTrue();

		ByteBuf content1 = client.get("/issue231_1")
		                .flatMapMany(res -> res.receive()
		                                       .log("received-status-4"))
		                .next()
		                .block(Duration.ofSeconds(30));

		Assertions.assertThat(errored2.await(30, TimeUnit.SECONDS)).isTrue();

		content1 = client.get("/issue231_2")
		                .flatMapMany(res -> res.receive()
		                                       .log("received-status-4"))
		                .next()
		                .block(Duration.ofSeconds(30));

		Assertions.assertThat(errored3.await(30, TimeUnit.SECONDS)).isTrue();

		Flux<ByteBuf> content2 = client.get("/issue237_1")
		                .flatMapMany(res -> res.receive()
		                                       .log("received-status-5"));

		StepVerifier.create(content2)
		            .expectNextCount(4)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Assertions.assertThat(errored4.await(30, TimeUnit.SECONDS)).isTrue();

		content2 = client.get("/issue237_2")
		                .flatMapMany(res -> res.receive()
		                                       .log("received-status-6"));

		StepVerifier.create(content2)
		            .expectNextCount(4)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Assertions.assertThat(errored5.await(30, TimeUnit.SECONDS)).isTrue();

		code = client.get("/test3")
				     .flatMap(res -> {
				         res.dispose();
				         return Mono.just(res.status().code())
				                    .log("received-status-3");
				     });

		StepVerifier.create(code)
		            .expectNext(500)
		            .verifyComplete();

		server.dispose();
	}

	@Test
	public void webSocketRespondsToRequestsFromClients() {
		AtomicInteger clientRes = new AtomicInteger();
		AtomicInteger serverRes = new AtomicInteger();

		Connection server =
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
				          .wiretap()
				          .bindNow(Duration.ofSeconds(5));

		HttpClient client = HttpClient.create("localhost", server.address().getPort());

		Mono<List<String>> response =
		    client.get("/test/World", req ->
		              req.header("Content-Type", "text/plain")
		                 .header("test", "test")
		                 .options(c -> c.flushOnEach())
		                 .sendWebsocket()
		                 .sendString(Flux.range(1, 1000)
		                                 .log("client-send")
		                                 .map(i -> "" + i)))
		          .flatMapMany(res -> res.receive()
		                                 .asString()
		                                 .log("client-received")
		                                 .publishOn(Schedulers.parallel())
		                                 .doOnNext(s -> clientRes.incrementAndGet()))
		          .take(1000)
		          .collectList()
		          .cache()
		          .doOnError(i -> System.err.println("Failed requesting server: " + i));

		System.out.println("STARTING: server[" + serverRes.get() + "] / client[" + clientRes.get() + "]");

		StepVerifier.create(response)
		            .expectNextMatches(list -> "1000 World!".equals(list.get(999)))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		System.out.println("FINISHED: server[" + serverRes.get() + "] / client[" + clientRes + "]");

		server.dispose();
	}
}
