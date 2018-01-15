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
package reactor.ipc.netty.channel;

import java.time.Duration;
import java.util.Random;

import org.junit.Test;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetector.Level;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;

public class FluxReceiveTest {

	@Test
	public void testByteBufsReleasedWhenTimeout() {
		ResourceLeakDetector.setLevel(Level.PARANOID);

		byte[] content = new byte[1024*8];
		Random rndm = new Random();
		rndm.nextBytes(content);

		DisposableServer server1 =
				HttpServer.create()
				          .port(0)
				          .router(routes ->
				                     routes.get("/target", (req, res) ->
				                           res.sendByteArray(Flux.just(content)
				                                                 .delayElements(Duration.ofMillis(100)))))
				          .wiretap()
				          .bindNow();

		DisposableServer server2 =
				HttpServer.create()
				          .port(0)
				          .router(routes ->
				                     routes.get("/forward", (req, res) ->
				                           HttpClient.prepare()
				                                     .port(server1.address().getPort())
				                                     .wiretap()
				                                     .get()
				                                     .uri("/target")
				                                     .responseContent()
				                                     .aggregate()
				                                     .asString()
				                                     .log()
				                                     .delayElement(Duration.ofMillis(50))
				                                     .timeout(Duration.ofMillis(50))
				                                     .then()))
				          .wiretap()
				          .bindNow();

		Flux.range(0, 50)
		    .flatMap(i -> HttpClient.prepare()
		                            .port(server2.address().getPort())
		                            .wiretap()
		                            .get()
		                            .uri("/forward")
		                            .responseContent()
		                            .log()
		                            .onErrorResume(t -> Mono.empty()))
		    .blockLast(Duration.ofSeconds(30));

		server1.dispose();
		server2.dispose();

		ResourceLeakDetector.setLevel(Level.SIMPLE);
	}
}
