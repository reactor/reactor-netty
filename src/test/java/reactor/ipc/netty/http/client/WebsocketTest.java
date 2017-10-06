/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import static org.hamcrest.CoreMatchers.is;

/**
 * @author tjreactive
 * @author smaldini
 */
public class WebsocketTest {

	static final String auth = "bearer abc";

	Connection httpServer = null;

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

		String res = HttpClient.create(httpServer.address()
		                                  .getPort())
		                .get("/test",
				                out -> out.addHeader("Authorization", auth)
				                          .sendWebsocket())
		                .flatMapMany(in -> in.receive()
		                                 .asString())
		                .log()
		                .collectList()
		                .block(Duration.ofSeconds(30))
		                .get(0);

		Assert.assertThat(res, is("test"));
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
				                       (i, o) -> o.options(opt -> opt.flushOnEach())
				                                  .sendString(
						                                  Mono.just("test")
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .block(Duration.ofSeconds(30));

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
	public void unidirectionalBinary() {
		int c = 10;
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       (i, o) -> o.options(opt -> opt.flushOnEach())
				                                  .sendByteArray(
						                                  Mono.just("test".getBytes())
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat())))
		                       .block(Duration.ofSeconds(30));

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
		httpServer = HttpServer.create(0)
		                                    .newHandler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                                    Mono.just("test"))))
		                                    .block(Duration.ofSeconds(30));

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
	public void simpleSubprotocolServerNotSupported() throws Exception {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       "protoA,protoB",
				                       (i, o) -> o.sendString(Mono.just("test"))))
		                       .block(Duration.ofSeconds(30));

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
	public void simpleSubprotocolServerSupported() throws Exception {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       "SUBPROTOCOL",
				                       (i, o) -> o.sendString(
						                       Mono.just("test"))))
		                       .block(Duration.ofSeconds(30));

		String res = HttpClient.create(httpServer.address().getPort())
		                       .get("/test",
				                out -> out.addHeader("Authorization", auth)
				                          .sendWebsocket("SUBPROTOCOL,OTHER"))
		                .flatMapMany(in -> in.receive().asString()).log().collectList().block(Duration.ofSeconds(30)).get(0);

		Assert.assertThat(res, is("test"));
	}

	@Test
	public void simpleSubprotocolSelected() throws Exception {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket(
				                       "NOT, Common",
				                       (i, o) -> o.sendString(
						                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .block(Duration.ofSeconds(30));

		String res = HttpClient.create(httpServer.address().getPort())
		                       .get("/test",
				                out -> out.addHeader("Authorization", auth)
				                          .sendWebsocket("Common,OTHER"))
		                       .map(HttpClientResponse::receiveWebsocket)
		                       .flatMapMany(in -> in.receive().asString()
				                       .map(srv -> "CLIENT:" + in.selectedSubprotocol() + "-" + srv))
		                       .log().collectList().block(Duration.ofSeconds(30)).get(0);

		Assert.assertThat(res, is("CLIENT:Common-SERVER:Common"));
	}

	@Test
	public void noSubprotocolSelected() {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .block(Duration.ofSeconds(30));

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
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocket("proto2,*", (i, o) -> o.sendString(
				                       Mono.just("SERVER:" + o.selectedSubprotocol()))))
		                       .block(Duration.ofSeconds(30));

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
	}

}
