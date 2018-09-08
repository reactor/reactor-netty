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

package reactor.netty.http.client;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import io.netty.handler.codec.CorruptedFrameException;
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
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.netty.resources.ConnectionProvider;
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
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(Mono.just("test"))))
		                       .wiretap()
		                       .bindNow();

		String res = Objects.requireNonNull(
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .wiretap()
				          .headers(h -> h.add("Authorization", auth))
				          .websocket()
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString())
				          .log("client")
				          .collectList()
				          .block()).get(0);

		Assert.assertThat(res, is("test"));
	}

	@Test
	public void serverWebSocketFailed() {
		httpServer =
				HttpServer.create()
						.port(0)
						.handle((in, out) -> {
							if (!in.requestHeaders().contains("Authorization")) {
								return out.status(401);
							} else {
								return out.sendWebsocket((i, o) -> o.sendString(Mono.just("test")));
							}
						})
						.wiretap()
						.bindNow();

		Mono<String> res =
				HttpClient.create()
				          .port(httpServer.address()
						                .getPort())
						  .websocket()
						  .uri("/test")
						  .handle((in, out) -> in.receive().aggregate().asString())
						  .next();

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
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(NettyPipeline.SendOptions::flushOnEach)
				                                  .sendString(
						                                  Mono.just("test")
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .wiretap()
		                       .bindNow();

		Flux<String> ws = HttpClient.create()
		                            .port(httpServer.address().getPort())
		                            .wiretap()
		                            .websocket()
		                            .uri("/")
		                            .handle((in, out) -> in.aggregateFrames()
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
	public void webSocketRespondsToRequestsFromClients() {
		AtomicInteger clientRes = new AtomicInteger();
		AtomicInteger serverRes = new AtomicInteger();

		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .route(r -> r.get("/test/{param}", (req, res) -> {
					          System.out.println(req.requestHeaders().get("test"));
					          return res.header("content-type", "text/plain")
					                    .sendWebsocket((in, out) ->
							                    out.options(NettyPipeline.SendOptions::flushOnEach)
							                       .sendString(in.receive()
							                                     .asString()
							                                     .publishOn(Schedulers.single())
							                                     .doOnNext(s -> serverRes.incrementAndGet())
							                                     .map(it -> it + ' ' + req.param("param") + '!')
							                                     .log("server-reply")));
				          }))
				          .wiretap()
				          .bindNow(Duration.ofSeconds(5));

		HttpClient client = HttpClient.create()
		                              .port(server.address().getPort())
		                              .wiretap();

		Mono<List<String>> response =
				client.headers(h -> h.add("Content-Type", "text/plain")
				                     .add("test", "test"))
				      .websocket() //TODO investigate why get not working
				      .uri("/test/World")
				      .handle((i, o) -> {
					      o.options(NettyPipeline.SendOptions::flushOnEach);

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

		System.out.println("STARTING: server[" + serverRes.get() + "] / client[" + clientRes.get() + "]");

		StepVerifier.create(response)
		            .expectNextMatches(list -> "1000 World!".equals(list.get(999)))
		            .expectComplete()
		            .verify();

		System.out.println("FINISHED: server[" + serverRes.get() + "] / client[" + clientRes + "]");

		server.dispose();
	}

	@Test
	public void unidirectionalBinary() {
		int c = 10;
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(NettyPipeline.SendOptions::flushOnEach)
				                                  .sendByteArray(
						                                  Mono.just("test".getBytes(Charset.defaultCharset()))
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .wiretap()
		                       .bindNow();

		Flux<String> ws = HttpClient.create()
		                            .port(httpServer.address().getPort())
		                            .websocket()
		                            .uri("/test")
		                            .handle((i, o) -> i.aggregateFrames()
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

		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                       i.receive()
				                        .asString()
				                        .take(c)
				                        .subscribeWith(server))))
		                       .wiretap()
		                       .bindNow();

		Flux.interval(Duration.ofMillis(200))
		    .map(Object::toString)
		    .subscribe(client::onNext);

		HttpClient.create()
		          .port(httpServer.address().getPort())
		          .headers(h -> h.add("Authorization", auth))
		          .websocket()
		          .uri("/test")
		          .handle((i, o) -> o.options(NettyPipeline.SendOptions::flushOnEach)
		                             .sendString(i.receive()
		                                          .asString()
		                                          .subscribeWith(client)))
		          .log()
		          .blockLast();

		Assert.assertTrue(serverLatch.await(10, TimeUnit.SECONDS));
		Assert.assertTrue(clientLatch.await(10, TimeUnit.SECONDS));
	}

	@Test
	public void simpleSubprotocolServerNoSubprotocol() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                                    Mono.just("test"))))
		                       .wiretap()
		                       .bindNow();

		StepVerifier.create(
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket("SUBPROTOCOL,OTHER")
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString())
		)
		            .verifyErrorMessage("Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	public void simpleSubprotocolServerNotSupported() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
				                       "protoA,protoB",
				                       (i, o) -> {
				                       	return o.sendString(Mono.just("test"));
				                       }))
		                       .wiretap()
		                       .bindNow();

		StepVerifier.create(
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket("SUBPROTOCOL,OTHER")
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString())
		)
		            //the SERVER returned null which means that it couldn't select a protocol
		            .verifyErrorMessage("Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	public void simpleSubprotocolServerSupported() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket("SUBPROTOCOL",
				                       (i, o) -> o.sendString(Mono.just("test"))))
		                       .wiretap()
		                       .bindNow();

		String res = Objects.requireNonNull(
				HttpClient.create()
				          .port(httpServer.address()
				                          .getPort())
				          .wiretap()
				          .headers(h -> h.add("Authorization", auth))
				          .websocket("SUBPROTOCOL,OTHER")
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString())
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30))).get(0);

		Assert.assertThat(res, is("test"));
	}

	@Test
	public void simpleSubprotocolSelected() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
				                       "NOT, Common",
				                       (i, o) -> o.sendString(
						                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .wiretap()
		                       .bindNow();

		String res = Objects.requireNonNull(
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .wiretap()
				          .headers(h -> h.add("Authorization", auth))
				          .websocket("Common,OTHER")
				          .uri("/test")
				          .handle((in, out) -> in.receive()
				                                 .asString()
				                                 .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30))).get(0);

		Assert.assertThat(res, is("CLIENT:Common-SERVER:Common"));
	}

	@Test
	public void noSubprotocolSelected() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
		                               Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .wiretap()
		                       .bindNow();

		String res = Objects.requireNonNull(
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket()
				          .uri("/test")
				          .handle((in, out) -> in.receive()
				                                 .asString()
				                                 .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30))).get(0);

		Assert.assertThat(res, is("CLIENT:null-SERVER:null"));
	}

	@Test
	public void anySubprotocolSelectsFirstClientProvided() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket("proto2,*", (i, o) -> o.sendString(
		                               Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .wiretap()
		                       .bindNow();

		String res = Objects.requireNonNull(
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket("proto1, proto2")
				          .uri("/test")
				          .handle((in, out) -> in.receive()
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

		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
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

		HttpClient.create()
		           .port(httpServer.address().getPort())
		           .headers(h -> h.add("Authorization", auth))
		           .websocket("proto1,proto2")
		           .uri("/test")
		           .handle((in, out) -> {
			          clientSelectedProtocolWhenSimplyUpgrading.set(in.selectedSubprotocol());
			          clientSelectedProtocol.set(out.selectedSubprotocol());
			          return out.sendString(Mono.just("HELLO" + out.selectedSubprotocol()));
		          })
		          .blockLast(Duration.ofSeconds(30));

		Assert.assertTrue(latch.await(30, TimeUnit.SECONDS));
		Assert.assertThat(serverSelectedProtocol.get(), is("proto1"));
		Assert.assertThat(clientSelectedProtocol.get(), is("proto1"));
		Assert.assertThat(clientSelectedProtocolWhenSimplyUpgrading.get(), is("proto1"));
	}

    @Test
    public void testMaxFramePayloadLengthFailed() {
        httpServer = HttpServer.create()
                .port(0)
                .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(Mono.just("12345678901"))))
                .wiretap()
                .bindNow();

        Mono<Void> response = HttpClient.create()
                        .port(httpServer.address().getPort())
                        .websocket(10)
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
    public void testMaxFramePayloadLengthSuccess() {
        httpServer = HttpServer.create()
                .port(0)
                .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(Mono.just("12345678901"))))
                .wiretap()
                .bindNow();

        Mono<Void> response = HttpClient.create()
                .port(httpServer.address().getPort())
                .websocket(11)
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
	public void closePool() {
		ConnectionProvider pr = ConnectionProvider.fixed("wstest", 1);
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(NettyPipeline.SendOptions::flushOnEach)
				                                  .sendString(
						                                  Mono.just("test")
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .wiretap()
		                       .bindNow();

		Flux<String> ws = HttpClient.create(pr)
		                            .port(httpServer.address()
		                                            .getPort())
		                            .websocket()
		                            .uri("/")
		                            .receive()
		                            .asString();

		StepVerifier.create(
				Flux.range(1, 10)
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
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				                  res.sendWebsocket((in, out) -> out.sendObject(in.receiveFrames()
				                                                                  .doOnNext(
						                                                                  WebSocketFrame::retain))))
				          .wiretap()
				          .bindNow();

		Flux<WebSocketFrame> response =
				HttpClient.create()
				          .port(httpServer.address().getPort())
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
	public void testCloseWebSocketFrameSentByClient() {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				                  res.sendWebsocket((in, out) -> out.sendString(Mono.just("echo"))
				                                                    .sendObject(new CloseWebSocketFrame())))
				          .wiretap()
				          .bindNow();

		Mono<Void> response =
				HttpClient.create()
				          .port(httpServer.address().getPort())
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
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.sendWebsocket(handler))
				          .wiretap()
				          .bindNow();

		ReplayProcessor<String> output = ReplayProcessor.create();
		HttpClient.create()
		          .port(httpServer.address().getPort())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> out.sendString(Flux.just("1", "text", "2"))
		                                                       .then(in.aggregateFrames()
		                                                               .receiveFrames()
		                                                               .map(WebSocketFrame::content)
		                                                               .map(byteBuf -> byteBuf.toString(Charset.defaultCharset()))
		                                                               .take(count)
		                                                               .subscribeWith(output)
		                                                               .then()))
		          .blockLast(Duration.ofSeconds(30));

		Assertions.assertThat(output.collectList().block(Duration.ofSeconds(30)))
		          .isEqualTo(expectation.collectList().block(Duration.ofSeconds(30)));

	}

	@Test
	public void testClientOnCloseIsInvokedClientSendClose() throws Exception {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.options(NettyPipeline.SendOptions::flushOnEach)
				                 .sendWebsocket((in, out) ->
				                     out.sendString(Flux.interval(Duration.ofSeconds(1))
				                                        .map(l -> l + ""))))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(3);
		AtomicBoolean error = new AtomicBoolean();
		HttpClient.create()
		          .port(httpServer.address().getPort())
		          .websocket()
		          .uri("/test")
		          .handle((in, out)  -> {
			          Mono.delay(Duration.ofSeconds(3))
			              .delayUntil(i -> out.sendClose())
			              .subscribe(c -> {
				              System.out.println("context.dispose()");
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
		          })
		          .blockLast(Duration.ofSeconds(30));

		latch.await(30, TimeUnit.SECONDS);

		Assertions.assertThat(error.get()).isFalse();
	}

	@Test
	public void testClientOnCloseIsInvokedClientDisposed() throws Exception {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.options(NettyPipeline.SendOptions::flushOnEach)
				                 .sendWebsocket((in, out) ->
				                     out.sendString(Flux.interval(Duration.ofSeconds(1))
				                                        .map(l -> l + ""))))
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(3);
		AtomicBoolean error = new AtomicBoolean();
		HttpClient.create()
		          .port(httpServer.address().getPort())
		          .websocket()
		          .uri("/test")
		          .handle((in, out)  -> {
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
		          })
		          .blockLast(Duration.ofSeconds(30));

		latch.await(30, TimeUnit.SECONDS);

		Assertions.assertThat(error.get()).isFalse();
	}

	@Test
	public void testClientOnCloseIsInvokedServerInitiatedClose() throws Exception {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) ->
				                  out.sendString(Mono.just("test"))))
				          .wiretap()
				          .bindNow();

		CountDownLatch latch = new CountDownLatch(2);
		AtomicBoolean error = new AtomicBoolean();
		HttpClient.create()
		          .port(httpServer.address().getPort())
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
		              return in.receive();
		          })
		          .blockLast(Duration.ofSeconds(30));

		latch.await(30, TimeUnit.SECONDS);

		Assertions.assertThat(error.get()).isFalse();
	}
}
