/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import java.io.IOException;
import java.net.SocketAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.function.BiFunction;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author tjreactive
 * @author smaldini
 */
@Ignore
public class PostAndGetTests {

	static final Logger log = Loggers.getLogger(PostAndGetTests.class);

	private DisposableServer httpServer;

	@Before
	public void setup() throws InterruptedException {
		setupServer();
	}

	private void setupServer() {
		httpServer = HttpServer.create()
		                       .port(0)
		                       .route(routes -> {
			                       routes.get("/get/{name}", getHandler())
			                             .post("/post", postHandler());
		                       })
		                       .wiretap(true)
		                       .bindNow();
	}

	BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> getHandler() {
		return (req, resp) -> {
			req.requestHeaders()
			   .entries()
			   .forEach(entry1 -> System.out.println(String.format("header [%s=>%s]",
					   entry1.getKey(),
					   entry1.getValue())));
			req.params()
			   .entrySet()
			   .forEach(entry2 -> System.out.println(String.format("params [%s=>%s]",
					   entry2.getKey(),
					   entry2.getValue())));

			StringBuilder response = new StringBuilder().append("hello ")
			                                            .append(req.params()
			                                                       .get("name"));
			System.out.println(String.format("%s from thread %s",
					response.toString(),
					Thread.currentThread()));
			return resp.sendString(Flux.just(response.toString()));
		};
	}

	BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> postHandler() {
		return (req, resp) -> {

			req.requestHeaders()
			   .entries()
			   .forEach(entry -> System.out.println(String.format("header [%s=>%s]",
					   entry.getKey(),
					   entry.getValue())));

			return resp.sendString(req.receive()
			                          .take(1)
			                          .log("received")
			                          .flatMap(data -> {
				                          final StringBuilder response =
						                          new StringBuilder().append("hello ")
						                                             .append(data.readCharSequence(
						                                                     data.readableBytes(),
								                                             Charset.defaultCharset()));
				                          System.out.println(String.format(
						                          "%s from thread %s",
						                          response.toString(),
						                          Thread.currentThread()));
				                          return Flux.just(response.toString());
			                          }));
		};
	}

	@After
	public void teardown() {
		httpServer.disposeNow();
	}

	@Test
	public void tryBoth() throws IOException {
		get("/get/joe", httpServer.address());
		post("/post", URLEncoder.encode("pete", "UTF8"), httpServer.address());
	}

	private void get(String path, SocketAddress address) {
		try {
			StringBuilder request =
					new StringBuilder().append(String.format("GET %s HTTP/1.1", path))
					                   .append("\r\n")
					                   .append("Connection: Keep-Alive\r\n")
					                   .append("\r\n");
			java.nio.channels.SocketChannel channel =
					java.nio.channels.SocketChannel.open(address);
			System.out.println(String.format("get: request >> [%s]", request.toString()));
			channel.write(ByteBuffer.wrap(request.toString()
			                                     .getBytes(Charset.defaultCharset())));
			ByteBuffer buf = ByteBuffer.allocate(4 * 1024);
			while (channel.read(buf) > -1) {
			}
			String response = new String(buf.array(), Charset.defaultCharset());
			System.out.println(String.format("get: << Response: %s", response));
			channel.close();
		}
		catch (IOException e) {
			log.error("", e);
		}
	}

	private void post(String path, String data, SocketAddress address) {
		try {
			StringBuilder request = new StringBuilder().append(String.format(
					"POST %s HTTP/1.1",
					path))
			                                           .append("\r\n")
			                                           .append("Connection: Keep-Alive\r\n");
			request.append(String.format("Content-Length: %s", data.length()))
			       .append("\r\n\r\n")
			       .append(data)
			       .append("\r\n");
			java.nio.channels.SocketChannel channel =
					java.nio.channels.SocketChannel.open(address);
			System.out.println(String.format("post: request >> [%s]",
					request.toString()));
			channel.write(ByteBuffer.wrap(request.toString()
			                                     .getBytes(Charset.defaultCharset())));
			ByteBuffer buf = ByteBuffer.allocate(4 * 1024);
			while (channel.read(buf) > -1) {
			}
			String response = new String(buf.array(), Charset.defaultCharset());
			log.info("post: << " + "Response: %s", response);
			channel.close();
		}
		catch (IOException e) {
			log.error("", e);
		}
	}
}
