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

package reactor.netty.http.client;

import io.netty.handler.codec.http.HttpResponseStatus;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.lang3.StringUtils;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpRedirectTest {

	@Test
	@Ignore
	public void deadlockWhenRedirectsToSameUrl(){
		redirectTests("/login");
	}

	@Test
	@Ignore
	public void okWhenRedirectsToOther(){
		redirectTests("/other");
	}

	private void redirectTests(String url) {
		AtomicInteger counter = new AtomicInteger(1);
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> {
				              if (req.uri().contains("/login") &&
				                      req.method().equals(HttpMethod.POST) &&
				                      counter.getAndDecrement() > 0) {
				                  return res.sendRedirect(url);
				              }
				              else {
				                  return res.status(200)
				                            .send();
				              }
				          })
				          .wiretap(true)
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("redirectTests", 1);

		HttpClient client =
				HttpClient.create(pool)
				          .addressSupplier(server::address);

		try {
			Flux.range(0, 1000)
			    .concatMap(i -> client.followRedirect(true)
			                          .post()
			                          .uri("/login")
			                          .responseContent()
			                          .then())
			    .blockLast(Duration.ofSeconds(30));
		}
		finally {
			server.disposeNow();
		}

	}

	@Test
	public void redirectDisabledByDefault() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .host("localhost")
				          .wiretap(true)
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
				                       .get("/3", (req, res) -> res.status(200)
				                                                   .sendString(Mono.just("OK"))))
				          .wiretap(true)
				          .bindNow();

		HttpClientResponse response =
				HttpClient.create()
				          .addressSupplier(server::address)
				          .wiretap(true)
				          .get()
				          .uri("/1")
				          .response()
				          .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.status()).isEqualTo(HttpResponseStatus.FOUND);
		assertThat(response.responseHeaders().get("location")).isEqualTo("/3");

		server.disposeNow();
	}

	/** This ensures functionality such as metrics and tracing can accurately count requests. */
	@Test
	public void redirect_issuesOnRequestForEachAttempt() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .host("localhost")
				          .wiretap(true)
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
				                       .get("/3", (req, res) -> res.status(200)
				                                                   .sendString(Mono.just("OK"))))
				          .bindNow();

		AtomicInteger onRequestCount = new AtomicInteger();
		AtomicInteger onResponseCount = new AtomicInteger();
		AtomicInteger onRedirectCount = new AtomicInteger();
		Tuple2<String, HttpResponseStatus> response =
				HttpClient.create()
				          .addressSupplier(server::address)
				          .wiretap(true)
				          .followRedirect(true)
				          .doOnRequest((r, c) -> onRequestCount.incrementAndGet())
				          .doOnResponse((r, c) -> onResponseCount.incrementAndGet())
				          .doOnRedirect((r, c) -> onRedirectCount.incrementAndGet())
				          .get()
				          .uri("/1")
				          .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res.status())))
				          .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.getT1()).isEqualTo("OK");
		assertThat(response.getT2()).isEqualTo(HttpResponseStatus.OK);
		assertThat(onRequestCount.get()).isEqualTo(2);
		assertThat(onResponseCount.get()).isEqualTo(1);
		assertThat(onRedirectCount.get()).isEqualTo(1);

		server.disposeNow();
	}

	@Test
	public void testIssue253() {
		final int serverPort1 = SocketUtils.findAvailableTcpPort();

		DisposableServer server =
				HttpServer.create()
				          .port(serverPort1)
				          .host("localhost")
				          .wiretap(true)
				          .route(r -> r.get("/1",
				                                   (req, res) -> res.sendRedirect("http://localhost:" + serverPort1 + "/3"))
				                       .get("/2",
				                                   (req, res) -> res.status(301)
				                                                    .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort1 + "/3")
				                                                    .send())
				                       .get("/3",
				                                   (req, res) -> res.status(200)
				                                                    .sendString(Mono.just("OK"))))
				          .wiretap(true)
				          .bindNow();

		HttpClient client =
				HttpClient.create()
				          .addressSupplier(server::address)
				          .wiretap(true);

		String value =
				client.followRedirect(true)
				      .get()
				      .uri("/1")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block(Duration.ofSeconds(30));
		assertThat(value).isEqualTo("OK");

		value = client.get()
		              .uri("/1")
		              .responseContent()
		              .aggregate()
		              .asString()
		              .block(Duration.ofSeconds(30));
		assertThat(value).isNull();

		value = client.followRedirect(true)
		              .get()
		              .uri("/2")
		              .responseContent()
		              .aggregate()
		              .asString()
		              .block(Duration.ofSeconds(30));
		assertThat(value).isEqualTo("OK");

		value = client.get()
		              .uri("/2")
		              .responseContent()
		              .aggregate()
		              .asString()
		              .block(Duration.ofSeconds(30));
		assertThat(value).isNull();

		server.disposeNow();
	}

	@Test
	public void testIssue278() {
		final int serverPort1 = SocketUtils.findAvailableTcpPort();
		final int serverPort2 = SocketUtils.findAvailableTcpPort();

		DisposableServer server1 =
				HttpServer.create()
				          .host("localhost")
				          .port(serverPort1)
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
				                       .get("/2", (req, res) -> res.sendRedirect("http://localhost:" + serverPort1 + "/3"))
				                       .get("/3", (req, res) -> res.sendString(Mono.just("OK")))
				                       .get("/4", (req, res) -> res.sendRedirect("http://localhost:" + serverPort2 + "/1")))
				          .wiretap(true)
				          .bindNow();

		DisposableServer server2 =
				HttpServer.create()
				          .host("localhost")
				          .port(serverPort2)
				          .route(r -> r.get("/1", (req, res) -> res.sendString(Mono.just("Other"))))
				          .wiretap(true)
				          .bindNow();

		HttpClient client = HttpClient.create()
		                              .baseUrl("http://localhost:" + serverPort1);

		Mono<String> response =
				client.followRedirect(true)
				      .get()
				      .uri("/1")
				      .responseContent()
				      .aggregate()
				      .asString();

		StepVerifier.create(response)
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		response = client.followRedirect(true)
		                 .get()
		                 .uri("/2")
		                 .responseContent()
		                 .aggregate()
		                 .asString();

		StepVerifier.create(response)
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		response = client.followRedirect(true)
		                 .get()
		                 .uri("/4")
		                 .responseContent()
		                 .aggregate()
		                 .asString();

		StepVerifier.create(response)
		            .expectNextMatches("Other"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server1.disposeNow();
		server2.disposeNow();
	}

	@Test
	public void testIssue522() {
		final int serverPort = SocketUtils.findAvailableTcpPort();

		DisposableServer server =
				HttpServer.create()
				          .port(serverPort)
				          .host("localhost")
				          .route(r -> r.get("/301", (req, res) ->
				                          res.status(301)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/302", (req, res) ->
				                          res.status(302)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/304", (req, res) -> res.status(304))
				                       .get("/307", (req, res) ->
				                          res.status(307)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/308", (req, res) ->
				                          res.status(308)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/predicate", (req, res) ->
				                          res.header("test", "test")
				                             .status(302)
				                             .header(HttpHeaderNames.LOCATION, "http://localhost:" + serverPort + "/redirect"))
				                       .get("/redirect", (req, res) -> res.sendString(Mono.just("OK"))))
				          .wiretap(true)
				          .bindNow();

		HttpClient client =
				HttpClient.create()
				          .addressSupplier(server::address)
				          .wiretap(true);

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/301")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/302")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/307")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/308")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect((req, res) -> res.responseHeaders()
		                                                           .contains("test"))
		                          .get()
		                          .uri("/predicate")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.followRedirect(true)
		                          .get()
		                          .uri("/304")
		                          .responseSingle((res, bytes) -> Mono.just(res.status()
		                                                                       .code())))
		            .expectNextMatches(i -> i == 304)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	public void testIssue606() {
		final int serverPort = SocketUtils.findAvailableTcpPort();

		DisposableServer server =
				HttpServer.create()
				          .port(serverPort)
				          .host("localhost")
				          .handle((req, res) -> res.sendRedirect("http://localhost:" + serverPort))
				          .wiretap(true)
				          .bindNow();

		AtomicInteger followRedirects = new AtomicInteger(0);
		HttpClient.create()
		          .addressSupplier(server::address)
		          .wiretap(true)
		          .followRedirect((req, res) -> {
		              boolean result = req.redirectedFrom().length < 4;
		              if (result) {
		                  followRedirects.getAndIncrement();
		              }
		              return result;
		          })
		          .get()
		          .uri("/")
		          .responseContent()
		          .blockLast();

		server.disposeNow();

		assertThat(followRedirects.get()).isEqualTo(4);
	}

	@Test
	public void testFollowRedirectPredicateThrowsException() {
		final int serverPort = SocketUtils.findAvailableTcpPort();

		DisposableServer server =
				HttpServer.create()
				          .port(serverPort)
				          .host("localhost")
				          .handle((req, res) -> res.sendRedirect("http://localhost:" + serverPort))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
		        HttpClient.create()
		                  .addressSupplier(server::address)
		                  .wiretap(true)
		                  .followRedirect((req, res) -> {
		                      throw new RuntimeException("testFollowRedirectPredicateThrowsException");
		                  })
		                  .get()
		                  .uri("/")
		                  .responseContent())
		            .expectError()
		            .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	public void testIssue843() throws Exception {
		final int server2Port = SocketUtils.findAvailableTcpPort();

		SelfSignedCertificate cert1 = new SelfSignedCertificate();
		DisposableServer server1 =
				HttpServer.create()
				          .port(0)
				          .secure(spec -> spec.sslContext(SslContextBuilder.forServer(cert1.certificate(), cert1.privateKey())))
				          .handle((req, res) -> res.sendRedirect("https://localhost:" + server2Port))
				          .wiretap(true)
				          .bindNow();

		SelfSignedCertificate cert2 = new SelfSignedCertificate();
		DisposableServer server2 =
				HttpServer.create()
				          .port(server2Port)
				          .host("localhost")
				          .secure(spec -> spec.sslContext(SslContextBuilder.forServer(cert2.certificate(), cert2.privateKey())))
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .wiretap(true)
				          .bindNow();

		AtomicInteger peerPort = new AtomicInteger(0);
		HttpClient.create()
		          .addressSupplier(server1::address)
		          .wiretap(true)
		          .followRedirect(true)
		          .secure(spec -> spec.sslContext(SslContextBuilder.forClient()
		                                                           .trustManager(InsecureTrustManagerFactory.INSTANCE)))
		          .doOnRequest((req, conn) ->
		                  peerPort.set(conn.channel()
		                                   .pipeline()
		                                   .get(SslHandler.class)
		                                   .engine()
		                                   .getPeerPort()))
		          .get()
		          .uri("/")
		          .responseContent()
		          .blockLast(Duration.ofSeconds(30));

		assertThat(peerPort.get()).isEqualTo(server2Port);

		server1.disposeNow();
		server2.disposeNow();
	}

	@Test
	public void testRelativeRedirectKeepsScheme() {
		final String requestPath = "/request";
		final String redirectPath = "/redirect";
		final String responseContent = "Success";

		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .route(r ->
				                  r.get(requestPath, (req, res) -> res.sendRedirect(redirectPath))
				                   .get(redirectPath, (req, res) -> res.sendString(Mono.just(responseContent))))
				          .wiretap(true)
				          .bindNow();

		final Mono<String> responseMono =
				HttpClient.create()
				          .wiretap(true)
				          .followRedirect(true)
				          .secure(spec -> spec.sslContext(
				                  SslContextBuilder.forClient()
				                                   .trustManager(InsecureTrustManagerFactory.INSTANCE)))
				          .get()
				          .uri("http://localhost:" + server.port() + requestPath)
				          .responseContent()
				          .aggregate()
				          .asString();

		StepVerifier.create(responseMono)
		            .expectNext(responseContent)
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));

		server.disposeNow();
	}

	@Test
	public void testLastLocationSetToResourceUrlOnRedirect() throws CertificateException {
		final String redirectPath = "/redirect";
		final String destinationPath = "/destination";
		final String responseContent = "Success";

		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverSslCtxBuilder =
				SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		DisposableServer redirectServer =
				HttpServer.create()
				          .port(0)
				          .route(r ->
				                  r.get(redirectPath, (req, res) -> res.sendRedirect(destinationPath))
				                   .get(destinationPath, (req, res) -> res.sendString(Mono.just(responseContent)))
				          )
				          .secure(spec -> spec.sslContext(serverSslCtxBuilder))
				          .wiretap(true)
				          .bindNow();

		DisposableServer initialServer =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) ->
				              res.sendRedirect("https://localhost:" + redirectServer.port() + destinationPath)
				          )
				          .wiretap(true)
				          .bindNow();

		SslContextBuilder clientSslCtxBuilder =
				SslContextBuilder.forClient()
				                 .trustManager(InsecureTrustManagerFactory.INSTANCE);
		final String requestUri = "http://localhost:" + initialServer.port();
		StepVerifier.create(
		        HttpClient.create()
		                  .wiretap(true)
		                  .followRedirect(true)
		                  .secure(spec -> spec.sslContext(clientSslCtxBuilder))
		                  .get()
		                  .uri(requestUri)
		                  .responseConnection((res, conn) -> Mono.justOrEmpty(res.resourceUrl())))
		            .expectNext("https://localhost:" + redirectServer.port() + destinationPath)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		initialServer.disposeNow();
		redirectServer.disposeNow();
	}

	@Test
	public void testBuffersForRedirectWithContentShouldBeReleased() {
		doTestBuffersForRedirectWithContentShouldBeReleased("Redirect response content!");
	}

	@Test
	public void testBuffersForRedirectWithLargeContentShouldBeReleased() {
		doTestBuffersForRedirectWithContentShouldBeReleased(StringUtils.repeat("a", 10000));
	}

	private void doTestBuffersForRedirectWithContentShouldBeReleased(String redirectResponseContent) {
		final String initialPath = "/initial";
		final String redirectPath = "/redirect";

		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .route(r -> r.get(initialPath,
				                            (req, res) -> res.status(HttpResponseStatus.MOVED_PERMANENTLY)
				                                             .header(HttpHeaderNames.LOCATION, redirectPath)
				                                             .sendString(Mono.just(redirectResponseContent)))
				                       .get(redirectPath, (req, res) -> res.send()))
				          .wiretap(true)
				          .bindNow();

		final List<Integer> redirectBufferRefCounts = new ArrayList<>();
		HttpClient.create()
		          .doOnRequest((r, c) -> c.addHandler("test-buffer-released", new ChannelInboundHandlerAdapter() {

		              @Override
		              public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		                  super.channelRead(ctx, msg);

		                  if(initialPath.equals("/" + r.path()) && msg instanceof HttpContent) {
		                      redirectBufferRefCounts.add(ReferenceCountUtil.refCnt(msg));
		                  }
		              }
		          }))
		          .wiretap(true)
		          .followRedirect(true)
		          .get()
		          .uri("http://localhost:" + server.port() + initialPath)
		          .response()
		          .block(Duration.ofSeconds(30));

		System.gc();

		assertThat(redirectBufferRefCounts).as("The HttpContents belonging to the redirection response should all be released")
		                                   .containsOnly(0);

		server.disposeNow();
	}
}
