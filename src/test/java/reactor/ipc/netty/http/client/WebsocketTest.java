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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import static org.hamcrest.CoreMatchers.is;

/**
 * @author tjreactive
 * @author smaldini
 */
public class WebsocketTest {

	static final String auth = "bearer abc";

	DisposableServer httpServer = null;

	@After
	public void disposeHttpServer() {
		if (httpServer != null)
			httpServer.dispose();
	}

	@Test
	public void simpleTest() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                       Mono.just("test"))))
		                       .wiretap()
		                       .bindNow();

		String res =
				HttpClient.prepare()
				          .port(httpServer.address().getPort())
				          .wiretap()
				          .request(HttpMethod.GET)
				          .uri("/test")
				          .send((req, out) -> req.addHeader("Authorization", auth)
				                                 .sendWebsocket())
				          .responseContent()
				          .asString()
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30))
				          .get(0);

		Assert.assertThat(res, is("test"));
	}

	@Test
	public void serverWebSocketFailed() {
		httpServer =
				HttpServer.create()
						.port(0)
						.handler((in, out) -> {
							if (!in.requestHeaders().contains("Authorization")) {
								return out.status(401);
							} else {
								return out.sendWebsocket((i, o) -> o.sendString(Mono.just("test")));
							}
						})
						.wiretap()
						.bindNow();

		Mono<String> res =
				HttpClient.prepare()
						.port(httpServer.address().getPort())
						.request(HttpMethod.GET)
						.uri("/test")
						.send((req, out) -> req.sendWebsocket())
						.responseContent()
						.aggregate()
						.asString();

		StepVerifier.create(res)
				.expectError(WebSocketHandshakeException.class)
				.verify(Duration.ofSeconds(30));
	}

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
				          .wiretap()
				          .bindNow(Duration.ofSeconds(5));

		HttpClient client = HttpClient.prepare()
		                              .port(server.address().getPort())
		                              .wiretap();

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

