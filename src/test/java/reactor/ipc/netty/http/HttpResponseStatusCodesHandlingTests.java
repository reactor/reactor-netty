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
package reactor.ipc.netty.http;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

/**
 * @author Violeta Georgieva
 */
public class HttpResponseStatusCodesHandlingTests {

	@Test
	public void httpStatusCode404IsHandledByTheClient() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .router(r -> r.post("/test", (req, res) -> res.send(req.receive()
				                                                                    .log("server-received"))))
				          .wiretap()
				          .bindNow();

		HttpClient client =
				HttpClient.prepare()
				          .port(server.address().getPort())
				          .tcpConfiguration(tcpClient -> tcpClient.host("localhost")
				                                                  .noSSL())
				          .wiretap();

		Mono<Integer> content = client.headers(h -> h.add("Content-Type", "text/plain"))
				                      .request(HttpMethod.GET)
				                      .uri("/unsupportedURI")
				                      .send(ByteBufFlux.fromString(Flux.just("Hello")
				                                                       .log("client-send")))
				                      .responseSingle((res, buf) -> Mono.just(res.status().code()))
				                     .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(content)
				    .expectNext(404)
				    .verifyComplete();

		server.dispose();
	}
}