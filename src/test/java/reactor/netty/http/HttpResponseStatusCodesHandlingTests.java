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
package reactor.netty.http;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
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
				          .route(r -> r.post("/test", (req, res) -> res.send(req.receive()
				                                                                .log("server-received"))))
				          .wiretap(true)
				          .bindNow();

		HttpClient client =
				HttpClient.create()
				          .port(server.address().getPort())
				          .wiretap(true);

		Mono<Integer> content = client.headers(h -> h.add("Content-Type", "text/plain"))
				                      .request(HttpMethod.GET)
				                      .uri("/status/404")
				                      .send(ByteBufFlux.fromString(Flux.just("Hello")
				                                                       .log("client-send")))
				                      .responseSingle((res, buf) -> Mono.just(res.status().code()))
				                     .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(content)
				    .expectNext(404)
				    .verifyComplete();

		server.disposeNow();
	}
}