//	static final byte[] testData;
//
//	static {
//		testData = new byte[10000];
//		for (int i = 0; i < testData.length; i++) {
//			testData[i] = 88;
//		}
//
//	}
//
//	@Test
//	public void largeChunk() throws Exception {
//		httpServer = HttpServer.create(0)
//		                       .newHandler((in, out) -> out.sendWebsocket((i, o) -> o
//				                       .sendByteArray(Mono.just(testData))
//		                                                                             .neverComplete()))
//		                       .block(Duration.ofSeconds(30));
//
//		HttpClient.create(httpServer.address()
//		                                         .getPort())
//		                       .get("/test",
//				                       out -> out.addHeader("Authorization", auth)
//				                                 .sendWebsocket())
//		                       .flatMapMany(in -> in.receiveWebsocket()
//		                                        .receive()
//		                                        .asByteArray())
//		                       .doOnNext(d -> System.out.println(d.length))
//		                       .log()
//		                       .subscribe();
//
//		Thread.sleep(200000);
//	}

	/* TODO ws?
	@Test
	public void unidirectional() {
		int c = 10;
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(opt -> opt.flushOnEach())
				                                  .sendString(
						                                  Mono.just("test")
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .wiretap()
		                       .bindNow();

		Flux<String> ws = HttpClient.prepare()
		                            .port(httpServer.address().getPort())
		                            .tcpConfiguration(tcpClient -> tcpClient.noSSL())
		                            .wiretap()
		                            .ws()
		                            .uri("/")
		                            .flatMapMany(in -> in.receiveWebsocket()
		                                             .aggregateFrames()
		                                             .receive()
		                                             .asString());

		StepVerifier.create(ws.take(c)
		                      .log())
		            .expectNextSequence(Flux.range(1, c)
		                                    .map(v -> "test")
		                                    .toIterable())
		            .expectComplete()
		            .verify();
	}


	@Test
	public void unidirectionalBinary() {
		int c = 10;
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(opt -> opt.flushOnEach())
				                                  .sendByteArray(
						                                  Mono.just("test".getBytes(Charset.defaultCharset()))
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .wiretap()
		                       .bindNow();

		Flux<String> ws = HttpClient.create(httpServer.address()
		                                              .getPort())
		                            .ws("/")
		                            .flatMapMany(in -> in.receiveWebsocket()
		                                             .aggregateFrames()
		                                             .receive()
		                                             .asString());

		StepVerifier.create(ws.take(c)
		                      .log())
		            .expectNextSequence(Flux.range(1, c)
		                                    .map(v -> "test")
		                                    .toIterable())
		            .expectComplete()
		            .verify();
	}

	@Test
	public void duplexEcho() throws Exception {

		int c = 10;
		CountDownLatch clientLatch = new CountDownLatch(c);
		CountDownLatch serverLatch = new CountDownLatch(c);

		FluxProcessor<String, String> server =
				ReplayProcessor.<String>create().serialize();
		FluxProcessor<String, String> client =
				ReplayProcessor.<String>create().serialize();

		server.log("server")
		      .subscribe(v -> serverLatch.countDown());
		client.log("client")
		      .subscribe(v -> clientLatch.countDown());

		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                       i.receive()
				                        .asString()
				                        .take(c)
				                        .subscribeWith(server))))
		                       .wiretap()
		                       .bindNow();

		Flux.interval(Duration.ofMillis(200))
		    .map(Object::toString)
		    .subscribe(client::onNext);

		HttpClient.create(httpServer.address()
		                            .getPort())
		          .ws("/test")
		          .flatMap(in -> in.receiveWebsocket((i, o) -> o.options(opt -> opt.flushOnEach())
		                                                     .sendString(i.receive()
		                                                                  .asString()
		                                                                  .subscribeWith(
				                                                                  client))))
		          .subscribe();

		Assert.assertTrue(serverLatch.await(10, TimeUnit.SECONDS));
		Assert.assertTrue(clientLatch.await(10, TimeUnit.SECONDS));
	}

	@Test
	public void simpleSubprotocolServerNoSubprotocol() throws Exception {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                                    Mono.just("test"))))
		                       .wiretap()
		                       .bindNow();

		StepVerifier.create(
				HttpClient.create(
						httpServer.address().getPort())
				          .get("/test",
						          out -> out.addHeader("Authorization", auth)
						                    .sendWebsocket("SUBPROTOCOL,OTHER"))
				          .flatMapMany(in -> in.receiveWebsocket().receive().asString())
		)
		            .verifyErrorMessage("Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	public void simpleSubprotocolServerNotSupported() throws Exception {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket(
				                       "protoA,protoB",
				                       (i, o) -> {
				                       	return o.sendString(Mono.just("test"));
				                       }))
		                       .wiretap()
		                       .bindNow();

		StepVerifier.create(
				HttpClient.create(
						httpServer.address().getPort())
				          .get("/test",
						          out -> out.addHeader("Authorization", auth)
						                    .sendWebsocket("SUBPROTOCOL,OTHER"))
				          .flatMapMany(in -> in.receiveWebsocket().receive().asString())
		)
		            //the SERVER returned null which means that it couldn't select a protocol
		            .verifyErrorMessage("Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}
*/
	@Test
	public void simpleSubprotocolServerSupported() throws Exception {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket(
				                       "SUBPROTOCOL",
				                       (i, o) -> o.sendString(
						                       Mono.just("test"))))
		                       .wiretap()
		                       .bindNow();

		String res = HttpClient.prepare()
		                       .port(httpServer.address().getPort())
		                       .wiretap()
		                       .request(HttpMethod.GET)
		                       .uri("/test")
		                       .send((req, out) -> req.addHeader("Authorization", auth)
		                                              .sendWebsocket("SUBPROTOCOL,OTHER"))
		                       .responseContent()
		                       .asString()
		                       .log()
		                       .collectList()
		                       .block(Duration.ofSeconds(30)).get(0);

		Assert.assertThat(res, is("test"));
	}
