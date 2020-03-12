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
package reactor.netty.http;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.assertj.core.api.Assertions;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.tcp.SslProvider;
import reactor.test.StepVerifier;

/**
 * @author Violeta Georgieva
 */
public class HttpTests {

	@Test
	public void httpRespondsEmpty() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .route(r ->
				              r.post("/test/{param}", (req, res) -> Mono.empty()))
				          .wiretap(true)
				          .bindNow();

		HttpClient client =
				HttpClient.create()
				          .port(server.address().getPort())
				          .wiretap(true);

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

		server.disposeNow();
	}

	@Test
	public void httpRespondsToRequestsFromClients() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .route(r ->
				              r.post("/test/{param}", (req, res) ->
				                  res.sendString(req.receive()
				                                    .asString()
				                                    .log("server-received")
				                                    .map(it -> it + ' ' + req.param("param") + '!')
				                                    .log("server-reply"))))
				          .wiretap(true)
				          .bindNow();

		HttpClient client =
				HttpClient.create()
				          .port(server.address().getPort())
				          .wiretap(true);

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

		server.disposeNow();
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

		DisposableServer server =
				HttpServer.create()
				          .port(0)
						  .route(r -> r.get("/test", (req, res) -> {throw new RuntimeException("test");})
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
						  .wiretap(true)
						  .bindNow();

		HttpClient client =
				HttpClient.create()
				          .port(server.address().getPort())
				          .wiretap(true);

		Mono<Integer> code =
				client.get()
				      .uri("/test")
				      .responseSingle((res, buf) -> Mono.just(res.status().code()))
				      .log("received-status-1");

		StepVerifier.create(code)
				    .expectNext(500)
				    .verifyComplete();

		Mono<ByteBuf> content =
				client.get()
				      .uri("/test2")
				      .responseContent()
				      .log("received-status-2")
				      .next();

		StepVerifier.create(content)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Assertions.assertThat(errored1.await(30, TimeUnit.SECONDS)).isTrue();

		client.get()
		      .uri("/issue231_1")
		      .responseContent()
		      .log("received-status-3")
		      .next()
		      .block(Duration.ofSeconds(30));

		Assertions.assertThat(errored2.await(30, TimeUnit.SECONDS)).isTrue();

		client.get()
		      .uri("/issue231_2")
		      .responseContent()
		      .log("received-status-4")
		      .next()
		      .block(Duration.ofSeconds(30));

		Assertions.assertThat(errored3.await(30, TimeUnit.SECONDS)).isTrue();

		Flux<ByteBuf> content2 = client.get()
		                               .uri("/issue237_1")
		                               .responseContent()
		                               .log("received-status-5");

		StepVerifier.create(content2)
		            .expectNextCount(4)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Assertions.assertThat(errored4.await(30, TimeUnit.SECONDS)).isTrue();

		content2 = client.get()
		                 .uri("/issue237_2")
		                 .responseContent()
		                 .log("received-status-6");

		StepVerifier.create(content2)
		            .expectNextCount(4)
		            .expectError(IOException.class)
		            .verify(Duration.ofSeconds(30));

		Assertions.assertThat(errored5.await(30, TimeUnit.SECONDS)).isTrue();

		code = client.get()
				     .uri("/test3")
				     .responseSingle((res, buf) -> Mono.just(res.status().code())
				                                       .log("received-status-7"));

		StepVerifier.create(code)
		            .expectNext(500)
		            .verifyComplete();

		server.disposeNow();
	}

