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
package reactor.netty.http.client;

import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.WebsocketServerSpec;
import reactor.test.StepVerifier;

/**
 * @author Violeta Georgieva
 */
public class WebsocketClientOperationsTest {

	@Test
	public void requestError() {
		failOnClientServerError(401, "", "");
	}

	@Test
	public void serverError() {
		failOnClientServerError(500, "", "");
	}

	@Test
	public void failedNegotiation() {
		failOnClientServerError(200, "Server-Protocol", "Client-Protocol");
	}

	private void failOnClientServerError(
			int serverStatus, String serverSubprotocol, String clientSubprotocol) {
		DisposableServer httpServer =
				HttpServer.create()
				          .port(0)
				          .route(routes ->
				              routes.post("/login", (req, res) -> res.status(serverStatus).sendHeaders())
				                    .get("/ws", (req, res) -> {
				                        int token = Integer.parseInt(req.requestHeaders().get("Authorization"));
				                        if (token >= 400) {
				                            return res.status(token).send();
				                        }
				                        return res.sendWebsocket((i, o) -> o.sendString(Mono.just("test")),
				                                WebsocketServerSpec.builder().protocols(serverSubprotocol).build());
				                    }))
				          .wiretap(true)
				          .bindNow();

		Flux<String> response =
				HttpClient.create()
				          .port(httpServer.port())
				          .wiretap(true)
				          .headersWhen(h -> login(httpServer.port()).map(token -> h.set("Authorization", token)))
				          .websocket(clientSubprotocol)
				          .uri("/ws")
				          .handle((i, o) -> i.receive().asString())
				          .log()
				          .switchIfEmpty(Mono.error(new Exception()));

		StepVerifier.create(response)
		            .expectError(WebSocketHandshakeException.class)
		            .verify();

		httpServer.disposeNow();
	}

	private Mono<String> login(int port) {
		return HttpClient.create()
		                 .port(port)
		                 .wiretap(true)
		                 .post()
		                 .uri("/login")
		                 .responseSingle((res, buf) -> Mono.just(res.status().code() + ""));
	}
}
