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

import java.nio.file.Paths;
import java.util.Arrays;

import io.netty.handler.codec.LineBasedFrameDecoder;
import org.junit.Test;
import org.testng.Assert;
import reactor.core.publisher.Flux;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.TcpClient;
import reactor.test.StepVerifier;

/**
 * @author Stephane Maldini
 */
public class HttpServerTests {

	@Test
	public void flushOnComplete() {

		Flux<String> test = Flux.range(0, 100)
		                        .map(n -> String.format("%010d", n));

		NettyContext c = HttpServer.create(0)
		                           .newHandler((req, resp) -> resp.sendString(test.map(s -> s + "\n")))
		                           .block();

		Flux<String> client = HttpClient.create(c.address()
		                                         .getPort())
		                                .get("/")
		                                .block()
		                                .addDecoder(new LineBasedFrameDecoder(10))
		                                .receive()
		                                .asString();

		StepVerifier.create(client)
		            .expectNextSequence(test.toIterable())
		            .expectComplete()
		            .verify();
	}

	@Test
	public void keepAlive() {
		NettyContext c = HttpServer.create(0)
		                           .newRouter(routes -> routes.directory("/test",
				                           Paths.get(getClass().getResource("/public")
				                                               .getFile())))
		                           .block();

		HttpResources.set(PoolResources.fixed("http", 1));

		HttpClientResponse response0 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/index.html")
		                                         .block();

		HttpClientResponse response1 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test.css")
		                                         .block();

		HttpClientResponse response2 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test1.css")
		                                         .block();

		HttpClientResponse response3 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test2.css")
		                                         .block();

		HttpClientResponse response4 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test3.css")
		                                         .block();

		HttpClientResponse response5 = HttpClient.create(c.address()
		                                                  .getPort())
		                                         .get("/test/test4.css")
		                                         .block();

		HttpClientResponse response6 = HttpClient.create(opts -> opts.connect(c.address()
		                                                                       .getPort())
		                                                             .disablePool())
		                                         .get("/test/test5.css")
		                                         .block();

		Assert.assertEquals(response0.channel(), response1.channel());
		Assert.assertEquals(response0.channel(), response2.channel());
		Assert.assertEquals(response0.channel(), response3.channel());
		Assert.assertEquals(response0.channel(), response4.channel());
		Assert.assertEquals(response0.channel(), response5.channel());
		Assert.assertNotEquals(response0.channel(), response6.channel());

		HttpResources.reset();
	}

}