/*	@Test
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
	}*/

	@Test
	public void test100Continue() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> req.receive()
				                                   .aggregate()
				                                   .asString()
				                                   .flatMap(s -> {
					                                       latch.countDown();
					                                       return res.sendString(Mono.just(s))
					                                                 .then();
				                                       }))
				          .wiretap(true)
				          .bindNow();

		String content =
				HttpClient.create()
				          .port(server.address().getPort())
				          .headers(h -> h.add("Expect", "100-continue"))
				          .post()
				          .uri("/")
				          .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
				          .responseContent()
				          .aggregate()
				          .asString()
				          .block();

		System.out.println(content);

		Assertions.assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		server.disposeNow();
	}

	@Test
	public void streamAndPoolExplicitCompression() {
		EmitterProcessor<String> ep = EmitterProcessor.create();

		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .route(r -> r.post("/hi", (req, res) -> req.receive()
				                                                     .aggregate()
				                                                     .asString()
				                                                     .log()
				                                                     .then(res.sendString(Flux.just("test")).then()))
				                       .get("/stream", (req, res) ->
						                           req.receive()
						                              .then(res.compression(true)
						                                       .sendString(ep.log()).then())))
				          .wiretap(true)
				          .bindNow();


		String content =
				HttpClient.create()
				          .port(server.address().getPort())
				          .compress(true)
				          .post()
				          .uri("/hi")
				          .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
				          .responseContent()
				          .aggregate()
				          .asString()
				          .log()
				          .block();

		Flux<String> f = HttpClient.create()
		                           .port(server.address().getPort())
		                           .compress(true)
		                           .get()
		                           .uri("/stream")
		                           .responseContent()
		                           .asString();
		System.out.println(content);

		StepVerifier.create(f)
		            .then(() -> ep.onNext("test1"))
		            .expectNext("test1")
		            .thenAwait(Duration.ofMillis(30))
		            .then(() -> ep.onNext("test2"))
		            .thenAwait(Duration.ofMillis(30))
		            .expectNext("test2")
		            .thenAwait(Duration.ofMillis(30))
		            .then(ep::onComplete)
		            .verifyComplete();



		HttpClient.create()
		          .port(server.address().getPort())
		          .compress(true)
		          .post()
		          .uri("/hi")
		          .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .log()
		          .block();


		server.disposeNow();
	}


	@Test
	public void streamAndPoolDefaultCompression() {
		EmitterProcessor<String> ep = EmitterProcessor.create();

		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .compress(true)
				          .route(r -> r.post("/hi", (req, res) -> req.receive()
				                                                     .aggregate()
				                                                     .asString()
				                                                     .log()
				                                                     .then(res.compression(false)
				                                                                  .sendString(Flux.just("test")).then()))
				                       .get("/stream", (req, res) ->
						                           req.receive()
						                              .then(res.sendString(ep.log()).then())))
				          .wiretap(true)
				          .bindNow();


		String content =
				HttpClient.create()
				          .port(server.address().getPort())
				          .compress(true)
				          .post()
				          .uri("/hi")
				          .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
				          .responseContent()
				          .aggregate()
				          .asString()
				          .log()
				          .block();

		Flux<String> f = HttpClient.create()
		                           .port(server.address().getPort())
		                           .compress(true)
		                           .get()
		                           .uri("/stream")
		                           .responseContent()
		                           .asString();
		System.out.println(content);

		StepVerifier.create(f)
		            .then(() -> ep.onNext("test1"))
		            .expectNext("test1")
		            .thenAwait(Duration.ofMillis(30))
		            .then(() -> ep.onNext("test2"))
		            .thenAwait(Duration.ofMillis(30))
		            .expectNext("test2")
		            .thenAwait(Duration.ofMillis(30))
		            .then(ep::onComplete)
		            .verifyComplete();



		HttpClient.create()
		          .port(server.address().getPort())
		          .compress(true)
		          .post()
		          .uri("/hi")
		          .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
		          .responseContent()
		          .aggregate()
		          .asString()
		          .log()
		          .block();


		server.disposeNow();
	}

	@Test
//	@Ignore
	public void testHttpToHttp2Ssl() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		DisposableServer server =
				HttpServer.create()
				          .secure(sslContextSpec -> sslContextSpec.sslContext(serverOptions)
				                                                  .defaultConfiguration(SslProvider.DefaultConfigurationType.H2))
				          .handle((req, res) -> res.sendString(Mono.just("Hello")))
				          .wiretap(true)
				          .bindNow();

		HttpClient.create()
		          .port(server.port())
		          .secure(ssl -> ssl.sslContext(
		                  SslContextBuilder.forClient()
		                                   .trustManager(InsecureTrustManagerFactory.INSTANCE)))
		          .wiretap(true)
		          .get()
		          .uri("/")
		          .responseContent()
		          .aggregate()
		          .asString()
		          .block(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	@Ignore
	public void testNettyOom() throws Exception {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 1000; i++) {
			sb.append("test");
		}

		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		DisposableServer server =
				HttpServer.create()
				          .secure(sslContextSpec -> sslContextSpec.sslContext(serverOptions))
				          .handle((req, res) -> res.sendString(Mono.just("Hello "+Mono.just(sb.toString()))
				                                                   .delayElement
						                                                   (Duration.ofMillis(500))))
				          .wiretap(true)
				          .bindNow();

		Mono<String> res =
				HttpClient.create()
				          .port(server.port())
				          .secure(ssl -> ssl.sslContext(
						          SslContextBuilder.forClient()
						                           .trustManager
								                           (InsecureTrustManagerFactory
										                           .INSTANCE))
				                            .defaultConfiguration(SslProvider
						                            .DefaultConfigurationType.TCP)
				                            .handshakeTimeoutMillis(30000))
				          .wiretap(true)
				          .post()
				          .uri("/")
				          .send(ByteBufFlux.fromString(Mono.just(sb.toString())))
				          .responseContent()
				          .aggregate()
				          .asString();

		Mono.just(1)
		    .repeat()
			.flatMap(i -> res.subscribeOn(Schedulers.elastic()))
			.blockLast();

		server.disposeNow();
	}


	@Test
	@Ignore
	public void testHttpSsl() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		DisposableServer server =
				HttpServer.create()
				          .port(8080)
				          .secure(sslContextSpec -> sslContextSpec.sslContext(serverOptions))
				          .handle((req, res) -> res.sendString(Mono.just("Hello")))
				          .wiretap(true)
				          .bindNow();

		new CountDownLatch(1).await();
		server.disposeNow();
	}

