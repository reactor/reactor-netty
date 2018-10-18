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

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;
import reactor.ipc.netty.resources.PoolResources;
import reactor.test.StepVerifier;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author tjreactive
 * @author smaldini
 */
public class WebsocketTest {

	static final String auth = "bearer abc";

	NettyContext httpServer = null;

	@After
	public void disposeHttpServer() {
		if (httpServer != null)
			httpServer.dispose();
	}

	@Test
	public void simpleTest() {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                       Mono.just("test"))))
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		String res = Objects.requireNonNull(
		        HttpClient.create(httpServer.address()
		                                    .getPort())
		                  .get("/test",
		                          out -> out.addHeader("Authorization", auth)
		                                    .sendWebsocket())
		                  .flatMapMany(in -> in.receive()
		                                       .asString())
		                  .log()
		                  .collectList()
		                  .block(Duration.ofSeconds(30))).get(0);

		Assert.assertThat(res, is("test"));
	}

	@Test
	public void serverWebSocketFailed() {
		httpServer =
				HttpServer.create(0)
				          .newHandler((in, out) -> {
				                  if (!in.requestHeaders().contains("Authorization")) {
				                      return out.status(401);
				                  }
				                  else {
				                      return out.sendWebsocket((i, o) -> o.sendString(Mono.just("test")));
				                  }
				          })
				          .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		Mono<String> res =
				HttpClient.create(httpServer.address().getPort())
				          .get("/test", out -> out.failOnClientError(false)
				                                      .sendWebsocket())
				          .flatMap(in -> in.receive().aggregate().asString());

		StepVerifier.create(res)
		            .expectError(WebSocketHandshakeException.class)
		            .verify(Duration.ofSeconds(30));
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

	@Test
	public void unidirectional() {
		int c = 10;
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(NettyPipeline.SendOptions::flushOnEach)
				                                  .sendString(
						                                  Mono.just("test")
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		Flux<String> ws = HttpClient.create(httpServer.address()
		                                              .getPort())
		                            .ws("/")
		                            .flatMapMany(in -> in.receiveWebsocket()
		                                             .aggregateFrames()
		                                             .receive()
		                                             .asString());

		StepVerifier.create(ws.take(c)
		                      .log())
		            .expectNextSequence(
		                    Objects.requireNonNull(Flux.range(1, c)
		                                               .map(v -> "test")
		                                               .collectList()
		                                               .block()))
		            .expectComplete()
		            .verify();
	}


	@Test
	public void unidirectionalBinary() {
		int c = 10;
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(NettyPipeline.SendOptions::flushOnEach)
				                                  .sendByteArray(
						                                  Mono.just("test".getBytes(Charset.defaultCharset()))
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		Flux<String> ws = HttpClient.create(httpServer.address()
		                                              .getPort())
		                            .ws("/")
		                            .flatMapMany(in -> in.receiveWebsocket()
		                                             .aggregateFrames()
		                                             .receive()
		                                             .asString());

		StepVerifier.create(ws.take(c)
		                      .log())
		            .expectNextSequence(
		                    Objects.requireNonNull(Flux.range(1, c)
		                                               .map(v -> "test")
		                                               .collectList()
		                                               .block()))
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

		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                       i.receive()
				                        .asString()
				                        .take(c)
				                        .subscribeWith(server))))
		                       .block(Duration.ofSeconds(30));

		Flux.interval(Duration.ofMillis(200))
		    .map(Object::toString)
		    .subscribe(client::onNext);

		HttpClient.create(httpServer.address()
		                            .getPort())
		          .ws("/test")
		          .flatMap(in -> in.receiveWebsocket((i, o) -> o.options(NettyPipeline.SendOptions::flushOnEach)
		                                                     .sendString(i.receive()
		                                                                  .asString()
		                                                                  .subscribeWith(
				                                                                  client))))
		          .subscribe();

		assertTrue(serverLatch.await(10, TimeUnit.SECONDS));
		assertTrue(clientLatch.await(10, TimeUnit.SECONDS));
	}

	@Test
	public void simpleSubprotocolServerNoSubprotocol() {
		httpServer = HttpServer.create(0)
		                                    .newHandler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                                    Mono.just("test"))))
		                                    .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		StepVerifier.create(
				HttpClient.create(
						httpServer.address().getPort())
				          .get("/test",
						          out -> out.addHeader("Authorization", auth)
						                    .sendWebsocket("SUBPROTOCOL,OTHER"))
				          .flatMapMany(in -> in.receive().asString())
		)
		            .verifyErrorMessage("Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	public void simpleSubprotocolServerNotSupported() {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       "protoA,protoB",
				                       (i, o) -> o.sendString(Mono.just("test"))))
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		StepVerifier.create(
				HttpClient.create(
						httpServer.address().getPort())
				          .get("/test",
						          out -> out.addHeader("Authorization", auth)
						                    .sendWebsocket("SUBPROTOCOL,OTHER"))
				          .flatMapMany(in -> in.receive().asString())
		)
		            //the SERVER returned null which means that it couldn't select a protocol
		            .verifyErrorMessage("Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	public void simpleSubprotocolServerSupported() {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       "SUBPROTOCOL",
				                       (i, o) -> o.sendString(
						                       Mono.just("test"))))
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		String res = Objects.requireNonNull(
				HttpClient.create(httpServer.address().getPort())
				          .get("/test",
				                  out -> out.addHeader("Authorization", auth)
				                            .sendWebsocket("SUBPROTOCOL,OTHER"))
				          .flatMapMany(in -> in.receive()
				                               .asString())
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30))).get(0);

		Assert.assertThat(res, is("test"));
	}

	@Test
	public void simpleSubprotocolSelected() {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       "NOT, Common",
				                       (i, o) -> o.sendString(
						                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		String res = Objects.requireNonNull(
				HttpClient.create(httpServer.address().getPort())
				          .get("/test",
				                  out -> out.addHeader("Authorization", auth)
				                            .sendWebsocket("Common,OTHER"))
				          .map(HttpClientResponse::receiveWebsocket)
				          .flatMapMany(in -> in.receive()
				                               .asString()
				                               .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30))).get(0);

		Assert.assertThat(res, is("CLIENT:Common-SERVER:Common"));
	}

	@Test
	public void noSubprotocolSelected() {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		String res = Objects.requireNonNull(
				HttpClient.create(httpServer.address()
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
				          .block(Duration.ofSeconds(30))).get(0);

		Assert.assertThat(res, is("CLIENT:null-SERVER:null"));
	}

	@Test
	public void anySubprotocolSelectsFirstClientProvided() {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket("proto2,*", (i, o) -> o.sendString(
				                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		String res = Objects.requireNonNull(
				HttpClient.create(httpServer.address()
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
				          .block(Duration.ofSeconds(30))).get(0);

		Assert.assertThat(res, is("CLIENT:proto1-SERVER:proto1"));
	}

	@Test
	public void sendToWebsocketSubprotocol() throws InterruptedException {
		AtomicReference<String> serverSelectedProtocol = new AtomicReference<>();
		AtomicReference<String> clientSelectedProtocol = new AtomicReference<>();
		AtomicReference<String> clientSelectedProtocolWhenSimplyUpgrading = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
		                       		"not,proto1", (i, o) -> {
					                       serverSelectedProtocol.set(i.selectedSubprotocol());
					                       latch.countDown();
					                       return i.receive()
					                               .asString()
					                               .doOnNext(System.err::println)
					                               .then();
				                       })
		                       )
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

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

		assertTrue(latch.await(30, TimeUnit.SECONDS));
		Assert.assertThat(serverSelectedProtocol.get(), is("proto1"));
		Assert.assertThat(clientSelectedProtocol.get(), is("proto1"));
		Assert.assertThat(clientSelectedProtocolWhenSimplyUpgrading.get(), is("proto1"));
	}


	@Test
	public void closePool() {
		PoolResources pr = PoolResources.fixed("wstest", 1);
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(NettyPipeline.SendOptions::flushOnEach)
				                                  .sendString(
						                                  Mono.just("test")
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .block(Duration.ofSeconds(30));

		Flux<String> ws = HttpClient.create(opts -> opts.port(httpServer.address()
		                                                                .getPort())
		                                                .poolResources(pr))
		                            .ws("/")
		                            .flatMapMany(in -> in.receiveWebsocket()
		                                                 .aggregateFrames()
		                                                 .receive()
		                                                 .asString());

		StepVerifier.create(Flux.range(1, 10)
		                        .concatMap(i -> ws.take(2)
		                                          .log()))
		            .expectNextSequence(
		                    Objects.requireNonNull(Flux.range(1, 20)
		                                               .map(v -> "test")
		                                               .collectList()
		                                               .block()))
		            .expectComplete()
		            .verify();

		pr.dispose();
	}

	@Test
	public void testCloseWebSocketFrameSentByServer() {
		httpServer =
				HttpServer.create(0)
				          .newHandler((req, res) ->
				                  res.sendWebsocket((in, out) -> out.sendObject(in.receiveFrames()
				                                                                  .doOnNext(WebSocketFrame::retain))))
				          .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

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
				HttpServer.create(0)
				          .newHandler((req, res) ->
				                  res.sendWebsocket((in, out) -> out.sendString(Mono.just("echo"))
				                                                    .sendObject(new CloseWebSocketFrame())))
				          .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

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
		        out.options(NettyPipeline.SendOptions::flushOnEach)
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
		        out.options(NettyPipeline.SendOptions::flushOnEach)
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
				HttpServer.create(0)
				          .newHandler((req, res) -> res.sendWebsocket(handler))
				          .block(Duration.ofSeconds(30));

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
				HttpServer.create(0)
				          .newHandler((req, res) ->
				              res.options(NettyPipeline.SendOptions::flushOnEach)
				                 .sendWebsocket((in, out) ->
				                     out.sendString(Flux.interval(Duration.ofSeconds(1))
				                                        .map(l -> l + ""))))
				          .block(Duration.ofSeconds(30));

		CountDownLatch latch = new CountDownLatch(3);
		AtomicBoolean error = new AtomicBoolean();
		HttpClient.create(httpServer.address().getPort())
		          .ws("/test")
		          .flatMap(res -> res.receiveWebsocket((in, out) -> {
		                  NettyContext context = in.context();
		                  Mono.delay(Duration.ofSeconds(3))
		                      .subscribe(c -> {
		                              System.out.println("context.dispose()");
		                              context.dispose();
		                              latch.countDown();
		                      });
		                  context.onClose()
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
		                  Mono.delay(Duration.ofSeconds(3))
		                      .repeat(() -> {
		                          System.out.println("context.isDisposed() " + context.isDisposed());
		                          if (context.isDisposed()) {
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
				HttpServer.create(0)
				          .newHandler((req, res) ->
				              res.sendWebsocket((in, out) ->
				                  out.sendString(Mono.just("test"))))
				          .block(Duration.ofSeconds(30));

		CountDownLatch latch = new CountDownLatch(2);
		AtomicBoolean error = new AtomicBoolean();
		HttpClient.create(httpServer.address().getPort())
		          .ws("/test")
		          .flatMap(res -> res.receiveWebsocket((in, out) -> {
		              NettyContext context = in.context();
		              context.onClose()
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
		              Mono.delay(Duration.ofSeconds(3))
		                  .repeat(() -> {
		                      System.out.println("context.isDisposed() " + context.isDisposed());
		                      if (context.isDisposed()) {
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
	public void testIssue444() throws InterruptedException {
		doTestIssue444((in, out) ->
				out.sendObject(Flux.error(new Throwable())
				                   .onErrorResume(ex -> out.sendClose(1001, "Going Away"))
				                   .cast(WebSocketFrame.class)));
		doTestIssue444((in, out) ->
				out.options(o -> o.flushOnEach(false))
				   .send(Flux.range(0, 10)
				             .map(i -> {
				                 if (i == 5) {
				                     out.sendClose(1001, "Going Away").subscribe();
				                 }
				                 return Unpooled.copiedBuffer((i + "").getBytes(Charset.defaultCharset()));
				             })));
		doTestIssue444((in, out) ->
				out.sendObject(Flux.error(new Throwable())
				                   .onErrorResume(ex -> Flux.empty())
				                   .cast(WebSocketFrame.class))
				   .then(Mono.defer(() -> out.sendObject(
				           new CloseWebSocketFrame(1001, "Going Away")).then())));
	}

	private void doTestIssue444(BiFunction<WebsocketInbound, WebsocketOutbound, Publisher<Void>> fn) {
		httpServer = HttpServer.create(ops -> ops.host("localhost")
		                                         .port(0))
		                       .newHandler((req, res) -> res.sendWebsocket(null, fn))
		                       .block(Duration.ofSeconds(30));
		assertNotNull(httpServer);

		StepVerifier.create(
		        HttpClient.create(ops -> ops.connectAddress(() -> httpServer.address()))
		                  .ws("/")
		                  .flatMapMany(res -> res.receiveWebsocket()
		                                     .receiveFrames()
		                                     .then()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}
}
