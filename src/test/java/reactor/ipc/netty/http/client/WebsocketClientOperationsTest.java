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
package reactor.ipc.netty.http.client;

import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.server.HttpServer;
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
		DisposableServer httpServer = HttpServer.create()
		                                  .port(0)
		                                  .route(
			routes -> routes.post("/login", (req, res) -> res.status(serverStatus).sendHeaders())
					.get("/ws", (req, res) -> {
						int token = Integer.parseInt(req.requestHeaders().get("Authorization"));
						if (token >= 400) {
							return res.status(token).send();
						}
						return res.sendWebsocket(serverSubprotocol, (i, o) -> o.sendString(Mono.just("test")));
					})
			)
		                                  .wiretap()
		                                  .bindNow();

		Flux<String> response =
			HttpClient.prepare()
			          .port(httpServer.address().getPort())
			          .wiretap()
			          .websocket(clientSubprotocol)
			          .uri("/ws")
			          .send(request ->
			              Mono.just(request)
			                  .transform(req -> doLoginFirst(req, httpServer.address().getPort()))
					          .then()
			          )
			          .handle((i, o) -> i.receive().asString())
			          .log()
			          .switchIfEmpty(Mono.error(new Exception()));

		StepVerifier.create(response)
		            .expectError(WebSocketHandshakeException.class)
		            .verify();

		httpServer.dispose();
	}

	private Mono<HttpClientRequest> doLoginFirst(Mono<HttpClientRequest> request, int port) {
		return Mono.zip(request, login(port))
		           .map(tuple -> {
		               HttpClientRequest req = tuple.getT1();
		               req.addHeader("Authorization", tuple.getT2());
		               return req;
		           });
	}

	private Mono<String> login(int port) {
		return HttpClient.prepare()
		                 .port(port)
		                 .wiretap()
		                 .post()
		                 .uri("/login")
		                 .responseSingle((res, buf) -> Mono.just(res.status().code() + ""));
	}
}