//	@Test
//	public void testHttpToHttp2ClearText() {
//		DisposableServer server =
//				HttpServer.create()
//				          .protocol(HttpProtocol.H2C)
//				          .handle((req, res) -> res.sendString(Mono.just("Hello")))
//				          .wiretap(true)
//				          .bindNow();
//
//		StepVerifier.create(
//				HttpClient.create()
//				          .port(server.port())
//				          .protocol(HttpProtocol.H2C)
//				          .wiretap(true)
//				          .post()
//				          .uri("/")
////				          .send((req, out) -> out.sendString(Mono.just("World")))
//				          .responseContent()
//				          .aggregate()
//				          .asString()
//		)
//		            .expectNext("Hello")
//		            .verifyComplete();
//
//
//		server.disposeNow();
//	}

	@Test
	@Ignore
	public void testH2PriorKnowledge() throws Exception {
//		SelfSignedCertificate cert = new SelfSignedCertificate();
//		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());

		DisposableServer server =
				HttpServer.create()
				          .protocol(HttpProtocol.H2C)
				          .port(8080)
				          .handle((req, res) -> res.sendString(Mono.just("Hello")))
				          .wiretap(true)
				          .bindNow();

		new CountDownLatch(1).await();
		server.disposeNow();
	}

	@Test
	@Ignore
	public void testHttp1or2() throws Exception {
//		SelfSignedCertificate cert = new SelfSignedCertificate();
//		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());

		DisposableServer server =
				HttpServer.create()
				          .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
				          .port(8080)
				          .handle((req, res) -> res.sendString(Mono.just("Hello")))
				          .wiretap(true)
				          .bindNow();

		new CountDownLatch(1).await();
		server.disposeNow();
	}

	@Test
	@Ignore
	public void testH2Secure() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());

		DisposableServer server =
				HttpServer.create()
				          .protocol(HttpProtocol.H2)
				          .secure(ssl -> ssl.sslContext(serverOptions))
				          .port(8080)
				          .handle((req, res) -> res.sendString(Mono.just("Hello")))
				          .wiretap(true)
				          .bindNow();

		new CountDownLatch(1).await();
		server.disposeNow();
	}

	@Test
	@Ignore
	public void testH2OrH1Secure() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());

		DisposableServer server =
				HttpServer.create()
				          .protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
				          .secure(ssl -> ssl.sslContext(serverOptions))
				          .port(8080)
				          .handle((req, res) -> res.sendString(Mono.just("Hello")))
				          .wiretap(true)
				          .bindNow();

		new CountDownLatch(1).await();
		server.disposeNow();
	}

	@Test
	@Ignore
	public void testIssue395() throws Exception {
		BiFunction<HttpServerRequest, HttpServerResponse, Mono<Void>> echoHandler =
				(req, res) -> res.send(req.receive().map(ByteBuf::retain)).then();

		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		DisposableServer server =
				HttpServer.create()
				          .secure(ssl -> ssl.sslContext(serverOptions))
				          .protocol(HttpProtocol.H2)
				          .handle(echoHandler)
				          .port(8080)
				          .wiretap(true)
				          .bindNow();

		new CountDownLatch(1).await();
		server.disposeNow();
	}

	@Test
	@Ignore
	public void testHttp1or2Secure() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());

		DisposableServer server =
				HttpServer.create()
				          .protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
				          .secure(ssl -> ssl.sslContext(serverOptions))
				          .port(8080)
				          .handle((req, res) -> res.sendString(req.receive().aggregate().retain().asString()))
				          .wiretap(true)
				          .bindNow();

		new CountDownLatch(1).await();
		server.disposeNow();
	}

	@Test
	public void testHttpNoSslH2Fails()  {
		StepVerifier.create(
			HttpServer.create()
			          .protocol(HttpProtocol.H2)
			          .handle((req, res) -> res.sendString(Mono.just("Hello")))
			          .wiretap(true)
			          .bind()
		).verifyErrorMessage("Configured H2 protocol without TLS. Use" +
				" a clear-text h2 protocol via HttpServer#protocol or configure TLS" +
				" via HttpServer#secure");
	}

	@Test
	public void testHttpSslH2CFails()  throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());

		StepVerifier.create(
			HttpServer.create()
			          .protocol(HttpProtocol.H2C)
			          .secure(ssl -> ssl.sslContext(serverOptions))
			          .handle((req, res) -> res.sendString(Mono.just("Hello")))
			          .wiretap(true)
			          .bind()
		).verifyErrorMessage("Configured H2 Clear-Text protocol with TLS. Use the non clear-text h2 protocol via HttpServer#protocol or disable TLS via HttpServer#tcpConfiguration(tcp -> tcp.noSSL())");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testIssue387() {
		HttpServer.create()
		          .secure(sslContextSpec -> System.out.println())
		          .bindNow();
	}
}
