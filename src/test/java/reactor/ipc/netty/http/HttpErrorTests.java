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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.tcp.TcpClient;

/**
 * @author tokuhirom
 */
public class HttpErrorTests {

	@Test
	public void test() {
		DisposableServer server = HttpServer.create()
		                              .port(0)
		                              .router(httpServerRoutes -> httpServerRoutes.get(
				                                "/",
				                                (httpServerRequest, httpServerResponse) -> {
					                                return httpServerResponse.sendString(
							                                Mono.error(new IllegalArgumentException()));
				                                }))
		                                    .bindNow(Duration.ofSeconds(30));

		HttpClient client = HttpClient.from(TcpClient.newConnection())
		                              .port(server.address().getPort());

		HttpClientResponse r = client.get()
		                             .uri("/")
		                             .response()
		                             .block(Duration.ofSeconds(30));

		List<String> result = r.receive()
		                    .asString(StandardCharsets.UTF_8)
		                    .collectList().block(Duration.ofSeconds(30));

		System.out.println("END");


		Assert.assertTrue(result.isEmpty());
		Assert.assertTrue(r.isDisposed());
		server.dispose();
	*/}
}