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

import java.io.InputStream;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.channel.AbortedException;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 * @since 0.6
 */
public class HttpClientTest {

	@Test
	public void abort() throws Exception {
		NettyContext x = TcpServer.create("localhost", 0)
		                          .newHandler((in, out) -> in.receive()
		                                                     .take(1)
		                                                     .thenMany(Flux.defer(() ->
						                                                     out.context(c ->
								                                                     c.addHandlerFirst(new HttpResponseEncoder()))
						                                                        .sendObject(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.ACCEPTED))
						                                                        .then(Mono.delay(Duration.ofSeconds(2)).then()))
		                                                     )
		                          )
		                          .block(Duration.ofSeconds(30));

		PoolResources pool = PoolResources.fixed("test", 1);

		int res = HttpClient.create(opts -> opts.connect("localhost",
				x.address()
				 .getPort())
		                                        .poolResources(pool))
		                    .get("/")
		                    .flatMap(r -> Mono.just(r.status()
		                                          .code()))
		                    .log()
		                    .block(Duration.ofSeconds(30));

		HttpClient.create(opts -> opts.connect("localhost",
					x.address()
					 .getPort())
		                              .poolResources(pool))
		          .get("/")
		          .log()
		          .block(Duration.ofSeconds(30));

		HttpClient.create(opts -> opts.connect("localhost",
				x.address()
				 .getPort())
		                              .poolResources(pool))
		          .get("/")
		          .log()
		          .block(Duration.ofSeconds(30));

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
		                          .newHandler((in, out) -> out.context(c -> c.addHandlerFirst(new
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
		                    .flatMap(r -> Mono.just(r.status()
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

		NettyContext c = HttpServer.create(0)
		                           .newRouter(routes -> routes.directory("/test",
				                           Paths.get(getClass().getResource("/public")
				                                               .getFile())))
		                           .block(Duration.ofSeconds(30));

		Mono<HttpClientResponse> remote = HttpClient.create(opts -> opts.connect(c
				.address().getPort()))
		                                            .get("/test/test.css");

		Mono<String> page = remote
				.flatMapMany(r -> r.receive()
				               .asString()
				               .limitRate(1))
				.reduce(String::concat);

		Mono<String> cancelledPage = remote
				.flatMapMany(r -> r.receive()
				               .asString()
				               .take(5)
				               .limitRate(1))
				.reduce(String::concat);

		page.block(Duration.ofSeconds(30));
		cancelledPage.block(Duration.ofSeconds(30));
		page.block(Duration.ofSeconds(30));
		c.dispose();
	}

	@Test
	public void serverInfiniteClientClose() throws Exception {

		CountDownLatch latch = new CountDownLatch(1);
		NettyContext c = HttpServer.create(0)
		                           .newHandler((req, resp) -> {
			                           req.context()
			                              .onClose(latch::countDown);

			                           return Flux.interval(Duration.ofSeconds(1))
			                                      .flatMap(d -> {
				                                      req.context()
				                                         .channel()
				                                         .config()
				                                         .setAutoRead(true);

				                                      return resp.sendObject(Unpooled.EMPTY_BUFFER)
				                                                 .then()
				                                                 .doOnSuccess(x -> req.context()
				                                                                      .channel()
				                                                                      .config()
				                                                                      .setAutoRead(
						                                                                      false));
			                                      });
		                           })
		                           .block(Duration.ofSeconds(30));

		Mono<HttpClientResponse> remote = HttpClient.create(opts -> opts.connect(c.address().getPort()))
		                                            .get("/");

		HttpClientResponse r = remote.block();
		r.dispose();
		while (r.channel()
		        .isActive()) {
		}
		latch.await();
		c.dispose();
	}

	@Test
	@Ignore
	public void proxy() throws Exception {
		Mono<HttpClientResponse> remote = HttpClient.create(o -> o.proxy("127.0.0.1", 8888))
		          .get("https://projectreactor.io",
				          c -> c.followRedirect()
				                .sendHeaders());

		Mono<String> page = remote
				.flatMapMany(r -> r.receive()
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
		                    .flatMap(r -> Mono.just(r.status()
		                                          .code()))
		                    .block(Duration.ofSeconds(30));
		res = HttpClient.create("google.com")
		                .get("/search",
				                c -> c.followRedirect()
				                      .sendHeaders())
		                .flatMap(r -> Mono.just(r.status()
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
		                    .flatMap(r -> Mono.just(r.status()
		                                          .code()))
		                    .log()
		                    .onErrorResume(HttpClientException.class,
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
		          .block(Duration.ofSeconds(5));

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
		          .block(Duration.ofSeconds(5));

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
		          .block(Duration.ofSeconds(5));

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
		                              .flatMap(r -> Mono.just(r.status().code()))
		)
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify();

		StepVerifier.create(HttpClient.create()
		                              .get("https://developer.chrome.com")
		                              .flatMap(r -> Mono.just(r.status().code()))
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
										return out.context(c -> c.addHandlerFirst(
												new HttpResponseEncoder()))
										          .sendObject(Mono.delay(Duration
												          .ofSeconds(2))
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
//		Thread.sleep(1000000);
	}

	@Test
	public void gzip() {
		//verify gzip is negotiated (when no decoder)
		StepVerifier.create(
				HttpClient.create()
				          .get("http://www.httpwatch.com", req -> req
						          .addHeader("Accept-Encoding", "gzip")
						          .addHeader("Accept-Encoding", "deflate")
				          )
				          .flatMap(r -> r.receive().asString().elementAt(0).map(s -> s.substring(0, 100))
				                      .and(Mono.just(r.responseHeaders().get("Content-Encoding", ""))))
		)
		            .expectNextMatches(tuple -> !tuple.getT1().contains("<html>") && !tuple.getT1().contains("<head>")
				            && "gzip".equals(tuple.getT2()))
		            .expectComplete()
		            .verify();

		//verify decoder does its job and removes the header
		StepVerifier.create(
				HttpClient.create()
				          .get("http://www.httpwatch.com", req -> {
					          req.context().addHandlerFirst("gzipDecompressor", new HttpContentDecompressor());
					          return req.addHeader("Accept-Encoding", "gzip")
					                    .addHeader("Accept-Encoding", "deflate");
				          })
				          .flatMap(r -> r.receive().asString().elementAt(0).map(s -> s.substring(0, 100))
				                      .and(Mono.just(r.responseHeaders().get("Content-Encoding", ""))))
		)
		            .expectNextMatches(tuple -> tuple.getT1().contains("<html>") && tuple.getT1().contains("<head>")
				            && "".equals(tuple.getT2()))
		            .expectComplete()
		            .verify();
	}

	@Test
	public void testUserAgent() {
		NettyContext c = HttpServer.create(0)
		                           .newHandler((req, resp) -> {
			                           Assert.assertTrue(""+req.requestHeaders()
			                                                   .get(HttpHeaderNames.USER_AGENT),
					                           req.requestHeaders()
			                                               .contains(HttpHeaderNames.USER_AGENT) && req.requestHeaders()
			                                                                                           .get(HttpHeaderNames.USER_AGENT)
			                                                                                           .equals(HttpClient.USER_AGENT));

			                           return resp;
		                           })
		                           .block();

		HttpClient.create(c.address().getPort())
		          .get("/")
		          .block();

		c.dispose();
	}

	@Test
	public void toStringShowsOptions() {
		HttpClient client = HttpClient.create(opt -> opt.connect("foo", 123)
		                                                .compression(true));

		assertThat(client.toString()).isEqualTo("HttpClient: connecting to foo:123 with gzip");
	}

	@Test
	public void gettingOptionsDuplicates() {
		HttpClient client = HttpClient.create(opt -> opt.connect("foo", 123).compression(true));
		assertThat(client.options())
				.isNotSameAs(client.options)
				.isNotSameAs(client.options());
	}

	@Test
	public void sshExchangeRelativeGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        //make the client to trust the self signed certificate
		                                        .trustManager(ssc.cert())
		                                        .build();

		NettyContext context =
				HttpServer.create(opt -> opt.sslContext(sslServer))
				          .newHandler((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .block();


		HttpClientResponse response = HttpClient.create(
				opt -> opt.connect(context.address().getPort())
				          .sslContext(sslClient))
		                                        .get("/foo")
		                                        .block(Duration.ofMillis(200));
		context.dispose();
		context.onClose().block();

		String responseString = response.receive().aggregate().asString(CharsetUtil.UTF_8).block();
		assertThat(responseString).isEqualTo("hello /foo");
	}

	@Test
	public void sshExchangeAbsoluteGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(ssc.cert()).build();

		NettyContext context =
				HttpServer.create(opt -> opt.sslContext(sslServer))
				          .newHandler((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .block();

		HttpClientResponse response = HttpClient.create(
				opt -> opt.connect(context.address().getPort())
				          .sslContext(sslClient)
		)
		                                        .get("https://localhost:" + context.address().getPort() + "/foo")
		                                        .block(Duration.ofMillis(200));
		context.dispose();
		context.onClose().block();

		String responseString = response.receive().aggregate().asString(CharsetUtil.UTF_8).block();
		assertThat(responseString).isEqualTo("hello /foo");
	}
}
