/*
 * Copyright (c) 2017-2024 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class HttpRedirectTest extends BaseHttpTest {

	static SelfSignedCertificate ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

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
		HttpClient redirectingClient = client.followRedirect(true);

		Flux<String> urls = Flux.just("/1", "/1", "/2", "/2");
		Flux<HttpClient> clients = Flux.just(redirectingClient, client, redirectingClient, client);

		Flux.zip(urls, clients)
		    .concatMap(t -> t.getT2()
		                     .get()
		                     .uri(t.getT1())
		                     .responseContent()
		                     .aggregate()
		                     .asString()
		                     .defaultIfEmpty("null"))
		    .collectList()
		    .as(StepVerifier::create)
		    .expectNext(Arrays.asList("OK", "null", "OK", "null"))
		    .expectComplete()
		    .verify(Duration.ofSeconds(30));
	}

	/*
	 * https://github.com/reactor/reactor-netty/issues/278
	 * https://github.com/reactor/reactor-netty/issues/1533
	 */
	@Test
	void testAbsoluteAndRelativeLocationRedirection() {
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
					                       .get("/4", (req, res) -> res.sendRedirect("http://localhost:" + serverPort2 + "/1"))
					                       .get("/5", (req, res) -> res.sendRedirect("//localhost:" + serverPort1 + "/3"))
					                       .get("/6", (req, res) -> res.sendRedirect("./3"))
					                       .get("/7/1", (req, res) -> res.sendRedirect("../3")))
					          .bindNow();

			server2 =
					createServer(serverPort2)
					          .host("localhost")
					          .route(r -> r.get("/1", (req, res) -> res.sendString(Mono.just("Other"))))
					          .bindNow();

			String baseUrl1 = "http://localhost:" + serverPort1;
			String baseUrl2 = "http://localhost:" + serverPort2;
			HttpClient client = HttpClient.create()
			                              .baseUrl(baseUrl1);

			doTestAbsoluteAndRelativeLocationRedirection(client, "/1", "OK", true, baseUrl1 + "/1", baseUrl1 + "/3");
			doTestAbsoluteAndRelativeLocationRedirection(client, "/2", "OK", true, baseUrl1 + "/2", baseUrl1 + "/3");
			doTestAbsoluteAndRelativeLocationRedirection(client, "/3", "OK", false, baseUrl1 + "/3");
			doTestAbsoluteAndRelativeLocationRedirection(client, "/4", "Other", true, baseUrl1 + "/4", baseUrl2 + "/1");
			doTestAbsoluteAndRelativeLocationRedirection(client, "/5", "OK", true, baseUrl1 + "/5", baseUrl1 + "/3");
			doTestAbsoluteAndRelativeLocationRedirection(client, "/6", "OK", true, baseUrl1 + "/6", baseUrl1 + "/3");
			doTestAbsoluteAndRelativeLocationRedirection(client, "/7/1", "OK", true, baseUrl1 + "/7/1", baseUrl1 + "/3");
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

	private void doTestAbsoluteAndRelativeLocationRedirection(
			HttpClient client, String uri, String expectedResponse, boolean expectRedirect, String... expectedResourceUrl) {
		AtomicBoolean redirected = new AtomicBoolean();
		AtomicReference<List<String>> resourceUrls = new AtomicReference<>(new ArrayList<>());
		client.doOnRequest((req, conn) -> resourceUrls.get().add(req.resourceUrl()))
		      .doOnRedirect((res, conn) -> redirected.set(true))
		      .followRedirect(true)
		      .get()
		      .uri(uri)
		      .responseContent()
		      .aggregate()
		      .asString()
		      .as(StepVerifier::create)
		      .expectNext(expectedResponse)
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));

		assertThat(redirected.get()).isEqualTo(expectRedirect);
		assertThat(resourceUrls.get().toArray(new String[0])).isEqualTo(expectedResourceUrl);
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
				                       .post("/303", (req, res) ->
				                          res.status(303)
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

		Flux.just("/301", "/302", "/303", "/307", "/308", "/predicate")
		    .flatMap(s -> {
		            HttpClient localClient = "/predicate".equals(s) ?
		                    client.followRedirect((req, res) -> res.responseHeaders().contains("test")) :
		                    client.followRedirect(true);
		            return localClient.get()
		                              .uri(s)
		                              .responseContent()
		                              .aggregate()
		                              .asString();
		    })
		    .collectList()
		    .as(StepVerifier::create)
		    .assertNext(l -> assertThat(l).allMatch("OK"::equals))
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
		          .blockLast(Duration.ofSeconds(5));

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
	@SuppressWarnings("deprecation")
	void testIssue843() {
		final int server2Port = SocketUtils.findAvailableTcpPort();

		DisposableServer server1 = null;
		DisposableServer server2 = null;
		try {
			server1 =
					createServer()
					          .secure(spec ->
					              spec.sslContext(Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())))
					          .handle((req, res) -> res.sendRedirect("https://localhost:" + server2Port))
					          .bindNow();

			server2 =
					createServer(server2Port)
					          .host("localhost")
					          .secure(spec ->
					              spec.sslContext(Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())))
					          .handle((req, res) -> res.sendString(Mono.just("test")))
					          .bindNow();

			AtomicInteger peerPort = new AtomicInteger(0);
			Http11SslContextSpec http11SslContextSpec =
					Http11SslContextSpec.forClient()
					                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
			createClient(server1::address)
			          .followRedirect(true)
			          .secure(spec -> spec.sslContext(http11SslContextSpec))
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
	@SuppressWarnings("deprecation")
	void testHttpRequestIfRedirectHttpToHttpsEnabled() {
		Http11SslContextSpec sslContext = Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		disposableServer =
				createServer()
						.host("localhost")
						.secure(spec -> spec.sslContext(sslContext), true)
						.bindNow();
		AtomicReference<Connection> connectionRef = new AtomicReference<>();
		HttpClient client =
				HttpClient.create()
						.doOnConnected(connectionRef::set)
						.host(disposableServer.host())
						.port(disposableServer.port());
		String uri = String.format("http://%s:%d/for-test/123", disposableServer.host(), disposableServer.port());
		StepVerifier
				.create(client.get().uri(uri).response()
						.doOnNext(response -> {
							String location = response.responseHeaders().get(HttpHeaderNames.LOCATION);
							String expectedLocation = uri.replace("http://", "https://");

							assertThat(response.status()).isEqualTo(HttpResponseStatus.PERMANENT_REDIRECT);
							assertThat(location).isEqualTo(expectedLocation);
							assertThat(connectionRef.get().isDisposed()).isTrue();
						}))
				.expectNextCount(1)
				.expectComplete()
				.verify(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testHttpsRequestIfRedirectHttpToHttpsEnabled() {
		String message = "The client should receive the message";
		disposableServer =
				createServer()
						.host("localhost")
						.secure(spec ->
								spec.sslContext(Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())), true)
						.handle((request, response) -> response.sendString(Mono.just(message)))
						.bindNow();
		Http11SslContextSpec http11SslContextSpec =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		HttpClient client =
				HttpClient.create()
				          .secure(spec -> spec.sslContext(http11SslContextSpec));
		String uri = String.format("https://%s:%d/for-test/123", disposableServer.host(), disposableServer.port());
		StepVerifier
				.create(client.get().uri(uri).response((response, body) -> {
					assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
					return body.aggregate().asString();
				}))
				.expectNextMatches(message::equals)
				.expectComplete()
				.verify(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("deprecation")
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

		Http11SslContextSpec http11SslContextSpec =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		final Mono<String> responseMono =
				HttpClient.create()
				          .wiretap(true)
				          .followRedirect(true)
				          .secure(spec -> spec.sslContext(http11SslContextSpec))
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
	@SuppressWarnings("deprecation")
	void testLastLocationSetToResourceUrlOnRedirect() {
		final String redirectPath = "/redirect";
		final String destinationPath = "/destination";
		final String responseContent = "Success";

		Http11SslContextSpec serverSslContextSpec =
				Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		DisposableServer redirectServer = null;
		DisposableServer initialServer = null;
		try {
			redirectServer =
					createServer()
					          .route(r ->
					                  r.get(redirectPath, (req, res) -> res.sendRedirect(destinationPath))
					                   .get(destinationPath, (req, res) -> res.sendString(Mono.just(responseContent)))
					          )
					          .secure(spec -> spec.sslContext(serverSslContextSpec))
					          .bindNow();

			final int redirectServerPort = redirectServer.port();
			initialServer =
					createServer()
					          .handle((req, res) ->
					              res.sendRedirect("https://localhost:" + redirectServerPort + destinationPath)
					          )
					          .bindNow();

			Http11SslContextSpec clientSslContextSpec =
					Http11SslContextSpec.forClient()
					                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
			final String requestUri = "http://localhost:" + initialServer.port();
			StepVerifier.create(
			        HttpClient.create()
			                  .wiretap(true)
			                  .followRedirect(true)
			                  .secure(spec -> spec.sslContext(clientSslContextSpec))
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
		          .doOnRequest((r, c) -> c.addHandlerLast("test-buffer-released", new ChannelInboundHandlerAdapter() {

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
	@SuppressWarnings("deprecation")
	void testHttpServerWithDomainSockets_HTTP2() {
		Http11SslContextSpec serverCtx = Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http11SslContextSpec clientCtx =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
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
	@SuppressWarnings("deprecation")
	void testHttp2Redirect() {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
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

	@Test
	void testIssue2670() {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("testIssue2670")))
				        .bindNow();

		createClient(disposableServer.port())
		        .followRedirect((req, res) -> true)
		        .get()
		        .uri("/")
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("testIssue2670")
		        .expectComplete()
		        .verify(Duration.ofSeconds(5));
	}

	@Test
	@SuppressWarnings("CollectionUndefinedEquality")
	void testIssue3035() {
		disposableServer =
				createServer()
				        .route(r -> r.get("/1", (req, res) -> res.sendRedirect("/2"))
				                     .get("/2", (req, res) ->
				                             req.cookies().containsKey("testCookie") ?
				                                     res.status(200).sendString(Mono.just("OK")) :
				                                     res.status(400).sendString(Mono.just("KO"))))
				        .bindNow();

		createClient(disposableServer::address)
		        .followRedirect(
		                (req, res) -> res.status().code() == 302,
		                (headers, redirect) -> redirect.addCookie(new DefaultCookie("testCookie", "testCookie")))
		        .get()
		        .uri("/1")
		        .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res.status().code())))
		        .as(StepVerifier::create)
		        .expectNextMatches(tuple -> "OK".equals(tuple.getT1()) && tuple.getT2() == 200)
		        .expectComplete()
		        .verify(Duration.ofSeconds(10));
	}
}
