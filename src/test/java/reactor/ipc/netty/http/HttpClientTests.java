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

import java.io.InputStream;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.channel.AbortedException;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientException;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.TcpServer;

/**
 * @author Stephane Maldini
 * @since 0.6
 */
public class HttpClientTests {

	@Test
	public void abort() throws Exception {
		NettyContext x = TcpServer.create("localhost", 0)
		                          .newHandler((in, out) -> in.receive()
		                                                     .take(1)
		                                                     .doOnNext(d -> out.context()
		                                                                       .addHandler(
				                                                                       new HttpResponseEncoder()))
		                                                     .then(() -> out.sendObject(
				                                                     new DefaultFullHttpResponse(
						                                                     HttpVersion.HTTP_1_1,
						                                                     HttpResponseStatus.ACCEPTED))
		                                                                    .then(Mono.delayMillis(
				                                                                    2000)
		                                                                              .then())))
		                          .block();

		PoolResources pool = PoolResources.fixed("test", 1);

		int res = HttpClient.create(opts -> opts.connect("localhost",
				x.address()
				 .getPort())
		                                        .poolResources(pool))
		                    .get("/")
		                    .then(r -> Mono.just(r.status()
		                                          .code()))
		                    .log()
		                    .block();

		try {
			HttpClient.create(opts -> opts.connect("localhost",
					x.address()
					 .getPort())
			                              .poolResources(pool))
			          .get("/")
			          .log()
			          .block();
		}
		catch (AbortedException ae) {
			return;
		}

		Assert.fail("Not aborted");
	}

	//@Test
	public void simpleTest() throws Exception {
		int res = HttpClient.create()
		                    .get("http://next.projectreactor.io/assets/css/reactor.css",
				                    c -> c.followRedirect()
				                          .sendHeaders())
		                    .then(r -> Mono.just(r.status()
		                                          .code()))
		                    .log()
		                    .block();
		res = HttpClient.create()
		                .get("http://next.projectreactor.io/assets/css/reactor.css",
				                c -> c.followRedirect()
				                      .sendHeaders())
		                .then(r -> Mono.just(r.status()
		                                      .code()))
		                .log()
		                .block();

		if (res != 200) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	//@Test
	public void postUpload() throws Exception {
		InputStream f = getClass().getResourceAsStream("/public/index.html");
		//Path f = Paths.get("/Users/smaldini/Downloads/IMG_6702.mp4");
		int res = HttpClient.create("google.com")
		                    .put("/post",
				                    c -> c.sendForm(form -> form.multipart(true)
				                                                .file("test", f)
				                                                .attr("att1",
						                                                     "attr2")
				                                                .file("test2", f))
				                          .log()
				                          .then())
		                    .then(r -> Mono.just(r.status()
		                                          .code()))
		                    .block();
		res = HttpClient.create("google.com")
		                .get("/search",
				                c -> c.followRedirect()
				                      .sendHeaders())
		                .then(r -> Mono.just(r.status()
		                                      .code()))
		                .log()
		                .block();

		if (res != 200) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	public void simpleTest404() {
		int res = HttpClient.create("google.com")
		                    .get("/unsupportedURI",
				                    c -> c.followRedirect()
				                          .sendHeaders())
		                    .then(r -> Mono.just(r.status()
		                                          .code()))
		                    .log()
		                    .otherwise(HttpClientException.class,
				                    e -> Mono.just(e.status()
				                                    .code()))
		                    .block();

		if (res != 404) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	public void disableChunkForced() throws Exception {
		HttpClientResponse r = HttpClient.create("google.com")
		                                 .get("/unsupportedURI",
				                                 c -> c.chunkedTransfer(false)
				                                       .failOnClientError(false)
				                                       .sendHeaders()
				                                       .sendString(Flux.just("hello")))
		                                 .block();

		FutureMono.from(r.context()
		                 .channel()
		                 .closeFuture())
		          .blockMillis(5000);

		Assert.assertTrue(r.status() == HttpResponseStatus.NOT_FOUND);
	}

	@Test
	public void disableChunkImplicit() throws Exception {
		HttpClientResponse r = HttpClient.create("google.com")
		                                 .get("/unsupportedURI",
				                                 c -> c.failOnClientError(false)
				                                       .sendHeaders())
		                                 .block();

		FutureMono.from(r.context()
		                 .channel()
		                 .closeFuture())
		          .blockMillis(5000);

		Assert.assertTrue(!r.context()
		                    .channel()
		                    .isOpen());
		Assert.assertTrue(r.status() == HttpResponseStatus.NOT_FOUND);
	}

	@Test
	public void disableChunkImplicitZeroLength() throws Exception {
		PoolResources p = PoolResources.fixed("test", 1);

		HttpClientResponse r = HttpClient.create(opts -> opts.poolResources(p))
		                                 .get("http://google.com/unsupportedURI",
				                                 c -> c.failOnClientError(false))
		                                 .block();

		HttpClientResponse r2 = HttpClient.create(opts -> opts.poolResources(p))
		                                  .get("http://google.com/unsupportedURI",
				                                  c -> c.failOnClientError(false))
		                                  .block();

		Assert.assertTrue(r.context()
		                   .channel() == r2.context()
		                                   .channel());
		Assert.assertTrue(r.status() == HttpResponseStatus.NOT_FOUND);
	}

}
