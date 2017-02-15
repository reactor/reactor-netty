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

package reactor.ipc.netty.http;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.channel.AbortedException;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientException;
import reactor.ipc.netty.http.client.HttpClientResponse;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.test.StepVerifier;

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
		                                                     .then(() -> out.context(c
				                                                     -> c.addEncoder(
				                                                     new HttpResponseEncoder()))
		                                                                    .sendObject(
				                                                     new DefaultFullHttpResponse(
						                                                     HttpVersion.HTTP_1_1,
						                                                     HttpResponseStatus.ACCEPTED))
		                                                                    .then(Mono.delayMillis(
				                                                                    2000)
		                                                                              .then())))
		                          .block(Duration.ofSeconds(30));

		PoolResources pool = PoolResources.fixed("test", 1);

		int res = HttpClient.create(opts -> opts.connect("localhost",
				x.address()
				 .getPort())
		                                        .poolResources(pool))
		                    .get("/")
		                    .then(r -> Mono.just(r.status()
		                                          .code()))
		                    .log()
		                    .block(Duration.ofSeconds(30));

		try {
			HttpClient.create(opts -> opts.connect("localhost",
					x.address()
					 .getPort())
			                              .poolResources(pool))
			          .get("/")
			          .log()
			          .block(Duration.ofSeconds(30));
		}
		catch (AbortedException ae) {
			Assert.fail("Not aborted");
		}

	}

	DefaultFullHttpResponse response() {
		DefaultFullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				HttpResponseStatus.ACCEPTED);
		r.headers()
		 .set(HttpHeaderNames.CONTENT_LENGTH, 0);
		return r;
	}

	@Test
	@Ignore
	public void pipelined() throws Exception {
		NettyContext x = TcpServer.create("localhost", 0)
		                          .newHandler((in, out) -> out.context(c -> c.addEncoder(new
				                          HttpResponseEncoder()))
		                                                      .sendObject(Flux.just(
				                                                      response(),
				                                                      response()))
		                                                      .neverComplete())
		                          .block(Duration.ofSeconds(30));

		PoolResources pool = PoolResources.fixed("test", 1);

		int res = HttpClient.create(opts -> opts.connect("localhost",
				x.address()
				 .getPort())
		                                        .poolResources(pool))
		                    .get("/")
		                    .then(r -> Mono.just(r.status()
		                                          .code()))
		                    .log()
		                    .block(Duration.ofSeconds(30));

		try {
			HttpClient.create(opts -> opts.connect("localhost",
					x.address()
					 .getPort())
			                              .poolResources(pool))
			          .get("/")
			          .log()
			          .block(Duration.ofSeconds(30));
		}
		catch (AbortedException ae) {
			return;
		}

		Assert.fail("Not aborted");
	}

	@Test
	public void backpressured() throws Exception {
		Mono<HttpClientResponse> remote = HttpClient.create()
		          .get("http://next.projectreactor.io" +
						          "/docs/core/release/api/reactor/core" +
						          "/publisher/Mono.html",
				          c -> c.followRedirect()
				                .sendHeaders());

		Mono<String> page = remote
				.flatMap(r -> r.receive()
				               .asString()
				               .limitRate(1))
				.reduce(String::concat);

		Mono<String> cancelledPage = remote
				.flatMap(r -> r.receive()
				               .asString()
				               .take(5)
				               .limitRate(1))
				.reduce(String::concat);

		page.block(Duration.ofSeconds(30));
		cancelledPage.block(Duration.ofSeconds(30));
		page.block(Duration.ofSeconds(30));
	}

	@Test
	@Ignore
	public void proxy() throws Exception {
		Mono<HttpClientResponse> remote = HttpClient.create(o -> o.proxy("127.0.0.1", 8888))
		          .get("https://projectreactor.io",
				          c -> c.followRedirect()
				                .sendHeaders());

		Mono<String> page = remote
				.flatMap(r -> r.receive()
				               .retain()
				               .asString()
				               .limitRate(1))
				.reduce(String::concat);

		page.block(Duration.ofSeconds(30));
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
		                    .block(Duration.ofSeconds(30));
		res = HttpClient.create("google.com")
		                .get("/search",
				                c -> c.followRedirect()
				                      .sendHeaders())
		                .then(r -> Mono.just(r.status()
		                                      .code()))
		                .log()
		                .block(Duration.ofSeconds(30));

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
		                    .block(Duration.ofSeconds(30));

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
				                                       .sendString(Flux.just("hello")))
		                                 .block(Duration.ofSeconds(30));

		FutureMono.from(r.context()
		                 .channel()
		                 .closeFuture())
		          .blockMillis(5000);

		Assert.assertTrue(r.status() == HttpResponseStatus.NOT_FOUND);
	}

	@Test
	public void disableChunkForced2() throws Exception {
		HttpClientResponse r = HttpClient.create("google.com")
		                                 .get("/unsupportedURI",
				                                 c -> c.chunkedTransfer(false)
				                                       .failOnClientError(false)
				                                       .keepAlive(false))
		                                 .block(Duration.ofSeconds(30));

		FutureMono.from(r.context()
		                 .channel()
		                 .closeFuture())
		          .blockMillis(5000);

		Assert.assertTrue(r.status() == HttpResponseStatus.NOT_FOUND);
	}

	@Test
	public void disableChunkImplicit() throws Exception {
		PoolResources p = PoolResources.fixed("test", 1);

		HttpClientResponse r = HttpClient.create(opts -> opts.poolResources(p))
		                                 .get("http://google.com/unsupportedURI",
				                                 c -> c.failOnClientError(false)
				                                       .sendHeaders())
		                                 .block(Duration.ofSeconds(30));

		HttpClientResponse r2 = HttpClient.create(opts -> opts.poolResources(p))
		                                  .get("http://google.com/unsupportedURI",
				                                  c -> c.failOnClientError(false)
				                                        .sendHeaders())
		                                  .block(Duration.ofSeconds(30));
		Assert.assertTrue(r.context()
		                   .channel() == r2.context()
		                                   .channel());

		Assert.assertTrue(r.status() == HttpResponseStatus.NOT_FOUND);
	}

	@Test
	public void disableChunkImplicitDefault() throws Exception {
		HttpClientResponse r = HttpClient.create("google.com")
		                                 .get("/unsupportedURI",
				                                 c -> c.chunkedTransfer(false)
				                                       .failOnClientError(false))
		                                 .block(Duration.ofSeconds(30));

		FutureMono.from(r.context()
		                 .channel()
		                 .closeFuture())
		          .blockMillis(5000);

		Assert.assertTrue(r.status() == HttpResponseStatus.NOT_FOUND);
	}

	@Test
	public void contentHeader() throws Exception {
		PoolResources fixed = PoolResources.fixed("test", 1);
		HttpClientResponse r = HttpClient.create(opts -> opts.poolResources(fixed))
		                                 .get("http://google.com",
				                                 c -> c.header("content-length", "1")
				                                       .failOnClientError(false)
				                                       .sendString(Mono.just(" ")))
		                                 .block(Duration.ofSeconds(30));

		HttpClient.create(opts -> opts.poolResources(fixed))
		          .get("http://google.com",
				          c -> c.header("content-length", "1")
				                .failOnClientError(false)
				                .sendString(Mono.just(" ")))
		          .block(Duration.ofSeconds(30));

		Assert.assertTrue(r.status() == HttpResponseStatus.BAD_REQUEST);
	}

	@Test
	public void simpleTestHttps() {

		StepVerifier.create(HttpClient.create()
		                              .get("https://developer.chrome.com")
		                              .then(r -> Mono.just(r.status().code()))
		)
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify();
	}

	@Test
	public void prematureCancel() throws Exception {
		DirectProcessor<Void> signal = DirectProcessor.create();
		NettyContext x = TcpServer.create("localhost", 0)
		                          .newHandler((in, out) -> {
										signal.onComplete();
										return out.context(c -> c.addEncoder(
												new HttpResponseEncoder()))
										          .sendObject(Mono.delayMillis(2000)
												          .map(t ->
												          new DefaultFullHttpResponse(
														          HttpVersion.HTTP_1_1,
														          HttpResponseStatus
																          .PROCESSING)))
												.neverComplete();
		                          })
		                          .block(Duration.ofSeconds(30));

		StepVerifier.create(HttpClient.create(x.address().getHostName(), x.address().getPort())
		                              .get("/")
		                              .timeout(signal)
		)
		            .verifyError(TimeoutException.class);
	}

}
