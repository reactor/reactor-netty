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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.Test;

import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;
import reactor.test.StepVerifier;

/**
 * @author Violeta Georgieva
 */
public class HttpClientWSOperationsTest {

	@Test
	public void failOnClientErrorDisabled() {
		failOnClientServerError(false, false, 401, "", "");
	}

	@Test
	public void failOnClientErrorEnabled() {
		failOnClientServerError(true, false, 401, "", "");
	}

	@Test
	public void failOnServerErrorDisabled() {
		failOnClientServerError(false, false, 500, "", "");
	}

	@Test
	public void failOnServerErrorEnabled() {
		failOnClientServerError(false, true, 500, "", "");
	}

	@Test
	public void failOnServerErrorDisabledFailedNegotiation() {
		failOnClientServerError(false, false, 200, "Server-Protocol", "Client-Protocol");
	}

	@Test
	public void failOnServerErrorEnabledFailedNegotiation() {
		failOnClientServerError(false, true, 200, "Server-Protocol", "Client-Protocol");
	}

	private void failOnClientServerError(boolean clientError, boolean serverError,
			int serverStatus, String serverSubprotocol, String clientSubprotocol) {
		Connection httpServer = HttpServer.create(0).newRouter(
			routes -> routes.post("/login", (req, res) -> {
						res.status(serverStatus);
						return res.sendHeaders();
					})
					.get("/ws", (req, res) -> {
						int token = Integer.parseInt(req.requestHeaders().get("Authorization"));
						if (token >= 400) {
							res.status(token);
							return res.send();
						}
						return res.sendWebsocket(serverSubprotocol, (i, o) -> o.sendString(Mono.just("test")));
					})
			)
		                                  .block(Duration.ofSeconds(30));

		Mono<HttpClientResponse> response =
			HttpClient.create(httpServer.address().getPort())
			          .get("/ws", request -> Mono.just(request.failOnClientError(clientError)
			                                                  .failOnServerError(serverError))
			                                     .transform(req -> doLoginFirst(req, httpServer.address().getPort()))
			                                     .flatMapMany(req -> ws(req, clientSubprotocol)))
			          .switchIfEmpty(Mono.error(new Exception()));


		if (clientError || serverError) {
			StepVerifier.create(response)
			            .expectError()
			            .verify(Duration.ofSeconds(30));
		}
		else {
			StepVerifier.create(response)
			            .assertNext(res ->
			                assertThat(res.status().code()).isEqualTo(serverStatus == 200 ? 101 : serverStatus))
			            .expectComplete()
			            .verify(Duration.ofSeconds(30));
		}
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
		return HttpClient.create(port)
		                 .post("/login", req -> req.failOnClientError(false)
		                                           .failOnServerError(false))
		                 .map(res -> res.status().code() + "");
	}

	private WebsocketOutbound ws(HttpClientRequest request, String clientSubprotocol) {
		return request.sendWebsocket(clientSubprotocol);
	}
}
