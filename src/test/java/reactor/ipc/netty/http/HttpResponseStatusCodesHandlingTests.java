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
package reactor.ipc.netty.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

/**
 * @author Violeta Georgieva
 */
public class HttpResponseStatusCodesHandlingTests {

	@Test
	public void httpStatusCode404IsHandledByTheClient() {
		Connection server =
				HttpServer.create(0)
				          .newRouter(r -> r.post("/test", (req, res) -> res.send(req.receive()
				                                                                    .log("server-received"))))
				          .block(Duration.ofSeconds(30));

		HttpClient client = HttpClient.create("localhost", server.address().getPort());

		List<String> replyReceived = new ArrayList<>();
		Mono<String> content = client.get("/unsupportedURI", req ->
				                         req.addHeader("Content-Type", "text/plain")
				                            .sendString(Flux.just("Hello")
				                                            .log("client-send"))
				                     )
				                     .flatMapMany(res -> res.receive()
				                                            .asString()
				                                            .log("client-received")
				                                            .doOnNext(s -> replyReceived.add(s)))
				                     .next()
				                     .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(content)
				    .expectErrorMatches(t -> t.getMessage().equals("HTTP request failed with code: 404.\nFailing URI: " +
						"/unsupportedURI"))
				    .verify(Duration.ofSeconds(30));

		Assertions.assertThat(replyReceived).isEmpty();

		server.dispose();
	}
}