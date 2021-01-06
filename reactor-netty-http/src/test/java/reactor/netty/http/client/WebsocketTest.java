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

package reactor.netty.http.client;

import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.BaseHttpTest;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.server.WebsocketServerSpec;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author tjreactive
 * @author smaldini
 */
class WebsocketTest extends BaseHttpTest {

	static final String auth = "bearer abc";

	static final Logger log = Loggers.getLogger(WebsocketTest.class);

	@Test
	void simpleTest() {
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(Mono.just("test"))))
		                       .bindNow();

		List<String> res =
				createClient(disposableServer.port())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket()
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString())
				          .log("client")
				          .collectList()
				          .block();

		assertThat(res).isNotNull();
		assertThat(res.get(0)).isEqualTo("test");
	}

	@Test
	void serverWebSocketFailed() {
		disposableServer =
				createServer()
				          .handle((in, out) -> {
				              if (!in.requestHeaders().contains("Authorization")) {
				                  return out.status(401);
				              }
				              else {
				                  return out.sendWebsocket((i, o) -> o.sendString(Mono.just("test")));
				              }
				          })
				          .bindNow();

		Mono<String> res =
				createClient(disposableServer.port())
				          .websocket()
				          .uri("/test")
				          .handle((in, out) -> in.receive().aggregate().asString())
				          .next();

		StepVerifier.create(res)
		            .expectError(WebSocketHandshakeException.class)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void unidirectional() {
		int c = 10;
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> o.sendString(
		                                                  Mono.just("test")
		                                                      .delayElement(Duration.ofMillis(100))
		                                                      .repeat())))
		                       .bindNow();

		Flux<String> ws = createClient(disposableServer.port())
		                            .websocket()
		                            .uri("/")
		                            .handle((in, out) -> in.aggregateFrames()
		                                                   .receive()
		                                                   .asString());

		List<String> expected =
				Flux.range(1, c)
				    .map(v -> "test")
				    .collectList()
				    .block();
		assertThat(expected).isNotNull();

		StepVerifier.create(ws.take(c)
		                      .log())
		            .expectNextSequence(expected)
		            .expectComplete()
		            .verify();
	}

	@Test
	void webSocketRespondsToRequestsFromClients() {
		AtomicInteger clientRes = new AtomicInteger();
		AtomicInteger serverRes = new AtomicInteger();

		disposableServer =
				createServer()
				          .route(r -> r.get("/test/{param}", (req, res) -> {
				              log.debug(req.requestHeaders().get("test"));
				              return res.header("content-type", "text/plain")
				                        .sendWebsocket((in, out) ->
				                                out.sendString(in.receive()
				                                                 .asString()
				                                                 .publishOn(Schedulers.single())
				                                                 .doOnNext(s -> serverRes.incrementAndGet())
				                                                 .map(it -> it + ' ' + req.param("param") + '!')
				                                                 .log("server-reply")));
				          }))
				          .bindNow(Duration.ofSeconds(5));

		HttpClient client = createClient(disposableServer.port());

		Mono<List<String>> response =
				client.headers(h -> h.add("Content-Type", "text/plain")
				                     .add("test", "test"))
				      .websocket()
				      .uri("/test/World")
				      .handle((i, o) -> {
				          o.sendString(Flux.range(1, 1000)
				                           .log("client-send")
				                           .map(it -> "" + it), Charset.defaultCharset())
				           .then()
				           .subscribe();

				          return i.receive()
				                  .asString();
				      })
				      .log("client-received")
				      .publishOn(Schedulers.parallel())
				      .doOnNext(s -> clientRes.incrementAndGet())
				      .take(1000)
				      .collectList()
				      .cache()
				      .doOnError(i -> System.err.println("Failed requesting server: " + i));

		log.debug("STARTING: server[" + serverRes.get() + "] / client[" + clientRes.get() + "]");

		StepVerifier.create(response)
		            .expectNextMatches(list -> "1000 World!".equals(list.get(999)))
		            .expectComplete()
		            .verify();

		log.debug("FINISHED: server[" + serverRes.get() + "] / client[" + clientRes + "]");
	}

	@Test
	void unidirectionalBinary() {
		int c = 10;
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> o.sendByteArray(
		                                                  Mono.just("test".getBytes(Charset.defaultCharset()))
		                                                      .delayElement(Duration.ofMillis(100))
		                                                      .repeat())))
		                       .bindNow();

		Flux<String> ws = createClient(disposableServer.port())
		                            .websocket()
		                            .uri("/test")
		                            .handle((i, o) -> i.aggregateFrames()
		                                               .receive()
		                                               .asString());

		List<String> expected =
				Flux.range(1, c)
				    .map(v -> "test")
				    .collectList()
				    .block();
		assertThat(expected).isNotNull();

		StepVerifier.create(ws.take(c)
		                      .log())
		            .expectNextSequence(expected)
		            .expectComplete()
		            .verify();
	}

	@Test
	void duplexEcho() throws Exception {

		int c = 10;
		CountDownLatch clientLatch = new CountDownLatch(c);
		CountDownLatch serverLatch = new CountDownLatch(c);

		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
		                               i.receive()
		                                .asString()
		                                .take(c)
		                                .doOnNext(s -> serverLatch.countDown())
		                                .log("server"))))
		                       .bindNow();

		Flux<String> flux = Flux.interval(Duration.ofMillis(200))
		                        .map(Object::toString);

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/test")
		          .handle((i, o) -> o.sendString(Flux.merge(flux, i.receive()
		                                                            .asString()
		                                                            .doOnNext(s -> clientLatch.countDown())
		                                                            .log("client"))))
		          .log()
		          .subscribe();

		assertThat(serverLatch.await(10, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(clientLatch.await(10, TimeUnit.SECONDS)).as("latch await").isTrue();
	}

	@Test
	void simpleSubprotocolServerNoSubprotocol() {
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(Mono.just("test"))))
		                       .bindNow();

		StepVerifier.create(
				createClient(disposableServer.port())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket(WebsocketClientSpec.builder().protocols("SUBPROTOCOL,OTHER").build())
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString()))
		            .verifyErrorMessage("Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	void simpleSubprotocolServerNotSupported() {
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> {
		                                   return o.sendString(Mono.just("test"));
		                               },
		                               WebsocketServerSpec.builder().protocols("protoA,protoB").build()))
		                       .bindNow();

		StepVerifier.create(
				createClient(disposableServer.port())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket(WebsocketClientSpec.builder().protocols("SUBPROTOCOL,OTHER").build())
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString()))
		            //the SERVER returned null which means that it couldn't select a protocol
		            .verifyErrorMessage("Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	void simpleSubprotocolServerSupported() {
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> o.sendString(Mono.just("test")),
		                               WebsocketServerSpec.builder().protocols("SUBPROTOCOL").build()))
		                       .bindNow();

		List<String> res =
				createClient(disposableServer.port())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket(WebsocketClientSpec.builder().protocols("SUBPROTOCOL,OTHER").build())
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString())
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30));

		assertThat(res).isNotNull();
		assertThat(res.get(0)).isEqualTo("test");
	}

	@Test
	void simpleSubprotocolSelected() {
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> o.sendString(
		                                       Mono.just("SERVER:" + o.selectedSubprotocol())),
		                               WebsocketServerSpec.builder().protocols("NOT, Common").build()))
		                       .bindNow();

		List<String> res =
				createClient(disposableServer.port())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket(WebsocketClientSpec.builder().protocols("Common,OTHER").build())
				          .uri("/test")
				          .handle((in, out) -> in.receive()
				                                 .asString()
				                                 .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30));

		assertThat(res).isNotNull();
		assertThat(res.get(0)).isEqualTo("CLIENT:Common-SERVER:Common");
	}

	@Test
	void noSubprotocolSelected() {
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
		                               Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .bindNow();

		List<String> res =
				createClient(disposableServer.port())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket()
				          .uri("/test")
				          .handle((in, out) -> in.receive()
				                                 .asString()
				                                 .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30));

		assertThat(res).isNotNull();
		assertThat(res.get(0)).isEqualTo("CLIENT:null-SERVER:null");
	}

	@Test
	void anySubprotocolSelectsFirstClientProvided() {
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
		                               Mono.just("SERVER:" + o.selectedSubprotocol())),
		                               WebsocketServerSpec.builder().protocols("proto2,*").build()))
		                       .bindNow();

		List<String> res =
				createClient(disposableServer.port())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket(WebsocketClientSpec.builder().protocols("proto1, proto2").build())
				          .uri("/test")
				          .handle((in, out) -> in.receive()
				                                 .asString()
				                                 .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30));

		assertThat(res).isNotNull();
		assertThat(res.get(0)).isEqualTo("CLIENT:proto1-SERVER:proto1");
	}

	@Test
	void sendToWebsocketSubprotocol() throws InterruptedException {
		AtomicReference<String> serverSelectedProtocol = new AtomicReference<>();
		AtomicReference<String> clientSelectedProtocol = new AtomicReference<>();
		AtomicReference<String> clientSelectedProtocolWhenSimplyUpgrading = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> {
		                                   serverSelectedProtocol.set(i.selectedSubprotocol());
		                                   latch.countDown();
		                                   return i.receive()
		                                           .asString()
		                                           .doOnNext(System.err::println)
		                                           .then();
		                               },
		                               WebsocketServerSpec.builder().protocols("not,proto1").build()))
		                       .bindNow();

		createClient(disposableServer.port())
		           .headers(h -> h.add("Authorization", auth))
		           .websocket(WebsocketClientSpec.builder().protocols("proto1,proto2").build())
		           .uri("/test")
		           .handle((in, out) -> {
		              clientSelectedProtocolWhenSimplyUpgrading.set(in.selectedSubprotocol());
		              clientSelectedProtocol.set(out.selectedSubprotocol());
		              return out.sendString(Mono.just("HELLO" + out.selectedSubprotocol()));
		          })
		          .blockLast(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(serverSelectedProtocol.get()).isEqualTo("proto1");
		assertThat(clientSelectedProtocol.get()).isEqualTo("proto1");
		assertThat(clientSelectedProtocolWhenSimplyUpgrading.get()).isEqualTo("proto1");
	}

	@Test
	void testMaxFramePayloadLengthFailed() {
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(Mono.just("12345678901"))))
		                       .bindNow();

		Mono<Void> response = createClient(disposableServer.port())
		                                .websocket(WebsocketClientSpec.builder().maxFramePayloadLength(10).build())
		                                .handle((in, out) -> in.receive()
		                                                       .asString()
		                                                       .map(srv -> srv))
		                                .log()
		                                .then();

		StepVerifier.create(response)
		            .expectError(CorruptedFrameException.class)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testMaxFramePayloadLengthSuccess() {
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(Mono.just("12345678901"))))
		                       .bindNow();

		Mono<Void> response = createClient(disposableServer.port())
		                                .websocket(WebsocketClientSpec.builder().maxFramePayloadLength(11).build())
		                                .handle((in, out) -> in.receive()
		                                                       .asString()
		                                                       .map(srv -> srv))
		                                .log()
		                                .then();

		StepVerifier.create(response)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testServerMaxFramePayloadLengthFailed() {
		doTestServerMaxFramePayloadLength(10,
				Flux.just("1", "2", "12345678901", "3"), Flux.just("1", "2"), 2);
	}

	@Test
	void testServerMaxFramePayloadLengthSuccess() {
		doTestServerMaxFramePayloadLength(11,
				Flux.just("1", "2", "12345678901", "3"), Flux.just("1", "2", "12345678901", "3"), 4);
	}

	private void doTestServerMaxFramePayloadLength(int maxFramePayloadLength, Flux<String> input, Flux<String> expectation, int count) {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendWebsocket((in, out) ->
				              out.sendObject(in.aggregateFrames()
				                               .receiveFrames()
				                               .map(WebSocketFrame::content)
				                               .map(byteBuf ->
				                                   byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
				                               .map(TextWebSocketFrame::new)),
				              WebsocketServerSpec.builder().maxFramePayloadLength(maxFramePayloadLength).build()))
				          .bindNow();

		AtomicReference<List<String>> output = new AtomicReference<>(new ArrayList<>());
		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> out.sendString(input)
		                                  .then(in.aggregateFrames()
		                                          .receiveFrames()
		                                          .map(WebSocketFrame::content)
		                                          .map(byteBuf ->
		                                              byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
		                                          .take(count)
		                                          .doOnNext(s -> output.get().add(s))
		                                          .then()))
		          .blockLast(Duration.ofSeconds(30));

		List<String> test = expectation.collectList().block(Duration.ofSeconds(30));
		assertThat(output.get()).isEqualTo(test);
	}


	@Test
	void closePool() {
		ConnectionProvider pr = ConnectionProvider.create("closePool", 1);
		disposableServer = createServer()
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> o.sendString(
		                                                  Mono.just("test")
		                                                      .delayElement(Duration.ofMillis(100))
		                                                      .repeat())))
		                       .bindNow();

		Flux<String> ws = createClient(disposableServer.port())
		                            .websocket()
		                            .uri("/")
		                            .receive()
		                            .asString();

		List<String> expected =
				Flux.range(1, 20)
				    .map(v -> "test")
				    .collectList()
				    .block();
		assertThat(expected).isNotNull();

		StepVerifier.create(
				Flux.range(1, 10)
				    .concatMap(i -> ws.take(2)
				                      .log()))
		            .expectNextSequence(expected)
		            .expectComplete()
		            .verify();

		pr.dispose();
	}

	@Test
	void testCloseWebSocketFrameSentByServer() {
		disposableServer =
				createServer()
				          .handle((req, res) ->
				                  res.sendWebsocket((in, out) -> out.sendObject(in.receiveFrames()
				                                                                  .doOnNext(WebSocketFrame::retain))))
				          .bindNow();

		Flux<WebSocketFrame> response =
				createClient(disposableServer.port())
				          .websocket()
				          .uri("/")
				          .handle((in, out) -> out.sendString(Mono.just("echo"))
				                                  .sendObject(new CloseWebSocketFrame())
				                                  .then()
				                                  .thenMany(in.receiveFrames()));

		StepVerifier.create(response)
		            .expectNextMatches(webSocketFrame ->
		                    webSocketFrame instanceof TextWebSocketFrame &&
		                    "echo".equals(((TextWebSocketFrame) webSocketFrame).text()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testCloseWebSocketFrameSentByClient() {
		disposableServer =
				createServer()
				          .handle((req, res) ->
				                  res.sendWebsocket((in, out) -> out.sendString(Mono.just("echo"))
				                                                    .sendObject(new CloseWebSocketFrame())))
				          .bindNow();

		Mono<Void> response =
				createClient(disposableServer.port())
				          .websocket()
				          .uri("/")
				          .handle((in, out) -> out.sendObject(in.receiveFrames()
				                                                .doOnNext(WebSocketFrame::retain)
				                                                .then()))
				          .next();

		StepVerifier.create(response)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testConnectionAliveWhenTransformationErrors_1() {
		doTestConnectionAliveWhenTransformationErrors((in, out) ->
		        out.sendObject(in.aggregateFrames()
		                         .receiveFrames()
		                         .map(WebSocketFrame::content)
		                         //.share()
		                         .publish()
		                         .autoConnect()
		                         .map(byteBuf ->
		                             byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
		                         .map(Integer::parseInt)
		                         .map(i -> new TextWebSocketFrame(i + ""))
		                         .retry()),
		       Flux.just("1", "2"), 2);
	}

	@Test
	void testConnectionAliveWhenTransformationErrors_2() {
		doTestConnectionAliveWhenTransformationErrors((in, out) ->
		        out.sendObject(in.aggregateFrames()
		                         .receiveFrames()
		                         .map(WebSocketFrame::content)
		                         .concatMap(content ->
		                             Mono.just(content)
		                                 .map(byteBuf ->
		                                     byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
		                                 .map(Integer::parseInt)
		                                 .map(i -> new TextWebSocketFrame(i + ""))
		                                 .onErrorResume(t -> Mono.just(new TextWebSocketFrame("error"))))),
		        Flux.just("1", "error", "2"), 3);
	}

	private void doTestConnectionAliveWhenTransformationErrors(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> handler,
			Flux<String> expectation, int count) {
		disposableServer =
				createServer()
				          .handle((req, res) -> res.sendWebsocket(handler))
				          .bindNow();

		AtomicReference<List<String>> output = new AtomicReference<>(new ArrayList<>());
		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> out.sendString(Flux.just("1", "text", "2"))
		                                  .then(in.aggregateFrames()
		                                          .receiveFrames()
		                                          .map(WebSocketFrame::content)
		                                          .map(byteBuf ->
		                                              byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
		                                          .take(count)
		                                          .doOnNext(s -> output.get().add(s))
		                                          .then()))
		          .blockLast(Duration.ofSeconds(30));

		List<String> test = expectation.collectList().block(Duration.ofSeconds(30));
		assertThat(output.get()).isEqualTo(test);

	}

	@Test
	void testClientOnCloseIsInvokedClientSendClose() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) ->
				                     out.sendString(Flux.interval(Duration.ofSeconds(1))
				                                        .map(l -> l + ""))))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(3);
		AtomicBoolean error = new AtomicBoolean();
		createClient(disposableServer.port())
		          .websocket()
		          .uri("/test")
		          .handle((in, out)  -> {
		              Mono.delay(Duration.ofSeconds(3))
		                  .delayUntil(i -> out.sendClose())
		                  .subscribe(c -> {
		                      log.debug("context.dispose()");
		                      latch.countDown();
		                  });
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
		                                  log.debug("context.onClose() completed");
		                                  latch.countDown();
		                              }));
		                  Mono.delay(Duration.ofSeconds(3))
		                      .repeat(() -> {
		                          AtomicBoolean disposed = new AtomicBoolean(false);
		                          in.withConnection(conn -> {
		                              disposed.set(conn.isDisposed());
		                              log.debug("context.isDisposed() " + conn.isDisposed());
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
		          })
		          .blockLast(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(error.get()).isFalse();
	}

	@Test
	void testClientOnCloseIsInvokedClientDisposed() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) ->
				                     out.sendString(Flux.interval(Duration.ofSeconds(1))
				                                        .map(l -> l + ""))))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(3);
		AtomicBoolean error = new AtomicBoolean();
		createClient(disposableServer.port())
		          .websocket()
		          .uri("/test")
		          .handle((in, out)  -> {
		              in.withConnection(conn -> {
		                  Mono.delay(Duration.ofSeconds(3))
		                      .subscribe(c -> {
		                              log.debug("context.dispose()");
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
		                                     log.debug("context.onClose() completed");
		                                     latch.countDown();
		                                 });
		              });
		                  Mono.delay(Duration.ofSeconds(3))
		                      .repeat(() -> {
		                          AtomicBoolean disposed = new AtomicBoolean(false);
		                          in.withConnection(conn -> {
		                              disposed.set(conn.isDisposed());
		                              log.debug("context.isDisposed() " + conn.isDisposed());
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
		          })
		          .blockLast(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(error.get()).isFalse();
	}

	@Test
	void testClientOnCloseIsInvokedServerInitiatedClose() throws Exception {
		disposableServer =
				createServer()
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) ->
				                  out.sendString(Mono.just("test"))))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(2);
		AtomicBoolean error = new AtomicBoolean();
		createClient(disposableServer.port())
		          .websocket()
		          .uri("/test")
		          .handle((in, out) -> {
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
		                                 log.debug("context.onClose() completed");
		                                 latch.countDown();
		                             }));
		              Mono.delay(Duration.ofSeconds(3))
		                  .repeat(() -> {
		                      AtomicBoolean disposed = new AtomicBoolean(false);
		                      in.withConnection(conn -> {
		                          disposed.set(conn.isDisposed());
		                          log.debug("context.isDisposed() " + conn.isDisposed());
		                      });
		                      if (disposed.get()) {
		                          latch.countDown();
		                          return false;
		                      }
		                      return true;
		                  })
		                  .subscribe();
		              return in.receive();
		          })
		          .blockLast(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		assertThat(error.get()).isFalse();
	}

	@Test
	void testIssue460() {
		disposableServer =
				createServer()
				          .host("::1")
				          .handle((req, res) -> res.sendWebsocket((in, out) -> Mono.never()))
				          .bindNow();

		HttpClient httpClient =
				createClient(disposableServer::address)
				          .headers(h -> h.add(HttpHeaderNames.HOST, "[::1"));

		StepVerifier.create(httpClient.websocket()
		                              .connect())
		            .expectError()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue444_1() {
		doTestIssue444((in, out) ->
				out.sendObject(Flux.error(new Throwable())
				                   .onErrorResume(ex -> out.sendClose(1001, "Going Away"))
				                   .cast(WebSocketFrame.class)));
	}

	@Test
	void testIssue444_2() {
		doTestIssue444((in, out) ->
				out.send(Flux.range(0, 10)
				             .map(i -> {
				                 if (i == 5) {
				                     out.sendClose(1001, "Going Away").subscribe();
				                 }
				                 return Unpooled.copiedBuffer((i + "").getBytes(Charset.defaultCharset()));
				             })));
	}

	@Test
	void testIssue444_3() {
		doTestIssue444((in, out) ->
				out.sendObject(Flux.error(new Throwable())
				                   .onErrorResume(ex -> Flux.empty())
				                   .cast(WebSocketFrame.class))
				   .then(Mono.defer(() -> out.sendObject(
				       new CloseWebSocketFrame(1001, "Going Away")).then())));
	}

	private void doTestIssue444(BiFunction<WebsocketInbound, WebsocketOutbound, Publisher<Void>> fn) {
		disposableServer =
				createServer()
				          .host("localhost")
				          .handle((req, res) -> res.sendWebsocket(fn))
				          .bindNow();

		StepVerifier.create(
				createClient(disposableServer::address)
				          .websocket()
				          .uri("/")
				          .handle((i, o) -> i.receiveFrames()
				                             .then()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	// https://bugzilla.mozilla.org/show_bug.cgi?id=691300
	@Test
	void firefoxConnectionTest() {
		disposableServer = createServer()
		                       .route(r -> r.ws("/ws", (in, out) -> out.sendString(Mono.just("test"))))
		                       .bindNow();

		HttpClientResponse res =
				createClient(disposableServer.port())
				          .headers(h -> {
				                  h.add(HttpHeaderNames.CONNECTION, "keep-alive, Upgrade");
				                  h.add(HttpHeaderNames.UPGRADE, "websocket");
				                  h.add(HttpHeaderNames.ORIGIN, "http://localhost");
				          })
				          .get()
				          .uri("/ws")
				          .response()
				          .block();
		assertThat(res).isNotNull();
		assertThat(res.status()).isEqualTo(HttpResponseStatus.SWITCHING_PROTOCOLS);
	}

	@Test
	void testIssue821() throws Exception {
		Scheduler scheduler = Schedulers.newSingle("ws");
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		disposableServer = createServer()
		                       .route(r -> r.ws("/ws", (in, out) -> {
		                           scheduler.schedule(() ->
		                               out.sendString(Mono.just("scheduled"))
		                                  .then()
		                                  .subscribe(
		                                          null,
		                                          t -> {
		                                              error.set(t);
		                                              latch.countDown();
		                                          },
		                                          null),
		                           500, TimeUnit.MILLISECONDS);
		                           return out.sendString(Mono.just("test"));
		                       }))
		                       .bindNow();

		String res =
				createClient(disposableServer.port())
				          .websocket()
				          .uri("/ws")
				          .receive()
				          .asString()
				          .blockLast();

		assertThat(res).isNotNull()
		               .isEqualTo("test");

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		assertThat(error.get()).isNotNull()
		                       .isInstanceOf(AbortedException.class);

		scheduler.dispose();
	}

	@Test
	void testIssue900_1() throws Exception {
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		disposableServer =
				createServer()
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) -> out.sendObject(in.receiveFrames()
				                                                              .doOnNext(WebSocketFrame::retain))))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(1);
		Flux<WebSocketFrame> response =
				createClient(disposableServer.port())
				          .websocket()
				          .uri("/")
				          .handle((in, out) -> {
				              in.receiveCloseStatus()
				                .doOnNext(o -> {
				                    statusClient.set(o);
				                    latch.countDown();
				                })
				                .subscribe();

				              return out.sendObject(Flux.just(new TextWebSocketFrame("echo"),
				                                              new CloseWebSocketFrame(1008, "something")))
				                        .then()
				                        .thenMany(in.receiveFrames());
				          });

		StepVerifier.create(response)
		            .expectNextMatches(webSocketFrame ->
		                webSocketFrame instanceof TextWebSocketFrame &&
		                    "echo".equals(((TextWebSocketFrame) webSocketFrame).text()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(statusClient.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(1008, "something"));
	}

	@Test
	void testIssue900_2() throws Exception {
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<String> incomingData = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				createServer()
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) -> {
				                  in.receiveCloseStatus()
				                    .doOnNext(o -> {
				                        statusServer.set(o);
				                        latch.countDown();
				                    })
				                    .subscribe();

				                  return out.sendObject(Flux.just(new TextWebSocketFrame("echo"),
				                                                  new CloseWebSocketFrame(1008, "something"))
				                                            .delayElements(Duration.ofMillis(100)))
				                            .then(in.receiveFrames()
				                                    .doOnNext(o -> {
				                                        if (o instanceof TextWebSocketFrame) {
				                                            incomingData.set(((TextWebSocketFrame) o).text());
				                                        }
				                                    })
				                                    .then());
				              })
				          )
				          .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> out.sendObject(in.receiveFrames()
		                                                .doOnNext(WebSocketFrame::retain)))
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(incomingData.get()).isNotNull()
				.isEqualTo("echo");
		assertThat(statusServer.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(1008, "something"));
	}

	@Test
	void testIssue663_1() throws Exception {
		AtomicBoolean incomingData = new AtomicBoolean();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				createServer()
				          .handle((req, resp) ->
				              resp.sendWebsocket((i, o) ->
				                  o.sendObject(Flux.just(new PingWebSocketFrame(), new CloseWebSocketFrame())
				                                   .delayElements(Duration.ofMillis(100)))
				                   .then(i.receiveFrames()
				                          .doOnNext(f -> {
				                              if (f instanceof PongWebSocketFrame) {
				                                  incomingData.set(true);
				                              }
				                          })
				                          .doOnComplete(latch::countDown)
				                          .then())))
				          .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> in.receiveFrames())
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(incomingData.get()).isTrue();
	}

	@Test
	void testIssue663_2() throws Exception {
		AtomicBoolean incomingData = new AtomicBoolean();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				createServer()
				          .handle((req, resp) ->
				              resp.sendWebsocket((i, o) ->
				                  o.sendObject(Flux.just(new PingWebSocketFrame(), new CloseWebSocketFrame())
				                   .delayElements(Duration.ofMillis(100)))
				                   .then(i.receiveFrames()
				                          .doOnNext(f -> incomingData.set(true))
				                          .doOnComplete(latch::countDown)
				                          .then())))
				          .bindNow();

		createClient(disposableServer.port())
		          .websocket(WebsocketClientSpec.builder().handlePing(true).build())
		          .uri("/")
		          .handle((in, out) -> in.receiveFrames())
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(incomingData.get()).isFalse();
	}

	@Test
	void testIssue663_3() throws Exception {
		AtomicBoolean incomingData = new AtomicBoolean();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				createServer()
				          .handle((req, resp) -> resp.sendWebsocket((i, o) -> i.receiveFrames().then()))
				          .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) ->
		              out.sendObject(Flux.just(new PingWebSocketFrame(), new CloseWebSocketFrame())
		                                 .delayElements(Duration.ofMillis(100)))
		                 .then(in.receiveFrames()
		                         .doOnNext(f -> {
		                             if (f instanceof PongWebSocketFrame) {
		                                 incomingData.set(true);
		                             }
		                         })
		                         .doOnComplete(latch::countDown)
		                         .then()))
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(incomingData.get()).isTrue();
	}

	@Test
	void testIssue663_4() throws Exception {
		AtomicBoolean incomingData = new AtomicBoolean();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				createServer()
				          .handle((req, resp) -> resp.sendWebsocket((i, o) -> i.receiveFrames().then(),
				                  WebsocketServerSpec.builder().handlePing(true).build()))
				          .bindNow();

		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) ->
		              out.sendObject(Flux.just(new PingWebSocketFrame(), new CloseWebSocketFrame())
		                                 .delayElements(Duration.ofMillis(100)))
		                 .then(in.receiveFrames()
		                         .doOnNext(f -> incomingData.set(true))
		                         .doOnComplete(latch::countDown)
		                         .then()))
		          .subscribe();


		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(incomingData.get()).isFalse();
	}


	@Test
	void testIssue967() throws Exception {
		Flux<String> somePublisher = Flux.range(1, 10)
		                                 .map(i -> Integer.toString(i))
		                                 .delayElements(Duration.ofMillis(50));

		disposableServer =
				createServer()
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) ->
				                  Mono.when(out.sendString(somePublisher),
				                            in.receiveFrames()
				                              .cast(TextWebSocketFrame.class)
				                              .map(TextWebSocketFrame::text)
				                              .publish()     // We want the connection alive even after takeUntil
				                              .autoConnect() // which will trigger cancel
				                              .takeUntil(msg -> msg.equals("5"))
				                              .then())))
				          .bindNow();

		Flux<String> toSend = Flux.range(1, 10)
		                          .map(i -> Integer.toString(i));

		AtomicInteger count = new AtomicInteger();
		CountDownLatch latch = new CountDownLatch(1);
		createClient(disposableServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) ->
		              Mono.when(out.sendString(toSend),
		                        in.receiveFrames()
		                          .cast(TextWebSocketFrame.class)
		                          .map(TextWebSocketFrame::text)
		                          .doOnNext(s -> count.getAndIncrement())
		                          .doOnComplete(latch::countDown)
		                          .then()))
		          .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(count.get()).isEqualTo(10);
	}

	@Test
	void testIssue970_WithCompress() {
		doTestIssue970(true);
	}

	@Test
	void testIssue970_NoCompress() {
		doTestIssue970(false);
	}

	private void doTestIssue970(boolean compress) {
		disposableServer =
				createServer()
				          .handle((req, res) ->
				              res.sendWebsocket(
				                  (in, out) -> out.sendString(Mono.just("test")),
				                  WebsocketServerSpec.builder().compress(compress).build()))
				          .bindNow();

		AtomicBoolean clientHandler = new AtomicBoolean();
		HttpClient client = createClient(disposableServer::address);

		String perMessageDeflateEncoder = "io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateEncoder";
		BiFunction<WebsocketInbound, WebsocketOutbound, Mono<Tuple2<String, String>>> receiver =
				(in, out) -> {
				    in.withConnection(conn ->
				        clientHandler.set(conn.channel()
				                              .pipeline()
				                              .get(perMessageDeflateEncoder) != null)
				    );

				    String header = in.headers()
				                      .get(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
				    return in.receive()
				             .aggregate()
				             .asString()
				             .zipWith(Mono.just(header == null ? "null" : header));
				};

		Predicate<Tuple2<String, String>> predicate = t -> "test".equals(t.getT1()) && "null".equals(t.getT2());
		StepVerifier.create(client.websocket()
		                          .uri("/")
		                          .handle(receiver))
		            .expectNextMatches(predicate)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
		assertThat(clientHandler.get()).isFalse();

		if (compress) {
			predicate = t -> "test".equals(t.getT1()) && !"null".equals(t.getT2());
		}
		StepVerifier.create(client.websocket(WebsocketClientSpec.builder().compress(compress).build())
		                          .uri("/")
		                          .handle(receiver))
		            .expectNextMatches(predicate)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
		assertThat(clientHandler.get()).isEqualTo(compress);
	}

	@Test
	void websocketOperationsBadValues() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations parent = new HttpClientOperations(Connection.from(channel),
				ConnectionObserver.emptyListener(), ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT);
		WebsocketClientOperations ops = new WebsocketClientOperations(new URI(""),
				WebsocketClientSpec.builder().build(), parent);

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> ops.aggregateFrames(-1))
				.withMessage("maxContentLength: -1 (expected: >= 0)");

		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> ops.send(null));

		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> ops.sendString(null, Charset.defaultCharset()));
	}
}