/*
	@Test
	public void simpleSubprotocolSelected() throws Exception {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket(
				                       "NOT, Common",
				                       (i, o) -> o.sendString(
						                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .wiretap()
		                       .bindNow();

		String res = HttpClient.prepare()
		                       .port(httpServer.address().getPort())
		                       .tcpConfiguration(tcpClient -> tcpClient.noSSL())
		                       .wiretap()
		                       .request(HttpMethod.GET)
		                       .uri("/test")
		                       .send((req, out) -> req.addHeader("Authorization", auth)
		                                              .sendWebsocket("Common,OTHER"))
		                       .map(HttpClientResponse::receiveWebsocket)
		                       .flatMapMany(in -> in.receive().asString()
				                       .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
		                       .log().collectList().block(Duration.ofSeconds(30)).get(0);

		Assert.assertThat(res, is("CLIENT:Common-SERVER:Common"));
	}

	@Test
	public void noSubprotocolSelected() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .wiretap()
		                       .bindNow();

		String res = HttpClient.create(httpServer.address()
		                                         .getPort())
		                       .get("/test",
				                       out -> out.addHeader("Authorization", auth)
				                                 .sendWebsocket())
		                       .map(HttpClientResponse::receiveWebsocket)
		                       .flatMapMany(in -> in.receive()
		                                        .asString()
		                                        .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
		                       .log()
		                       .collectList()
		                       .block(Duration.ofSeconds(30))
		                       .get(0);

		Assert.assertThat(res, is("CLIENT:null-SERVER:null"));
	}

	@Test
	public void anySubprotocolSelectsFirstClientProvided() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket("proto2,*", (i, o) -> o.sendString(
				                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .wiretap()
		                       .bindNow();

		String res = HttpClient.create(httpServer.address()
		                                         .getPort())
		                       .get("/test",
				                       out -> out.addHeader("Authorization", auth)
				                                 .sendWebsocket("proto1, proto2"))
		                       .map(HttpClientResponse::receiveWebsocket)
		                       .flatMapMany(in -> in.receive()
		                                        .asString()
		                                        .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
		                       .log()
		                       .collectList()
		                       .block(Duration.ofSeconds(30))
		                       .get(0);

		Assert.assertThat(res, is("CLIENT:proto1-SERVER:proto1"));
	}

	@Test
	public void sendToWebsocketSubprotocol() throws InterruptedException {
		AtomicReference<String> serverSelectedProtocol = new AtomicReference<>();
		AtomicReference<String> clientSelectedProtocol = new AtomicReference<>();
		AtomicReference<String> clientSelectedProtocolWhenSimplyUpgrading = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket(
		                       		"not,proto1", (i, o) -> {
					                       serverSelectedProtocol.set(i.selectedSubprotocol());
					                       latch.countDown();
					                       return i.receive()
					                               .asString()
					                               .doOnNext(System.err::println)
					                               .then();
				                       })
		                       )
		                       .wiretap()
		                       .bindNow();

		HttpClient.create(httpServer.address()
		                            .getPort())
		          .ws("/test", "proto1,proto2")
		          .flatMap(in -> {
			          clientSelectedProtocolWhenSimplyUpgrading.set(in.receiveWebsocket().selectedSubprotocol());
			          return in.receiveWebsocket((i, o) -> {
				          clientSelectedProtocol.set(o.selectedSubprotocol());
				          return o.sendString(Mono.just("HELLO" + o.selectedSubprotocol()));
			          });
		          })
		          .block(Duration.ofSeconds(30));

		Assert.assertTrue(latch.await(30, TimeUnit.SECONDS));
		Assert.assertThat(serverSelectedProtocol.get(), is("proto1"));
		Assert.assertThat(clientSelectedProtocol.get(), is("proto1"));
		Assert.assertThat(clientSelectedProtocolWhenSimplyUpgrading.get(), is("proto1"));
	}*/


	//TODO
