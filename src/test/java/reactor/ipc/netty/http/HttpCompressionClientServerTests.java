/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.http;

import java.net.InetSocketAddress;
import java.time.Duration;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.http.server.HttpServer;

/**
 * @author mostroverkhov
 */
public class HttpCompressionClientServerTests {

	@Test
	public void trueEnabledIncludeContentEncoding() throws Exception {

		HttpServer server = HttpServer.create(o -> o.listen(0)
		                                            .compression(true));

		NettyContext nettyContext =
				server.newHandler((in, out) -> out.sendString(Mono.just("reply")))
				      .block(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.create(o -> o.compression(true)
		                                            .connect(address(nettyContext)));
		client.get("/test", o -> {
			Assert.assertTrue(o.requestHeaders()
			                   .contains("Accept-Encoding", "gzip", true));
			return o;
		})
		      .block();

		nettyContext.dispose();
		nettyContext.onClose()
		            .block();
	}

	@Test
	public void serverCompressionDefault() throws Exception {
		HttpServer server = HttpServer.create(0);

		NettyContext nettyContext =
				server.newHandler((in, out) -> out.sendString(Mono.just("reply")))
				      .block(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.create(o -> o.compression(true)
		                                            .connect(address(nettyContext)));
		HttpClientResponse resp =
				client.get("/test", req -> req.header("Accept-Encoding", "gzip"))
				      .block();

		String reply = resp.receive()
		                   .asString()
		                   .blockFirst();
		Assert.assertEquals("reply", reply);
		nettyContext.dispose();
		nettyContext.onClose()
		            .block();
	}

	@Test
	public void serverCompressionDisabled() throws Exception {
		HttpServer server = HttpServer.create(o -> o.listen(0)
		                                            .compression(false));

		NettyContext nettyContext =
				server.newHandler((in, out) -> out.sendString(Mono.just("reply")))
				      .block(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.create(o -> o.compression(true)
		                                            .connect(address(nettyContext)));
		HttpClientResponse resp =
				client.get("/test", req -> req.header("Accept-Encoding", "gzip"))
				      .block();

		String reply = resp.receive()
		                   .asString()
		                   .blockFirst();
		Assert.assertEquals("reply", reply);
		nettyContext.dispose();
		nettyContext.onClose()
		            .block();
	}

	@Test
	public void serverCompressionAlwaysEnabled() throws Exception {
		HttpServer server = HttpServer.create(o -> o.listen(0)
		                                            .compression(true));

		NettyContext nettyContext =
				server.newHandler((in, out) -> out.sendString(Mono.just("reply")))
				      .block(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.create(o -> o.compression(true)
		                                            .connect(address(nettyContext)));
		HttpClientResponse resp =
				client.get("/test", req -> req.header("Accept-Encoding", "gzip"))
				      .block();

		String reply = resp.receive()
		                   .asString()
		                   .blockFirst();
		Assert.assertEquals("reply", reply);
		nettyContext.dispose();
		nettyContext.onClose()
		            .block();
	}

	@Test
	public void serverCompressionEnabledSmallResponse() throws Exception {
		HttpServer server = HttpServer.create(o -> o.listen(0)
		                                            .compression(25));

		NettyContext nettyContext =
				server.newHandler((in, out) -> out.sendString(Mono.just("reply")))
				      .block(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.create(o -> o.compression(true)
		                                            .connect(address(nettyContext)));
		HttpClientResponse resp =
				client.get("/test", req -> req.header("Accept-Encoding", "gzip"))
				      .block();

		HttpHeaders headers = resp.responseHeaders();
		Assert.assertFalse(headers.contains("Content-Encoding", "gzip", true));
		String reply = resp.receive()
		                   .asString()
		                   .blockFirst();
		Assert.assertEquals("reply", reply);
		nettyContext.dispose();
		nettyContext.onClose()
		            .block();
	}

	@Test
	public void serverCompressionEnabledBigResponse() throws Exception {
		HttpServer server = HttpServer.create(o -> o.listen(0)
		                                            .compression(4));

		NettyContext nettyContext =
				server.newHandler((in, out) -> out.sendString(Mono.just("reply")))
				      .block(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.create(o -> o.compression(true)
		                                            .connect(address(nettyContext)));
		HttpClientResponse resp =
				client.get("/test", req -> req.header("Accept-Encoding", "gzip"))
				      .block();

		String reply = resp.receive()
		                   .asString()
		                   .blockFirst();
		Assert.assertEquals("reply", reply);
		nettyContext.dispose();
		nettyContext.onClose()
		            .block();
	}

	@Test
	public void compressionServerEnabledClientDisabled() throws Exception {
		HttpServer server = HttpServer.create(o -> o.listen(0)
		                                            .compression(true));

		String serverReply = "reply";
		NettyContext nettyContext =
				server.newHandler((in, out) -> out.sendString(Mono.just(serverReply)))
				      .block(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.create(o -> o.compression(false)
		                                            .connect(address(nettyContext)));
		HttpClientResponse resp =
				client.get("/test", req -> req.header("Accept-Encoding", "gzip"))
				      .block();
		String reply = resp.receive()
		                   .asString()
		                   .blockFirst();
		Assert.assertTrue(resp.responseHeaders()
		                      .contains("Content-Encoding", "gzip", true));
		Assert.assertNotEquals(serverReply, reply);
		nettyContext.dispose();
		nettyContext.onClose()
		            .block();
	}

	@Test
	public void compressionServerDefaultClientDefault() throws Exception {
		HttpServer server = HttpServer.create(o -> o.listen(0)
		                                            .compression(false));

		NettyContext nettyContext =
				server.newHandler((in, out) -> out.sendString(Mono.just("reply")))
				      .block(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.create(o -> o.connect(address(nettyContext)));

		HttpClientResponse resp =
				client.get("/test", req -> req.header("Accept-Encoding", "gzip"))
				      .block();

		String reply = resp.receive()
		                   .asString()
		                   .blockFirst();
		Assert.assertEquals("reply", reply);
		nettyContext.dispose();
		nettyContext.onClose()
		            .block();
	}

	private InetSocketAddress address(NettyContext nettyContext) {
		return new InetSocketAddress(nettyContext.address()
		                                         .getPort());
	}
}
