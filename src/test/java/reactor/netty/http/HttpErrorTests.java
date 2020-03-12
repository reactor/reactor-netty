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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

/**
 * @author tokuhirom
 */
public class HttpErrorTests {

	@Test
	public void test() {
		DisposableServer server = HttpServer.create()
		                              .port(0)
		                              .route(httpServerRoutes -> httpServerRoutes.get(
				                                "/",
				                                (httpServerRequest, httpServerResponse) -> {
					                                return httpServerResponse.sendString(
							                                Mono.error(new IllegalArgumentException("test")));
				                                }))
		                                    .bindNow(Duration.ofSeconds(30));

		HttpClient client = HttpClient.create()
		                              .port(server.address().getPort());

		StepVerifier.create(client.get()
		                             .uri("/")
		                             .responseContent()
		                             .asString(StandardCharsets.UTF_8)
		                             .collectList())
		            .expectNextMatches(List::isEmpty)
		            .verifyComplete();

		server.disposeNow();
	}
}