/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

/**
 * @author tjreactive
 * @author smaldini
 */
public class WebsocketTests {

	static final String auth = "bearer abc";

	@Test
	public void simpleTest() {
		NettyContext httpServer = HttpServer.create(0)
		                                    .newHandler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                                    Mono.just("test"))))
		                                    .block();

		String res = HttpClient.create(httpServer.address()
		                                         .getPort())
		                       .get("/test",
				                       out -> out.addHeader("Authorization", auth)
				                                 .sendWebsocket())
		                       .flatMap(in -> in.receive()
		                                        .asString())
		                       .log()
		                       .collectList()
		                       .block()
		                       .get(0);

		res = HttpClient.create(httpServer.address()
		                                  .getPort())
		                .get("/test",
				                out -> out.addHeader("Authorization", auth)
				                          .sendWebsocket())
		                .flatMap(in -> in.receive()
		                                 .asString())
		                .log()
		                .collectList()
		                .block()
		                .get(0);

		if (!res.equals("test")) {
			throw new IllegalStateException("test");
		}

		httpServer.dispose();
	}

	@Test
	public void unidirectional() {
		int c = 10;
		NettyContext httpServer = HttpServer.create(0)
		                                    .newHandler((in, out) -> out.sendWebsocket(
		                                    		(i, o) -> o.options(opt -> opt.flushOnEach())
				                                               .sendString(
				                                    Flux.just("test")
				                                        .delayMillis(100)
				                                        .repeat())))
		                                    .block();

		Flux<String> ws = HttpClient.create(httpServer.address()
		                                              .getPort())
		                            .ws("/")
		                            .flatMap(in -> in.receiveWebsocket()
		                                             .receive()
		                                             .asString());

		StepVerifier.create(ws.take(c))
		            .expectNextSequence(Flux.range(1, c)
		                                    .map(v -> "test")
		                                    .toIterable())
		            .expectComplete()
		            .verify();

		httpServer.dispose();
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

		NettyContext httpServer = HttpServer.create(0)
		                                    .newHandler((in, out) -> out.sendWebsocket((i, o) -> o.sendString(
				                                    i.receive()
				                                     .asString()
				                                     .take(c)
				                                     .subscribeWith(server))))
		                                    .block();

		Flux.intervalMillis(200)
		    .map(Object::toString)
		    .subscribe(client::onNext);

		HttpClient.create(httpServer.address()
		                            .getPort())
		          .ws("/test")
		          .then(in -> in.receiveWebsocket((i, o) -> o.options(opt -> opt.flushOnEach())
		                                                     .sendString(i.receive()
		                                                                  .asString()
		                                                                  .subscribeWith(
				                                                                  client))))
		          .subscribe();

		Assert.assertTrue(serverLatch.await(10, TimeUnit.SECONDS));
		Assert.assertTrue(clientLatch.await(10, TimeUnit.SECONDS));
	}


}