/*	@Test
	public void closePool() {
		PoolResources pr = PoolResources.fixed("wstest", 1);
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(opt -> opt.flushOnEach())
				                                  .sendString(
						                                  Mono.just("test")
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .wiretap()
		                       .bindNow();

		Flux<String> ws = HttpClient.create(opts -> opts.port(httpServer.address()
		                                                                .getPort())
		                                                .poolResources(pr))
		                            .ws("/")
		                            .flatMapMany(in -> in.receiveWebsocket()
		                                                 .aggregateFrames()
		                                                 .receive()
		                                                 .asString());

		StepVerifier.create(
				Flux.range(1, 10)
				    .concatMap(i -> ws.take(2)
				                      .log())
		)
		            .expectNextSequence(Flux.range(1, 20)
		                                    .map(v -> "test")
		                                    .toIterable())
		            .expectComplete()
		            .verify();

		pr.dispose();
	}

	@Test
	public void testCloseWebSocketFrameSentByServer() {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handler((req, res) ->
				                  res.sendWebsocket((in, out) -> out.sendObject(in.receiveFrames()
				                                                                  .doOnNext(WebSocketFrame::retain))))
				          .wiretap()
				          .bindNow();

		Flux<WebSocketFrame> response =
				HttpClient.create(httpServer.address().getPort())
				          .get("/", req -> req.sendWebsocket()
				                                  .sendString(Mono.just("echo"))
				                                  .sendObject(new CloseWebSocketFrame()))
				          .flatMapMany(res -> res.receiveWebsocket()
				                                 .receiveFrames());

		StepVerifier.create(response)
		            .expectNextMatches(webSocketFrame ->
		                    webSocketFrame instanceof TextWebSocketFrame &&
		                    "echo".equals(((TextWebSocketFrame) webSocketFrame).text()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testCloseWebSocketFrameSentByClient() {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handler((req, res) ->
				                  res.sendWebsocket((in, out) -> out.sendString(Mono.just("echo"))
				                                                    .sendObject(new CloseWebSocketFrame())))
				          .wiretap()
				          .bindNow();

		Mono<Void> response =
				HttpClient.create(httpServer.address().getPort())
				          .ws("/")
				          .flatMap(res ->
				                  res.receiveWebsocket((in, out) -> out.sendObject(in.receiveFrames()
				                                                                     .doOnNext(WebSocketFrame::retain))));

		StepVerifier.create(response)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testConnectionAliveWhenTransformationErrors_1() {
		doTestConnectionAliveWhenTransformationErrors((in, out) ->
		        out.options(sendOptions -> sendOptions.flushOnEach())
		           .sendObject(in.aggregateFrames()
		                         .receiveFrames()
		                         .map(WebSocketFrame::content)
		                         //.share()
		                         .publish()
		                         .autoConnect()
		                         .map(byteBuf -> byteBuf.toString(Charset.defaultCharset()))
		                         .map(Integer::parseInt)
		                         .map(i -> new TextWebSocketFrame(i + ""))
		                         .retry()),
		       Flux.just("1", "2"), 2);
	}

	@Test
	public void testConnectionAliveWhenTransformationErrors_2() {
		doTestConnectionAliveWhenTransformationErrors((in, out) ->
		        out.options(sendOptions -> sendOptions.flushOnEach())
		           .sendObject(in.aggregateFrames()
		                         .receiveFrames()
		                         .map(WebSocketFrame::content)
		                         .concatMap(content ->
		                             Mono.just(content)
		                                 .map(byteBuf -> byteBuf.toString(Charset.defaultCharset()))
		                                 .map(Integer::parseInt)
		                                 .map(i -> new TextWebSocketFrame(i + ""))
		                                 .onErrorResume(t -> Mono.just(new TextWebSocketFrame("error"))))),
				Flux.just("1", "error", "2"), 3);
	}

	private void doTestConnectionAliveWhenTransformationErrors(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> handler,
			Flux<String> expectation, int count) {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handler((req, res) -> res.sendWebsocket(handler))
				          .wiretap()
				          .bindNow();

		ReplayProcessor<String> output = ReplayProcessor.create();
		HttpClient.create(httpServer.address().getPort())
		          .ws("/")
		          .flatMap(res ->
		                  res.receiveWebsocket((in, out) -> out.sendString(Flux.just("1", "text", "2"))
		                                                       .then(in.aggregateFrames()
		                                                               .receiveFrames()
		                                                               .map(WebSocketFrame::content)
		                                                               .map(byteBuf -> byteBuf.toString(Charset.defaultCharset()))
		                                                               .take(count)
		                                                               .subscribeWith(output)
		                                                               .then())))
		          .block(Duration.ofSeconds(30));

		Assertions.assertThat(output.collectList().block(Duration.ofSeconds(30)))
		          .isEqualTo(expectation.collectList().block(Duration.ofSeconds(30)));

	}

	@Test
	public void testClientOnCloseIsInvokedClientDisposed() throws Exception {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handler((req, res) ->
				              res.options(sendOptions -> sendOptions.flushOnEach())
				                 .sendWebsocket((in, out) ->
				                     out.sendString(Flux.interval(Duration.ofSeconds(1))
				                                        .map(l -> l + ""))))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(3);
		AtomicBoolean error = new AtomicBoolean();
		HttpClient.prepare()
		          .port(httpServer.address().getPort())
		          .ws("/test")
		          .flatMap(res -> res.receiveWebsocket((in, out) -> {
		              in.withConnection(conn -> {
		                  Mono.delay(Duration.ofSeconds(3))
		                      .subscribe(c -> {
		                              System.out.println("context.dispose()");
		                              conn.dispose();
		                              latch.countDown();
		                      });
		                  conn.onDispose()
		                         .subscribe(
		                                 c -> { // no-op
		                                 },
		                                 t -> {
		                                     t.printStackTrace();
		                                     error.set(true);
		                                 },
		                                 () -> {
		                                     System.out.println("context.onClose() completed");
		                                     latch.countDown();
		                                 });
		              });
		                  Mono.delay(Duration.ofSeconds(3))
		                      .repeat(() -> {
		                          AtomicBoolean disposed = new AtomicBoolean(false);
		                          in.withConnection(conn -> {
		                              disposed.set(conn.isDisposed());
		                              System.out.println("context.isDisposed() " + conn.isDisposed());
		                          });
		                          if (disposed.get()) {
		                              latch.countDown();
		                              return false;
		                          }
		                          return true;
		                      })
		                      .subscribe();
		                  return Mono.delay(Duration.ofSeconds(7))
		                             .then();
		          }))
		          .block(Duration.ofSeconds(30));

		latch.await(30, TimeUnit.SECONDS);

		Assertions.assertThat(error.get()).isFalse();
	}

	@Test
	public void testClientOnCloseIsInvokedServerInitiatedClose() throws Exception {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handler((req, res) ->
				              res.sendWebsocket((in, out) ->
				                  out.sendString(Mono.just("test"))))
				          .wiretap()
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(2);
		AtomicBoolean error = new AtomicBoolean();
		HttpClient.create(httpServer.address().getPort())
		          .ws("/test")
		          .flatMap(res -> res.receiveWebsocket((in, out) -> {
		              in.withConnection(conn ->
		                 conn.onDispose()
		                     .subscribe(
		                             c -> { // no-op
		                             },
		                             t -> {
		                                 t.printStackTrace();
		                                 error.set(true);
		                             },
		                             () -> {
		                                 System.out.println("context.onClose() completed");
		                                 latch.countDown();
		                             }));
		              Mono.delay(Duration.ofSeconds(3))
		                  .repeat(() -> {
		                      AtomicBoolean disposed = new AtomicBoolean(false);
		                      in.withConnection(conn -> {
		                          disposed.set(conn.isDisposed());
		                          System.out.println("context.isDisposed() " + conn.isDisposed());
		                      });
		                      if (disposed.get()) {
		                          latch.countDown();
		                          return false;
		                      }
		                      return true;
		                  })
		                  .subscribe();
		              return Mono.delay(Duration.ofSeconds(7))
		                         .then();
		          }))
		          .block(Duration.ofSeconds(30));

		latch.await(30, TimeUnit.SECONDS);

		Assertions.assertThat(error.get()).isFalse();
	}*/
}
