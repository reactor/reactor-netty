/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;

/**
 * @author tjreactive
 * @author smaldini
 */
public class WebsocketTests {

	private NettyContext httpServer;

	static final String auth =
			"bearer abc";

	@Before
	public void setup() throws InterruptedException {
		setupServer();
	}

	private void setupServer() throws InterruptedException {
		httpServer = HttpServer.create(0)
		                       .newHandler((in, out) -> out.sendWebsocketText((i, o) -> o.sendString(
				                       Mono.just("test"))))
		                       .block();
	}

	@Test
	public void simpleTest() {
		String res = HttpClient.create(httpServer.address()
		                                         .getPort())
		                       .get("/test",
				                       out -> out.addHeader("Authorization", auth)
				                                 .sendWebsocketText())
		                       .flatMap(in -> in.receive()
		                                        .asString())
		                       .log()
		                       .collectList()
		                       .block()
		                       .get(0);


		res = HttpClient.create(httpServer.address()
		                                         .getPort())
		                       .get("/test",
				                       out -> out.addHeader("Authorization", auth)
				                                 .sendWebsocketText())
		                       .flatMap(in -> in.receive()
		                                        .asString())
		                       .log()
		                       .collectList()
		                       .block()
		                       .get(0);

		if (!res.equals("test")) {
			throw new IllegalStateException("test");
		}
	}

	@After
	public void teardown() throws Exception {
		httpServer.dispose();
	}


}
