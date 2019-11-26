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

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.ReplayProcessor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.is;

/**
 * @author tjreactive
 * @author smaldini
 */
public class WebsocketTest {

	static final String auth = "bearer abc";

	static final Logger log = Loggers.getLogger(WebsocketTest.class);

	DisposableServer httpServer = null;

	@After
	public void disposeHttpServer() {
		if (httpServer != null)
			httpServer.disposeNow();
	}

	@Test
	public void simpleTest() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(Mono.just("test"))))
		                       .wiretap(true)
		                       .bindNow();

		List<String> res =
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .wiretap(true)
				          .headers(h -> h.add("Authorization", auth))
				          .websocket()
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString())
				          .log("client")
				          .collectList()
				          .block();

		Assert.assertNotNull(res);
		Assert.assertThat(res.get(0), is("test"));
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
				          .wiretap(true)
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

	@Test
	public void unidirectional() {
		int c = 10;
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> o.sendString(
		                                                  Mono.just("test")
		                                                      .delayElement(Duration.ofMillis(100))
		                                                      .repeat())))
		                       .wiretap(true)
		                       .bindNow();

		Flux<String> ws = HttpClient.create()
		                            .port(httpServer.address().getPort())
		                            .wiretap(true)
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
		Assert.assertNotNull(expected);

		StepVerifier.create(ws.take(c)
		                      .log())
		            .expectNextSequence(expected)
		            .expectComplete()
		            .verify();
	}

	@Test
	public void webSocketRespondsToRequestsFromClients() {
		AtomicInteger clientRes = new AtomicInteger();
		AtomicInteger serverRes = new AtomicInteger();

		httpServer =
				HttpServer.create()
				          .port(0)
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
				          .wiretap(true)
				          .bindNow(Duration.ofSeconds(5));

		HttpClient client = HttpClient.create()
		                              .port(httpServer.address().getPort())
		                              .wiretap(true);

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
	public void unidirectionalBinary() {
		int c = 10;
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> o.sendByteArray(
		                                                  Mono.just("test".getBytes(Charset.defaultCharset()))
		                                                      .delayElement(Duration.ofMillis(100))
		                                                      .repeat())))
		                       .wiretap(true)
		                       .bindNow();

		Flux<String> ws = HttpClient.create()
		                            .port(httpServer.address().getPort())
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
		Assert.assertNotNull(expected);

		StepVerifier.create(ws.take(c)
		                      .log())
		            .expectNextSequence(expected)
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
		                       .wiretap(true)
		                       .bindNow();

		Flux.interval(Duration.ofMillis(200))
		    .map(Object::toString)
		    .subscribe(client::onNext);

		HttpClient.create()
		          .port(httpServer.address().getPort())
		          .wiretap(true)
		          .websocket()
		          .uri("/test")
		          .handle((i, o) -> o.sendString(i.receive()
		                                          .asString()
		                                          .subscribeWith(client)))
		          .log()
		          .subscribe();

		Assert.assertTrue(serverLatch.await(10, TimeUnit.SECONDS));
		Assert.assertTrue(clientLatch.await(10, TimeUnit.SECONDS));
	}

	@Test
	public void simpleSubprotocolServerNoSubprotocol() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(Mono.just("test"))))
		                       .wiretap(true)
		                       .bindNow();

		StepVerifier.create(
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket("SUBPROTOCOL,OTHER")
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString()))
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
		                       .wiretap(true)
		                       .bindNow();

		StepVerifier.create(
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .headers(h -> h.add("Authorization", auth))
				          .websocket("SUBPROTOCOL,OTHER")
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString()))
		            //the SERVER returned null which means that it couldn't select a protocol
		            .verifyErrorMessage("Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	public void simpleSubprotocolServerSupported() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket("SUBPROTOCOL",
		                               (i, o) -> o.sendString(Mono.just("test"))))
		                       .wiretap(true)
		                       .bindNow();

		List<String> res =
				HttpClient.create()
				          .port(httpServer.address()
				                          .getPort())
				          .wiretap(true)
				          .headers(h -> h.add("Authorization", auth))
				          .websocket("SUBPROTOCOL,OTHER")
				          .uri("/test")
				          .handle((i, o) -> i.receive().asString())
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30));

		Assert.assertNotNull(res);
		Assert.assertThat(res.get(0), is("test"));
	}

	@Test
	public void simpleSubprotocolSelected() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
		                               "NOT, Common",
		                               (i, o) -> o.sendString(
		                                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .wiretap(true)
		                       .bindNow();

		List<String> res =
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .wiretap(true)
				          .headers(h -> h.add("Authorization", auth))
				          .websocket("Common,OTHER")
				          .uri("/test")
				          .handle((in, out) -> in.receive()
				                                 .asString()
				                                 .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
				          .log()
				          .collectList()
				          .block(Duration.ofSeconds(30));

		Assert.assertNotNull(res);
		Assert.assertThat(res.get(0), is("CLIENT:Common-SERVER:Common"));
	}

	@Test
	public void noSubprotocolSelected() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
		                               Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .wiretap(true)
		                       .bindNow();

		List<String> res =
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
				          .block(Duration.ofSeconds(30));

		Assert.assertNotNull(res);
		Assert.assertThat(res.get(0), is("CLIENT:null-SERVER:null"));
	}

	@Test
	public void anySubprotocolSelectsFirstClientProvided() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket("proto2,*", (i, o) -> o.sendString(
		                               Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .wiretap(true)
		                       .bindNow();

		List<String> res =
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
				          .block(Duration.ofSeconds(30));

		Assert.assertNotNull(res);
		Assert.assertThat(res.get(0), is("CLIENT:proto1-SERVER:proto1"));
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
		                               }))
		                       .wiretap(true)
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
		                       .wiretap(true)
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
		                       .wiretap(true)
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
	public void testServerMaxFramePayloadLengthFailed() {
		doTestServerMaxFramePayloadLength(10,
				Flux.just("1", "2", "12345678901", "3"), Flux.just("1", "2"), 2);
	}

	@Test
	public void testServerMaxFramePayloadLengthSuccess() {
		doTestServerMaxFramePayloadLength(11,
				Flux.just("1", "2", "12345678901", "3"), Flux.just("1", "2", "12345678901", "3"), 4);
	}

	private void doTestServerMaxFramePayloadLength(int maxFramePayloadLength, Flux<String> input, Flux<String> expectation, int count) {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.sendWebsocket(null, maxFramePayloadLength, (in, out) ->
				              out.sendObject(in.aggregateFrames()
				                               .receiveFrames()
				                               .map(WebSocketFrame::content)
				                               .map(byteBuf ->
				                                   byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
				                               .map(TextWebSocketFrame::new))))
				          .wiretap(true)
				          .bindNow();

		ReplayProcessor<String> output = ReplayProcessor.create();
		HttpClient.create()
		          .port(httpServer.address().getPort())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> out.sendString(input)
		                                  .then(in.aggregateFrames()
		                                          .receiveFrames()
		                                          .map(WebSocketFrame::content)
		                                          .map(byteBuf ->
		                                              byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
		                                          .take(count)
		                                          .subscribeWith(output)
		                                          .then()))
		          .blockLast(Duration.ofSeconds(30));

		assertThat(output.collectList().block(Duration.ofSeconds(30)))
				.isEqualTo(expectation.collectList().block(Duration.ofSeconds(30)));
	}


	@Test
	public void closePool() {
		ConnectionProvider pr = ConnectionProvider.fixed("wstest", 1);
		httpServer = HttpServer.create()
		                       .port(0)
		                       .handle((in, out) -> out.sendWebsocket(
		                               (i, o) -> o.sendString(
		                                                  Mono.just("test")
		                                                      .delayElement(Duration.ofMillis(100))
		                                                      .repeat())))
		                       .wiretap(true)
		                       .bindNow();

		Flux<String> ws = HttpClient.create(pr)
		                            .port(httpServer.address()
		                                            .getPort())
		                            .websocket()
		                            .uri("/")
		                            .receive()
		                            .asString();

		List<String> expected =
				Flux.range(1, 20)
				    .map(v -> "test")
				    .collectList()
				    .block();
		Assert.assertNotNull(expected);

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
	public void testCloseWebSocketFrameSentByServer() {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				                  res.sendWebsocket((in, out) -> out.sendObject(in.receiveFrames()
				                                                                  .doOnNext(WebSocketFrame::retain))))
				          .wiretap(true)
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
				          .wiretap(true)
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
	public void testConnectionAliveWhenTransformationErrors_2() {
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
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> res.sendWebsocket(handler))
				          .wiretap(true)
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
		                                          .map(byteBuf ->
		                                              byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
		                                          .take(count)
		                                          .subscribeWith(output)
		                                          .then()))
		          .blockLast(Duration.ofSeconds(30));

		assertThat(output.collectList().block(Duration.ofSeconds(30)))
				.isEqualTo(expectation.collectList().block(Duration.ofSeconds(30)));

	}

	@Test
	public void testClientOnCloseIsInvokedClientSendClose() throws Exception {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) ->
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

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		assertThat(error.get()).isFalse();
	}

	@Test
	public void testClientOnCloseIsInvokedClientDisposed() throws Exception {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) ->
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

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		assertThat(error.get()).isFalse();
	}

	@Test
	public void testClientOnCloseIsInvokedServerInitiatedClose() throws Exception {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) ->
				                  out.sendString(Mono.just("test"))))
				          .wiretap(true)
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
	public void testIssue460() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .host("::1")
				          .wiretap(true)
				          .handle((req, res) -> res.sendWebsocket((in, out) -> Mono.never()))
				          .bindNow();

		HttpClient httpClient =
				HttpClient.create()
				          .addressSupplier(server::address)
				          .wiretap(true)
				          .headers(h -> h.add(HttpHeaderNames.HOST, "[::1"));

		StepVerifier.create(httpClient.websocket()
		                              .connect())
		                              .expectError()
		                              .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	public void testIssue444() {
		doTestIssue444((in, out) ->
				out.sendObject(Flux.error(new Throwable())
				                   .onErrorResume(ex -> out.sendClose(1001, "Going Away"))
				                   .cast(WebSocketFrame.class)));
		doTestIssue444((in, out) ->
				out.send(Flux.range(0, 10)
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
		httpServer =
				HttpServer.create()
				          .host("localhost")
				          .port(0)
				          .handle((req, res) -> res.sendWebsocket(null, fn))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
				HttpClient.create()
				          .addressSupplier(() -> httpServer.address())
				          .wiretap(true)
				          .websocket()
				          .uri("/")
				          .handle((i, o) -> i.receiveFrames()
				                             .then()))
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue507_1() {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .compress(true)
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) -> out.sendString(Mono.just("test"))))
				          .wiretap(true)
				          .bindNow();

		AtomicBoolean clientHandler = new AtomicBoolean();
		HttpClient client =
				HttpClient.create()
				          .addressSupplier(httpServer::address)
				          .wiretap(true);

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

		StepVerifier.create(client.websocket()
		                          .uri("/")
		                          .handle(receiver))
		            .expectNextMatches(t -> "test".equals(t.getT1()) && "null".equals(t.getT2()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
		assertThat(clientHandler.get()).isFalse();

		StepVerifier.create(client.compress(true)
		                          .websocket()
		                          .uri("/")
		                          .handle(receiver))
		            .expectNextMatches(t -> "test".equals(t.getT1()) && !"null".equals(t.getT2()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
		assertThat(clientHandler.get()).isTrue();
	}

	@Test
	public void testIssue507_2() {
		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) -> out.sendString(Mono.just("test"))))
				          .wiretap(true)
				          .bindNow();

		AtomicBoolean clientHandler = new AtomicBoolean();
		HttpClient client =
				HttpClient.create()
				          .addressSupplier(httpServer::address)
				          .wiretap(true);

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

		StepVerifier.create(client.websocket()
		                          .uri("/")
		                          .handle(receiver))
		            .expectNextMatches(t -> "test".equals(t.getT1()) && "null".equals(t.getT2()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
		assertThat(clientHandler.get()).isFalse();

		StepVerifier.create(client.compress(true)
		                          .websocket()
		                          .uri("/")
		                          .handle(receiver))
		            .expectNextMatches(t -> "test".equals(t.getT1()) && "null".equals(t.getT2()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
		assertThat(clientHandler.get()).isFalse();
	}

	// https://bugzilla.mozilla.org/show_bug.cgi?id=691300
	@Test
	public void firefoxConnectionTest() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .route(r -> r.ws("/ws", (in, out) -> out.sendString(Mono.just("test"))))
		                       .wiretap(true)
		                       .bindNow();

		HttpClientResponse res =
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .wiretap(true)
				          .headers(h -> {
				                  h.add(HttpHeaderNames.CONNECTION, "keep-alive, Upgrade");
				                  h.add(HttpHeaderNames.UPGRADE, "websocket");
				                  h.add(HttpHeaderNames.ORIGIN, "http://localhost");
				          })
				          .get()
				          .uri("/ws")
				          .response()
				          .block();
		Assert.assertNotNull(res);
		Assert.assertThat(res.status(), is(HttpResponseStatus.SWITCHING_PROTOCOLS));
	}

	@Test
	public void testIssue821() throws Exception {
		Scheduler scheduler = Schedulers.newSingle("ws");
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		httpServer = HttpServer.create()
		                       .port(0)
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
		                                          null)
		                           , 500, TimeUnit.MILLISECONDS);
		                           return out.sendString(Mono.just("test"));
		                       }))
		                       .wiretap(true)
		                       .bindNow();

		String res =
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .wiretap(true)
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
	public void testIssue900_1() {
		MonoProcessor<WebSocketCloseStatus> statusClient = MonoProcessor.create();

		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) -> out.sendObject(in.receiveFrames()
				                                                              .doOnNext(WebSocketFrame::retain))))
				          .wiretap(true)
				          .bindNow();

		Flux<WebSocketFrame> response =
				HttpClient.create()
				          .port(httpServer.port())
				          .websocket()
				          .uri("/")
				          .handle((in, out) -> {
				              in.receiveCloseStatus()
				                .subscribeWith(statusClient);

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

		StepVerifier.create(statusClient)
		            .expectNext(new WebSocketCloseStatus(1008, "something"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue900_2() {
		MonoProcessor<WebSocketCloseStatus> statusServer = MonoProcessor.create();
		DirectProcessor<WebSocketFrame> incomingData = DirectProcessor.create();

		httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.sendWebsocket((in, out) -> {
				                  in.receiveCloseStatus()
				                    .subscribeWith(statusServer);

				                  return out.sendObject(Flux.just(new TextWebSocketFrame("echo"),
				                                                  new CloseWebSocketFrame(1008, "something"))
				                                            .delayElements(Duration.ofMillis(100)))
				                            .then(in.receiveFrames()
				                                    .subscribeWith(incomingData)
				                                    .then());
				              })
				          )
				          .wiretap(true)
				          .bindNow();

		HttpClient.create()
		          .port(httpServer.port())
		          .websocket()
		          .uri("/")
		          .handle((in, out) -> out.sendObject(in.receiveFrames()
		                                                .doOnNext(WebSocketFrame::retain)))
		          .subscribe();

		StepVerifier.create(incomingData)
		            .expectNext(new TextWebSocketFrame("echo"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(statusServer)
		            .expectNext(new WebSocketCloseStatus(1008, "something"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}
}
