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
package reactor.netty.channel;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

public class FluxReceiveTest {

	@Test
	public void testByteBufsReleasedWhenTimeout() {
		byte[] content = new byte[1024*8];
		Random rndm = new Random();
		rndm.nextBytes(content);

		DisposableServer server1 =
				HttpServer.create()
				          .port(0)
				          .route(routes ->
				                     routes.get("/target", (req, res) ->
				                           req.receive()
				                              .thenMany(res.sendByteArray(Flux.just(content)
				                                                              .delayElements(Duration.ofMillis(100))))))
				          .bindNow();

		DisposableServer server2 =
				HttpServer.create()
				          .port(0)
				          .route(routes ->
				                     routes.get("/forward", (req, res) ->
				                           HttpClient.create()
				                                     .port(server1.address().getPort())
				                                     .get()
				                                     .uri("/target")
				                                     .responseContent()
				                                     .aggregate()
				                                     .asString()
				                                     .log()
				                                     .timeout(Duration.ofMillis(50))
				                                     .then()))
				          .bindNow();

		Flux.range(0, 50)
		    .flatMap(i -> HttpClient.create()
		                            .port(server2.address().getPort())
		                            .get()
		                            .uri("/forward")
		                            .responseContent()
		                            .log()
		                            .onErrorResume(t -> Mono.empty()))
		    .blockLast(Duration.ofSeconds(15));

		server1.disposeNow();
		server2.disposeNow();
	}

	@Test
	public void testByteBufsReleasedWhenTimeoutUsingHandlers() {
		byte[] content = new byte[1024*8];
		Random rndm = new Random();
		rndm.nextBytes(content);

		DisposableServer server1 =
				HttpServer.create()
				          .port(0)
				          .route(routes ->
				                     routes.get("/target", (req, res) ->
				                           req.receive()
				                              .thenMany(res.sendByteArray(Flux.just(content)
				                                                              .delayElements(Duration.ofMillis(100))))))
				          .bindNow();

		DisposableServer server2 =
				HttpServer.create()
				          .port(0)
				          .route(routes ->
				                     routes.get("/forward", (req, res) ->
				                           HttpClient.create()
				                                     .port(server1.address().getPort())
				                                     .tcpConfiguration(tcpClient ->
				                                         tcpClient.doOnConnected(c ->
				                                             c.addHandlerFirst(new ReadTimeoutHandler(50, TimeUnit.MILLISECONDS))))
				                                     .get()
				                                     .uri("/target")
				                                     .responseContent()
				                                     .aggregate()
				                                     .asString()
				                                     .log()
				                                     .then()))
				          .bindNow();

		Flux.range(0, 50)
		    .flatMap(i -> HttpClient.create()
		                            .port(server2.address().getPort())
		                            .get()
		                            .uri("/forward")
		                            .responseContent()
		                            .log()
		                            .onErrorResume(t -> Mono.empty()))
		    .blockLast(Duration.ofSeconds(15));

		server1.disposeNow();
		server2.disposeNow();
	}
}
