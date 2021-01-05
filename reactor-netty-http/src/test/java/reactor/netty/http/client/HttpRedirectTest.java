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

import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class HttpRedirectTest extends BaseHttpTest {

	@Test
	void deadlockWhenRedirectsToSameUrl() {
		redirectTests("/login");
	}

	@Test
	void okWhenRedirectsToOther() {
		redirectTests("/other");
	}

	private void redirectTests(String url) {
		AtomicInteger counter = new AtomicInteger(1);
		disposableServer =
				createServer()
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
				          .bindNow();

		ConnectionProvider pool = ConnectionProvider.create("redirectTests", 1);

		HttpClient client = createClient(pool, disposableServer::address);

		Flux.range(0, 100)
		    .concatMap(i -> client.followRedirect(true)
		                          .post()
		                          .uri("/login")
		                          .responseContent()
		                          .then())
		    .blockLast(Duration.ofSeconds(30));
	}

	@Test
	void redirectDisabledByDefault() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
				                       .get("/3", (req, res) -> res.status(200)
				                                                   .sendString(Mono.just("OK"))))
				          .wiretap(true)
				          .bindNow();

		HttpClientResponse response =
				createClient(disposableServer::address)
				          .get()
				          .uri("/1")
				          .response()
				          .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.status()).isEqualTo(HttpResponseStatus.FOUND);
		assertThat(response.responseHeaders().get("location")).isEqualTo("/3");
	}

	/** This ensures functionality such as metrics and tracing can accurately count requests. */
	@Test
	void redirect_issuesOnRequestForEachAttempt() {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
				                       .get("/3", (req, res) -> res.status(200)
				                                                   .sendString(Mono.just("OK"))))
				          .bindNow();

		AtomicInteger onRequestCount = new AtomicInteger();
		AtomicInteger onResponseCount = new AtomicInteger();
		AtomicInteger onRedirectCount = new AtomicInteger();
		AtomicInteger doOnResponseError = new AtomicInteger();
		Tuple2<String, HttpResponseStatus> response =
				createClient(disposableServer::address)
				          .followRedirect(true)
				          .doOnRequest((r, c) -> onRequestCount.incrementAndGet())
				          .doOnResponse((r, c) -> onResponseCount.incrementAndGet())
				          .doOnRedirect((r, c) -> onRedirectCount.incrementAndGet())
				          .doOnResponseError((r, t) -> doOnResponseError.incrementAndGet())
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
		assertThat(doOnResponseError.get()).isEqualTo(0);
	}


	/**
	 * This ensures metrics and tracing get reliable timing. {@link HttpClient#doOnResponse} should
	 * return when headers are received. Blocking client usage should therefore finish after that
	 * timestamp.
	 */
	@Test
	void redirect_onResponseBeforeBlockCompletes() throws Exception {
		disposableServer =
				createServer()
				          .host("localhost")
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
				                       .get("/3", (req, res) -> res.status(200)
				                                                   .sendString(Mono.just("OK"))))
				          .bindNow();

		BlockingQueue<Long> doOnResponseNanos = new LinkedBlockingQueue<>();
		Long responseNanos =
				createClient(disposableServer::address)
				          .followRedirect(true)
				          .doOnResponse((r, c) -> doOnResponseNanos.add(System.nanoTime()))
				          .get()
				          .uri("/1")
				          .response().map(r -> System.nanoTime())
				          .block(Duration.ofSeconds(30));

		assertThat(responseNanos).isGreaterThan(doOnResponseNanos.poll(5, TimeUnit.SECONDS));
	}

	@Test
	void testIssue253() {
		final int serverPort1 = SocketUtils.findAvailableTcpPort();

		disposableServer =
				createServer(serverPort1)
				          .host("localhost")
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

		HttpClient client = createClient(disposableServer::address);

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
	}

	@Test
	void testIssue278() {
		final int serverPort1 = SocketUtils.findAvailableTcpPort();
		final int serverPort2 = SocketUtils.findAvailableTcpPort();

		DisposableServer server1 = null;
		DisposableServer server2 = null;
		try {
			server1 =
					createServer(serverPort1)
					          .host("localhost")
					          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
					                       .get("/2", (req, res) -> res.sendRedirect("http://localhost:" + serverPort1 + "/3"))
					                       .get("/3", (req, res) -> res.sendString(Mono.just("OK")))
					                       .get("/4", (req, res) -> res.sendRedirect("http://localhost:" + serverPort2 + "/1")))
					          .bindNow();

			server2 =
					createServer(serverPort2)
					          .host("localhost")
					          .route(r -> r.get("/1", (req, res) -> res.sendString(Mono.just("Other"))))
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
		}
		finally {
			if (server1 != null) {
				server1.disposeNow();
			}
			if (server2 != null) {
				server2.disposeNow();
			}
		}
	}

	@Test
	void testIssue522() {
		final int serverPort = SocketUtils.findAvailableTcpPort();

		disposableServer =
				createServer(serverPort)
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
				          .bindNow();

		HttpClient client = createClient(disposableServer::address);

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
	}

	@Test
	void testIssue606() {
		final int serverPort = SocketUtils.findAvailableTcpPort();

		disposableServer =
				createServer(serverPort)
				          .host("localhost")
				          .handle((req, res) -> res.sendRedirect("http://localhost:" + serverPort))
				          .bindNow();

		AtomicInteger followRedirects = new AtomicInteger(0);
		createClient(disposableServer::address)
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

		assertThat(followRedirects.get()).isEqualTo(4);
	}

	@Test
	void testFollowRedirectPredicateThrowsException() {
		final int serverPort = SocketUtils.findAvailableTcpPort();

		disposableServer =
				createServer(serverPort)
				          .host("localhost")
				          .handle((req, res) -> res.sendRedirect("http://localhost:" + serverPort))
				          .bindNow();

		StepVerifier.create(
		        createClient(disposableServer::address)
		                  .followRedirect((req, res) -> {
		                      throw new RuntimeException("testFollowRedirectPredicateThrowsException");
		                  })
		                  .get()
		                  .uri("/")
		                  .responseContent())
		            .expectError()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue843() throws Exception {
		final int server2Port = SocketUtils.findAvailableTcpPort();

		SelfSignedCertificate cert1 = new SelfSignedCertificate();
		DisposableServer server1 = null;
		DisposableServer server2 = null;
		try {
			server1 =
					createServer()
					          .secure(spec -> spec.sslContext(SslContextBuilder.forServer(cert1.certificate(), cert1.privateKey())))
					          .handle((req, res) -> res.sendRedirect("https://localhost:" + server2Port))
					          .bindNow();

			SelfSignedCertificate cert2 = new SelfSignedCertificate();
			server2 =
					createServer(server2Port)
					          .host("localhost")
					          .secure(spec -> spec.sslContext(SslContextBuilder.forServer(cert2.certificate(), cert2.privateKey())))
					          .handle((req, res) -> res.sendString(Mono.just("test")))
					          .bindNow();

			AtomicInteger peerPort = new AtomicInteger(0);
			createClient(server1::address)
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
		}
		finally {
			if (server1 != null) {
				server1.disposeNow();
			}
			if (server2 != null) {
				server2.disposeNow();
			}
		}
	}

	@Test
	void testRelativeRedirectKeepsScheme() {
		final String requestPath = "/request";
		final String redirectPath = "/redirect";
		final String responseContent = "Success";

		disposableServer =
				createServer()
				          .route(r ->
				                  r.get(requestPath, (req, res) -> res.sendRedirect(redirectPath))
				                   .get(redirectPath, (req, res) -> res.sendString(Mono.just(responseContent))))
				          .bindNow();

		final Mono<String> responseMono =
				HttpClient.create()
				          .wiretap(true)
				          .followRedirect(true)
				          .secure(spec -> spec.sslContext(
				                  SslContextBuilder.forClient()
				                                   .trustManager(InsecureTrustManagerFactory.INSTANCE)))
				          .get()
				          .uri("http://localhost:" + disposableServer.port() + requestPath)
				          .responseContent()
				          .aggregate()
				          .asString();

		StepVerifier.create(responseMono)
		            .expectNext(responseContent)
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));
	}

	@Test
	void testLastLocationSetToResourceUrlOnRedirect() throws CertificateException {
		final String redirectPath = "/redirect";
		final String destinationPath = "/destination";
		final String responseContent = "Success";

		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverSslCtxBuilder =
				SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		DisposableServer redirectServer = null;
		DisposableServer initialServer = null;
		try {
			redirectServer =
					createServer()
					          .route(r ->
					                  r.get(redirectPath, (req, res) -> res.sendRedirect(destinationPath))
					                   .get(destinationPath, (req, res) -> res.sendString(Mono.just(responseContent)))
					          )
					          .secure(spec -> spec.sslContext(serverSslCtxBuilder))
					          .bindNow();

			final int redirectServerPort = redirectServer.port();
			initialServer =
					createServer()
					          .handle((req, res) ->
					              res.sendRedirect("https://localhost:" + redirectServerPort + destinationPath)
					          )
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
			                  .response((res, conn) -> Mono.justOrEmpty(res.resourceUrl())))
			            .expectNext("https://localhost:" + redirectServer.port() + destinationPath)
			            .expectComplete()
			            .verify(Duration.ofSeconds(30));
		}
		finally {
			if (redirectServer != null) {
				redirectServer.disposeNow();
			}
			if (initialServer != null) {
				initialServer.disposeNow();
			}
		}
	}

	@Test
	void testBuffersForRedirectWithContentShouldBeReleased() {
		doTestBuffersForRedirectWithContentShouldBeReleased("Redirect response content!");
	}

	@Test
	void testBuffersForRedirectWithLargeContentShouldBeReleased() {
		doTestBuffersForRedirectWithContentShouldBeReleased(StringUtils.repeat("a", 10000));
	}

	private void doTestBuffersForRedirectWithContentShouldBeReleased(String redirectResponseContent) {
		final String initialPath = "/initial";
		final String redirectPath = "/redirect";

		disposableServer =
				createServer()
				          .route(r -> r.get(initialPath,
				                            (req, res) -> res.status(HttpResponseStatus.MOVED_PERMANENTLY)
				                                             .header(HttpHeaderNames.LOCATION, redirectPath)
				                                             .sendString(Mono.just(redirectResponseContent)))
				                       .get(redirectPath, (req, res) -> res.send()))
				          .bindNow();

		ConnectionProvider provider = ConnectionProvider.create("doTestBuffersForRedirectWithContentShouldBeReleased", 1);
		final List<Integer> redirectBufferRefCounts = new ArrayList<>();
		HttpClient.create(provider)
		          .doOnRequest((r, c) -> c.addHandler("test-buffer-released", new ChannelInboundHandlerAdapter() {

		              @Override
		              public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		                  super.channelRead(ctx, msg);

		                  if (initialPath.equals("/" + r.path()) && msg instanceof HttpContent) {
		                      redirectBufferRefCounts.add(ReferenceCountUtil.refCnt(msg));
		                  }
		              }
		          }))
		          .wiretap(true)
		          .followRedirect(true)
		          .get()
		          .uri("http://localhost:" + disposableServer.port() + initialPath)
		          .response()
		          .block(Duration.ofSeconds(30));

		System.gc();

		assertThat(redirectBufferRefCounts).as("The HttpContents belonging to the redirection response should all be released")
		                                   .containsOnly(0);

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	void testHttpServerWithDomainSockets_HTTP11() {
		doTestHttpServerWithDomainSockets(HttpServer.create(), HttpClient.create());
	}

	@Test
	void testHttpServerWithDomainSockets_HTTP2() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContextBuilder clientCtx = SslContextBuilder.forClient()
		                                               .trustManager(InsecureTrustManagerFactory.INSTANCE);
		doTestHttpServerWithDomainSockets(
				HttpServer.create().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
				HttpClient.create().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)));
	}

	private void doTestHttpServerWithDomainSockets(HttpServer server, HttpClient client) {
		assumeThat(LoopResources.hasNativeSupport()).isTrue();
		disposableServer =
				server.bindAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
				      .wiretap(true)
				      .route(r -> r.get("/redirect", (req, res) -> res.sendRedirect("/end"))
				                   .get("/end", (req, res) -> res.sendString(Mono.just("END"))))
				      .bindNow();

		String response =
				client.remoteAddress(disposableServer::address)
				      .wiretap(true)
				      .followRedirect(true)
				      .get()
				      .uri("/redirect")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("END");
	}

	@Test
	void testHttp2Redirect() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContextBuilder clientCtx = SslContextBuilder.forClient()
		                                               .trustManager(InsecureTrustManagerFactory.INSTANCE);
		disposableServer =
				createServer()
				          .host("localhost")
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(serverCtx))
				          .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
				                       .get("/3", (req, res) -> res.status(200)
				                                                   .sendString(Mono.just("OK"))))
				          .wiretap(true)
				          .bindNow();

		Tuple2<String, Integer> response =
				createClient(disposableServer::address)
				          .followRedirect(true)
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(clientCtx))
				          .get()
				          .uri("/1")
				          .responseSingle((res, bytes) -> bytes.asString()
				                                               .zipWith(Mono.just(res.status().code())))
				          .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.getT2()).isEqualTo(200);
		assertThat(response.getT1()).isEqualTo("OK");
	}
}
