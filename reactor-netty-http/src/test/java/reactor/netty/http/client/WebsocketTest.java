/*
 * Copyright (c) 2011-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import java.nio.charset.Charset;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.BeforeAll;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.BaseHttpTest;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.WebsocketServerSpec;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;

import static io.netty.handler.codec.http.websocketx.WebSocketCloseStatus.ABNORMAL_CLOSURE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test class verifies {@link HttpClient} websocket functionality.
 *
 * @author tjreactive
 * @author smaldini
 */
class WebsocketTest extends BaseHttpTest {

	static final String auth = "bearer abc";

	static final Logger log = Loggers.getLogger(WebsocketTest.class);

	static SelfSignedCertificate ssc;
	static Http11SslContextSpec serverCtx11;
	static Http2SslContextSpec serverCtx2;
	static Http11SslContextSpec clientCtx11;
	static Http2SslContextSpec clientCtx2;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
		serverCtx11 = Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		serverCtx2 = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		clientCtx11 = Http11SslContextSpec.forClient()
		                                  .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		clientCtx2 = Http2SslContextSpec.forClient()
		                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
	}

	void doSimpleTest(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server, (i, o) -> o.sendString(Mono.just("test")));
		sendRequestWithCollect(client, (i, o) -> i.receive().asString(), "test");
	}

	void doServerWebSocketFailed(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((in, out) -> {
				          if (!in.requestHeaders().contains("Authorization")) {
				              return out.status(401);
				          }
				          else {
				              return out.sendWebsocket((i, o) -> o.sendString(Mono.just("test")));
				          }
				      })
				      .bindNow();

		client.websocket()
		      .uri("/test")
		      .handle((in, out) -> in.receive().aggregate().asString())
		      .next()
		      .as(StepVerifier::create)
		      .expectError(WebSocketHandshakeException.class)
		      .verify(Duration.ofSeconds(30));
	}

	void doUnidirectional(HttpServer server, HttpClient client) {
		int c = 10;
		disposableServer = createDisposableServer(server,
				(i, o) -> o.sendString(Mono.just("test").delayElement(Duration.ofMillis(100)).repeat()));

		List<String> expected =
				Flux.range(1, c)
				    .map(v -> "test")
				    .collectList()
				    .block();
		assertThat(expected).isNotNull();

		client.websocket()
		      .uri("/")
		      .handle((in, out) -> in.aggregateFrames()
		                             .receive()
		                             .asString())
		      .take(c)
		      .log()
		      .as(StepVerifier::create)
		      .expectNextSequence(expected)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	void doWebSocketRespondsToRequestsFromClients(HttpServer server, HttpClient client) {
		AtomicInteger clientRes = new AtomicInteger();
		AtomicInteger serverRes = new AtomicInteger();

		disposableServer =
				server.handle((req, res) -> {
				          log.debug(req.requestHeaders().get("test"));
				          return res.header("content-type", "text/plain")
				                    .sendWebsocket((in, out) ->
				                            out.sendString(in.receive()
				                                             .asString()
				                                             .publishOn(Schedulers.single())
				                                             .doOnNext(s -> serverRes.incrementAndGet())
				                                             .map(it -> it + '!')
				                                             .log("server-reply")));
				      })
				      .bindNow(Duration.ofSeconds(5));

		log.debug("STARTING: server[" + serverRes.get() + "] / client[" + clientRes.get() + "]");

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
		      .doOnError(i -> System.err.println("Failed requesting server: " + i))
		      .as(StepVerifier::create)
		      .expectNextMatches(list -> "1000!".equals(list.get(999)))
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		log.debug("FINISHED: server[" + serverRes.get() + "] / client[" + clientRes.get() + "]");
	}

	void doUnidirectionalBinary(HttpServer server, HttpClient client) {
		int c = 10;
		disposableServer = createDisposableServer(server,
		        (i, o) -> o.sendByteArray(Mono.just("test".getBytes(Charset.defaultCharset()))
		                                      .delayElement(Duration.ofMillis(100))
		                                      .repeat()));

		List<String> expected =
				Flux.range(1, c)
				    .map(v -> "test")
				    .collectList()
				    .block();
		assertThat(expected).isNotNull();

		client.websocket()
		      .uri("/test")
		      .handle((i, o) -> i.aggregateFrames()
		                         .receive()
		                         .asString())
		      .take(c)
		      .log()
		      .as(StepVerifier::create)
		      .expectNextSequence(expected)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	void doDuplexEcho(HttpServer server, HttpClient client) throws Exception {
		int c = 10;
		CountDownLatch clientLatch = new CountDownLatch(c);
		CountDownLatch serverLatch = new CountDownLatch(c);

		disposableServer = createDisposableServer(server,
		        (i, o) -> o.sendString(i.receive()
		                                .asString()
		                                .take(c)
		                                .doOnNext(s -> serverLatch.countDown())
		                                .log("server")));

		Flux<String> flux = Flux.interval(Duration.ofMillis(200))
		                        .map(Object::toString);

		client.websocket()
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

	void doSimpleSubProtocolServerNoSubProtocol(HttpServer server, HttpClient client, String errorMessage) {
		disposableServer = createDisposableServer(server, (i, o) -> o.sendString(Mono.just("test")));

		client.headers(h -> h.add("Authorization", auth))
		      .websocket(WebsocketClientSpec.builder().protocols("SUBPROTOCOL,OTHER").build())
		      .uri("/test")
		      .handle((i, o) -> i.receive().asString())
		      .as(StepVerifier::create)
		      .verifyErrorMessage(errorMessage);
	}

	void doSimpleSubProtocolServerNotSupported(HttpServer server, HttpClient client, String errorMessage) {
		disposableServer = createDisposableServer(server, (i, o) -> o.sendString(Mono.just("test")),
				WebsocketServerSpec.builder().protocols("protoA,protoB").build());

		client.headers(h -> h.add("Authorization", auth))
		      .websocket(WebsocketClientSpec.builder().protocols("SUBPROTOCOL,OTHER").build())
		      .uri("/test")
		      .handle((i, o) -> i.receive().asString())
		      .as(StepVerifier::create)
		      //the SERVER returned null which means that it couldn't select a protocol
		      .verifyErrorMessage(errorMessage);
	}

	void doSimpleSubProtocolServerSupported(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server, (i, o) -> o.sendString(Mono.just("test")),
				WebsocketServerSpec.builder().protocols("SUBPROTOCOL").build());
		sendRequestWithCollect(client, WebsocketClientSpec.builder().protocols("SUBPROTOCOL,OTHER").build(),
				(i, o) -> i.receive().asString(), "test");
	}

	void doSimpleSubProtocolSelected(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server, (i, o) -> o.sendString(Mono.just("SERVER:" + o.selectedSubprotocol())),
				WebsocketServerSpec.builder().protocols("NOT, Common").build());
		sendRequestWithCollect(client, WebsocketClientSpec.builder().protocols("Common,OTHER").build(),
				(in, out) -> in.receive().asString().map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv),
				"CLIENT:Common-SERVER:Common");
	}

	void doNoSubProtocolSelected(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server, (i, o) -> o.sendString(Mono.just("SERVER:" + o.selectedSubprotocol())));
		sendRequestWithCollect(client,
				(in, out) -> in.receive().asString().map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv),
				"CLIENT:null-SERVER:null");
	}

	void doAnySubProtocolSelectsFirstClientProvided(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server, (i, o) -> o.sendString(Mono.just("SERVER:" + o.selectedSubprotocol())),
				WebsocketServerSpec.builder().protocols("proto2,*").build());
		sendRequestWithCollect(client, WebsocketClientSpec.builder().protocols("proto1, proto2").build(),
				(in, out) -> in.receive().asString().map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv),
				"CLIENT:proto1-SERVER:proto1");
	}

	void doSendToWebsocketSubProtocol(HttpServer server, HttpClient client) throws InterruptedException {
		AtomicReference<String> serverSelectedProtocol = new AtomicReference<>();
		AtomicReference<String> clientSelectedProtocol = new AtomicReference<>();
		AtomicReference<String> clientSelectedProtocolWhenSimplyUpgrading = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);

		disposableServer = createDisposableServer(server,
		        (i, o) -> {
		            serverSelectedProtocol.set(i.selectedSubprotocol());
		            latch.countDown();
		            return i.receive()
		                    .asString()
		                    .doOnNext(System.err::println)
		                    .then();
		        },
		        WebsocketServerSpec.builder().protocols("not,proto1").build());

		client.headers(h -> h.add("Authorization", auth))
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

	void doTestMaxFramePayloadLengthFailed(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server, (i, o) -> o.sendString(Mono.just("12345678901")));

		client.websocket(WebsocketClientSpec.builder().maxFramePayloadLength(10).build())
		      .handle((in, out) -> in.receive()
		                             .asString()
		                             .map(srv -> srv))
		      .log()
		      .then()
		      .as(StepVerifier::create)
		      .expectError(CorruptedFrameException.class)
		      .verify(Duration.ofSeconds(30));
	}

	void doTestMaxFramePayloadLengthSuccess(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server, (i, o) -> o.sendString(Mono.just("12345678901")));

		client.websocket(WebsocketClientSpec.builder().maxFramePayloadLength(11).build())
		      .handle((in, out) -> in.receive()
		                             .asString()
		                             .map(srv -> srv))
		      .log()
		      .then()
		      .as(StepVerifier::create)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	void doTestServerMaxFramePayloadLength(HttpServer server, HttpClient client,
			int maxFramePayloadLength, Flux<String> input, Flux<String> expectation, int count) {
		disposableServer =
				createDisposableServer(server,
				        (in, out) ->
				            out.sendObject(in.aggregateFrames()
				                             .receiveFrames()
				                             .map(WebSocketFrame::content)
				                             .map(byteBuf ->
				                                 byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
				                             .map(TextWebSocketFrame::new)),
				        WebsocketServerSpec.builder().maxFramePayloadLength(maxFramePayloadLength).build());

		AtomicReference<List<String>> output = new AtomicReference<>(new ArrayList<>());
		client.websocket()
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

	void doClosePool(HttpServer server, HttpClient client) {
		ConnectionProvider pr = ConnectionProvider.create("closePool", 1);
		disposableServer = createDisposableServer(server,
				(i, o) -> o.sendString(Mono.just("test").delayElement(Duration.ofMillis(100)).repeat()));

		Flux<String> ws = client.websocket()
		                        .uri("/")
		                        .receive()
		                        .asString();

		List<String> expected =
				Flux.range(1, 20)
				    .map(v -> "test")
				    .collectList()
				    .block();
		assertThat(expected).isNotNull();

		Flux.range(1, 10)
		    .concatMap(i -> ws.take(2)
		                      .log())
		    .as(StepVerifier::create)
		    .expectNextSequence(expected)
		    .expectComplete()
		    .verify(Duration.ofSeconds(30));

		pr.dispose();
	}

	void doTestCloseWebSocketFrameSentByServer(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server,
				(in, out) -> out.sendObject(in.receiveFrames().doOnNext(WebSocketFrame::retain)));

		sendRequest(client, "/",
		        (in, out) -> out.sendString(Mono.just("echo"))
		                        .sendObject(new CloseWebSocketFrame())
		                        .then()
		                        .thenMany(in.receiveFrames()),
		        webSocketFrame -> webSocketFrame instanceof TextWebSocketFrame &&
		                "echo".equals(((TextWebSocketFrame) webSocketFrame).text()));
	}

	void doTestCloseWebSocketFrameSentByClient(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server,
				(in, out) -> out.sendString(Mono.just("echo")).sendObject(new CloseWebSocketFrame()));

		client.websocket()
		      .uri("/")
		      .handle((in, out) -> out.sendObject(in.receiveFrames()
		                                            .doOnNext(WebSocketFrame::retain)
		                                            .then()))
		      .next()
		      .as(StepVerifier::create)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	void doTestConnectionAliveWhenTransformationErrors(HttpServer server, HttpClient client,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> handler,
			Flux<String> expectation, int count) {
		disposableServer = createDisposableServer(server, handler);

		AtomicReference<List<String>> output = new AtomicReference<>(new ArrayList<>());
		client.websocket()
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

	void doTestClientOnCloseIsInvokedClientSendClose(HttpServer server, HttpClient client) throws Exception {
		disposableServer = createDisposableServer(server,
				(in, out) -> out.sendString(Flux.interval(Duration.ofSeconds(1)).map(l -> l + "")));

		CountDownLatch latch = new CountDownLatch(3);
		AtomicBoolean error = new AtomicBoolean();
		client.websocket()
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

	void doTestClientOnCloseIsInvokedClientDisposed(HttpServer server, HttpClient client) throws Exception {
		disposableServer = createDisposableServer(server,
				(in, out) -> out.sendString(Flux.interval(Duration.ofSeconds(1)).map(l -> l + "")));

		CountDownLatch latch = new CountDownLatch(3);
		AtomicBoolean error = new AtomicBoolean();
		client.websocket()
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

	void doTestClientOnCloseIsInvokedServerInitiatedClose(HttpServer server, HttpClient client) throws Exception {
		disposableServer = createDisposableServer(server, (in, out) -> out.sendString(Mono.just("test")));

		CountDownLatch latch = new CountDownLatch(2);
		AtomicBoolean error = new AtomicBoolean();
		client.websocket()
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

	void doTestIssue460(HttpServer server, HttpClient client) {
		disposableServer = createDisposableServer(server.host("::1"), (in, out) -> Mono.never());

		client.headers(h -> h.add(HttpHeaderNames.HOST, "[::1"))
		      .websocket()
		      .connect()
		      .as(StepVerifier::create)
		      .expectError()
		      .verify(Duration.ofSeconds(30));
	}

	void doTestIssue444(HttpServer server, HttpClient client, BiFunction<WebsocketInbound, WebsocketOutbound, Publisher<Void>> fn) {
		disposableServer = createDisposableServer(server.host("localhost"), fn);

		client.websocket()
		      .uri("/")
		      .handle((i, o) -> i.receiveFrames()
		                         .then())
		      .as(StepVerifier::create)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	void doTestIssue821(HttpServer server, HttpClient client) throws Exception {
		Scheduler scheduler = Schedulers.newSingle("ws");
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		disposableServer = server.route(r -> r.ws("/ws", (in, out) -> {
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

		client.websocket()
		      .uri("/ws")
		      .receive()
		      .asString()
		      .as(StepVerifier::create)
		      .expectNext("test")
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		assertThat(error.get()).isNotNull()
		                       .isInstanceOf(AbortedException.class);

		scheduler.dispose();
	}

	void doTestIssue900_1(HttpServer server, HttpClient client) throws Exception {
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		disposableServer = createDisposableServer(server,
				(in, out) -> out.sendObject(in.receiveFrames().doOnNext(WebSocketFrame::retain)));

		CountDownLatch latch = new CountDownLatch(1);
		sendRequest(client, "/",
		        (in, out) -> {
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
		        },
		        webSocketFrame -> webSocketFrame instanceof TextWebSocketFrame &&
		                "echo".equals(((TextWebSocketFrame) webSocketFrame).text()));

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(statusClient.get()).isNotNull()
				.isEqualTo(new WebSocketCloseStatus(1008, "something"));
	}

	void doTestIssue900_2(HttpServer server, HttpClient client) throws Exception {
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<String> incomingData = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer = createDisposableServer(server,
		        (in, out) -> {
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
		        });

		client.websocket()
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

	void doTestIssue663_1(HttpServer server, HttpClient client) throws Exception {
		AtomicBoolean incomingData = new AtomicBoolean();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer = createDisposableServer(server,
		        (i, o) ->
		            o.sendObject(Flux.just(new PingWebSocketFrame(), new CloseWebSocketFrame())
		                             .delayElements(Duration.ofMillis(100)))
		             .then(i.receiveFrames()
		                    .doOnNext(f -> {
		                        if (f instanceof PongWebSocketFrame) {
		                            incomingData.set(true);
		                        }
		                    })
		                    .doOnComplete(latch::countDown)
		                    .then()));

		client.websocket()
		      .uri("/")
		      .handle((in, out) -> in.receiveFrames())
		      .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(incomingData.get()).isTrue();
	}

	void doTestIssue663_2(HttpServer server, HttpClient client) throws Exception {
		AtomicBoolean incomingData = new AtomicBoolean();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer = createDisposableServer(server,
		        (i, o) ->
		            o.sendObject(Flux.just(new PingWebSocketFrame(), new CloseWebSocketFrame())
		             .delayElements(Duration.ofMillis(100)))
		             .then(i.receiveFrames()
		                    .doOnNext(f -> incomingData.set(true))
		                    .doOnComplete(latch::countDown)
		                    .then()));

		client.websocket(WebsocketClientSpec.builder().handlePing(true).build())
		      .uri("/")
		      .handle((in, out) -> in.receiveFrames())
		      .subscribe();

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(incomingData.get()).isFalse();
	}

	void doTestIssue663_3(HttpServer server, HttpClient client) throws Exception {
		AtomicBoolean incomingData = new AtomicBoolean();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer = createDisposableServer(server, (i, o) -> i.receiveFrames().then());

		client.websocket()
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

	void doTestIssue663_4(HttpServer server, HttpClient client) throws Exception {
		AtomicBoolean incomingData = new AtomicBoolean();

		CountDownLatch latch = new CountDownLatch(1);
		disposableServer = createDisposableServer(server,
				(i, o) -> i.receiveFrames().then(),
				WebsocketServerSpec.builder().handlePing(true).build());

		client.websocket()
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

	void doTestIssue967(HttpServer server, HttpClient client) throws Exception {
		Flux<String> somePublisher = Flux.range(1, 10)
		                                 .map(i -> Integer.toString(i))
		                                 .delayElements(Duration.ofMillis(50));

		disposableServer = createDisposableServer(server,
		        (in, out) ->
		            Mono.when(out.sendString(somePublisher),
		                      in.receiveFrames()
		                        .cast(TextWebSocketFrame.class)
		                        .map(TextWebSocketFrame::text)
		                        .publish()     // We want the connection alive even after takeUntil
		                        .autoConnect() // which will trigger cancel
		                        .takeUntil(msg -> msg.equals("5"))
		                        .then()));

		Flux<String> toSend = Flux.range(1, 10)
		                          .map(i -> Integer.toString(i));

		AtomicInteger count = new AtomicInteger();
		CountDownLatch latch = new CountDownLatch(1);
		client.websocket()
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

	void doTestWebsocketCompression(HttpServer server, HttpClient client, boolean compress) {
		doTestWebsocketCompression(server, client, compress, false);
	}

	void doTestWebsocketCompression(HttpServer server, HttpClient client, boolean compress, boolean clientServerNoContextTakeover) {
		WebsocketServerSpec.Builder serverBuilder = WebsocketServerSpec.builder().compress(compress);
		WebsocketServerSpec websocketServerSpec = clientServerNoContextTakeover ?
				serverBuilder.compressionAllowServerNoContext(true).compressionPreferredClientNoContext(true).build() :
				serverBuilder.build();
		disposableServer = createDisposableServer(server, (in, out) -> out.sendString(Mono.just("test")), websocketServerSpec);

		AtomicBoolean clientHandler = new AtomicBoolean();

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
		sendRequest(client, "/", receiver, predicate);
		assertThat(clientHandler.get()).isFalse();

		if (compress) {
			predicate = t -> "test".equals(t.getT1()) && !"null".equals(t.getT2());
			predicate = clientServerNoContextTakeover ?
					predicate.and(t -> t.getT2().contains("client_no_context_takeover") && t.getT2().contains("server_no_context_takeover")) :
					predicate.and(t -> !t.getT2().contains("client_no_context_takeover") && !t.getT2().contains("server_no_context_takeover"));
		}
		WebsocketClientSpec.Builder clientBuilder = WebsocketClientSpec.builder().compress(compress);
		WebsocketClientSpec websocketClientSpec = clientServerNoContextTakeover ?
				clientBuilder.compressionAllowClientNoContext(true).compressionRequestedServerNoContext(true).build() :
				clientBuilder.build();
		sendRequest(client, websocketClientSpec, "/", receiver, predicate);
		assertThat(clientHandler.get()).isEqualTo(compress);
	}

	void doTestIssue1485_CloseFrameSentByClient(HttpServer server, HttpClient client) throws Exception {
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(2);
		disposableServer = createDisposableServer(server,
		        (in, out) -> {
		            in.receiveCloseStatus()
		              .doOnNext(status -> {
		                  statusServer.set(status);
		                  latch.countDown();
		              })
		              .subscribe();
		            return in.receive().then();
		        });

		client.websocket()
		      .uri("/")
		      .handle((in, out) -> {
		          in.receiveCloseStatus()
		            .doOnNext(status -> {
		                statusClient.set(status);
		                latch.countDown();
		            })
		            .subscribe();
		          return out.sendObject(new CloseWebSocketFrame())
		                    .then(in.receive().then());
		      })
		      .blockLast(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(statusClient.get())
				.isNotNull()
				.isEqualTo(WebSocketCloseStatus.EMPTY);

		assertThat(statusServer.get())
				.isNotNull()
				.isEqualTo(WebSocketCloseStatus.EMPTY);
	}

	void doTestIssue1485_CloseFrameSentByServer(HttpServer server, HttpClient client) throws Exception {
		AtomicReference<WebSocketCloseStatus> statusServer = new AtomicReference<>();
		AtomicReference<WebSocketCloseStatus> statusClient = new AtomicReference<>();

		CountDownLatch latch = new CountDownLatch(2);
		disposableServer = createDisposableServer(server,
		        (in, out) -> {
		            in.receiveCloseStatus()
		              .doOnNext(status -> {
		                  statusServer.set(status);
		                  latch.countDown();
		              })
		              .subscribe();
		            return out.sendObject(new CloseWebSocketFrame())
		                      .then(in.receive().then());
		        });

		client.websocket()
		      .uri("/")
		      .handle((in, out) -> {
		          in.receiveCloseStatus()
		            .doOnNext(status -> {
		                statusClient.set(status);
		                latch.countDown();
		            })
		            .subscribe();
		          return in.receive();
		      })
		      .blockLast(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(statusClient.get())
				.isNotNull()
				.isEqualTo(WebSocketCloseStatus.EMPTY);

		assertThat(statusServer.get())
				.isNotNull()
				.isEqualTo(WebSocketCloseStatus.EMPTY);
	}

	void doTestConnectionClosedWhenFailedUpgrade(HttpServer server, HttpClient client,
			@Nullable Consumer<Throwable> errorConsumer) throws Exception {
		disposableServer =
				server.handle((req, res) -> res.sendNotFound())
				      .bindNow();

		CountDownLatch latch = new CountDownLatch(1);

		client.doOnRequest((req, conn) -> conn.channel().closeFuture().addListener(f -> latch.countDown()))
		      .websocket()
		      .uri("/")
		      .connect()
		      .subscribe(null, errorConsumer, null);

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();
	}

	void doTestIssue3295(HttpServer server, HttpClient client) throws Exception {
		AtomicReference<Throwable> serverError = new AtomicReference<>();
		CountDownLatch serverLatch = new CountDownLatch(1);
		disposableServer = createDisposableServer(server,
		        (in, out) ->
		            in.aggregateFrames(10)
		              .receiveFrames()
		              .doOnError(t -> {
		                  serverError.set(t);
		                  serverLatch.countDown();
		              })
		              .cast(BinaryWebSocketFrame.class)
		              .map(DefaultByteBufHolder::content)
		              .then());

		AtomicReference<WebSocketCloseStatus> clientStatus = new AtomicReference<>();
		AtomicReference<Connection> connection = new AtomicReference<>();
		CountDownLatch clientLatch = new CountDownLatch(2);
		byte[] content1 = "Content1".getBytes(CharsetUtil.UTF_8);
		byte[] content2 = "Content2".getBytes(CharsetUtil.UTF_8);
		byte[] content3 = "Content3".getBytes(CharsetUtil.UTF_8);
		client.websocket()
		      .handle((in, out) -> {
		          in.withConnection(conn -> {
		              connection.set(conn);
		              conn.channel().closeFuture().addListener(f -> clientLatch.countDown());
		          });
		          in.receiveCloseStatus().subscribe(s -> {
		              clientStatus.set(s);
		              clientLatch.countDown();
		          });
		          return out.sendObject(Flux.just(
		                  new BinaryWebSocketFrame(false, 0, Unpooled.wrappedBuffer(content1)),
		                  new ContinuationWebSocketFrame(false, 0, Unpooled.wrappedBuffer(content2)),
		                  new ContinuationWebSocketFrame(true, 0, Unpooled.wrappedBuffer(content3))));
		      })
		      .then()
		      .block(Duration.ofSeconds(5));

		assertThat(serverLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(serverError.get()).isNotNull().isInstanceOf(TooLongFrameException.class);

		assertThat(clientLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(clientStatus.get()).isNotNull().isEqualTo(ABNORMAL_CLOSURE);
		assertThat(connection.get()).isNotNull();
		assertThat(connection.get().channel().isActive()).isFalse();
	}

	private static DisposableServer createDisposableServer(HttpServer server,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> handler) {
		return createDisposableServer(server, handler, null);
	}

	private static DisposableServer createDisposableServer(HttpServer server,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> handler,
			@Nullable WebsocketServerSpec spec) {
		return spec == null ?
				server.handle((in, out) -> out.sendWebsocket(handler)).bindNow() :
				server.handle((in, out) -> out.sendWebsocket(handler, spec)).bindNow();
	}

	private static <V> void sendRequest(HttpClient client, String uri,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<V>> receiver,
			Predicate<V> predicate) {
		sendRequest(client, null, uri, receiver, predicate);
	}

	private static <V> void sendRequest(HttpClient client, @Nullable WebsocketClientSpec spec, String uri,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<V>> receiver,
			Predicate<V> predicate) {
		HttpClient.WebsocketSender sender = spec == null ? client.websocket() : client.websocket(spec);
		sender.uri(uri)
		      .handle(receiver)
		      .as(StepVerifier::create)
		      .expectNextMatches(predicate)
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
	}

	private static void sendRequestWithCollect(HttpClient client,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<String>> receiver,
			String expectedBody) {
		sendRequestWithCollect(client, null, receiver, expectedBody);
	}

	private static void sendRequestWithCollect(HttpClient client, @Nullable WebsocketClientSpec spec,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<String>> receiver,
			String expectedBody) {
		HttpClient.WebsocketSender sender = spec == null ?
				client.headers(h -> h.add("Authorization", auth)).websocket() :
				client.headers(h -> h.add("Authorization", auth)).websocket(spec);

		sender.uri("/test")
		      .handle(receiver)
		      .log("client")
		      .collectList()
		      .as(StepVerifier::create)
		      .expectNextMatches(l -> expectedBody.equals(l.get(0)))
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
	}
}
