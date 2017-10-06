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
package reactor.ipc.netty.channel;

import java.time.Duration;
import java.util.Random;

import org.junit.Test;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;

public class FluxReceiveTest {

	@Test
	public void testByteBufsReleasedWhenTimeout() {
		ResourceLeakDetector.setLevel(Level.PARANOID);

		byte[] content = new byte[1024*8];
		Random rndm = new Random();
		rndm.nextBytes(content);

		Connection server1 =
				HttpServer.create(0)
				          .newRouter(routes ->
				                     routes.get("/target", (req, res) ->
				                           res.sendByteArray(Flux.just(content)
				                                                 .delayElements(Duration.ofMillis(100)))))
				          .block(Duration.ofSeconds(30));

		Connection server2 =
				HttpServer.create(0)
				          .newRouter(routes ->
				                     routes.get("/forward", (req, res) ->
				                           HttpClient.create(server1.address().getPort())
				                                     .get("/target")
				                                     .log()
				                                     .delayElement(Duration.ofMillis(50))
				                                     .flatMap(response -> response.receive().aggregate().asString())
				                                     .timeout(Duration.ofMillis(50))
				                                     .then()))
				          .block(Duration.ofSeconds(30));

		Flux.range(0, 50)
		    .flatMap(i -> HttpClient.create(server2.address().getPort())
		                            .get("/forward")
		                            .log()
		                            .onErrorResume(t -> Mono.empty()))
		    .blockLast(Duration.ofSeconds(30));

		ResourceLeakDetector.setLevel(Level.SIMPLE);
	}
}
