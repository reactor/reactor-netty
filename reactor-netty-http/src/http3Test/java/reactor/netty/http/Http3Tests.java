/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.LogTracker;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.HTTP_CLIENT_PREFIX;
import static reactor.netty.Metrics.HTTP_SERVER_PREFIX;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.PROXY_ADDRESS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

/**
 * Holds HTTP/3 specific tests.
 *
 * @author Violeta Georgieva
 * @since 1.2.0
 */
class Http3Tests {
	static final String HTTP3_WITHOUT_TLS_CLIENT = "Configured HTTP/3 protocol without TLS. Check URL scheme";
	static final String HTTP3_WITHOUT_TLS_SERVER = "Configured HTTP/3 protocol without TLS. " +
			"Configure TLS via HttpServer#secure";

	static SelfSignedCertificate ssc;

	DisposableServer disposableServer;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@AfterEach
	void disposeServer() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Test
	void test100Continue() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		disposableServer =
				createServer()
				        .handle((req, res) -> req.receive()
				                                 .aggregate()
				                                 .asString()
				                                 .flatMap(s -> {
				                                     latch.countDown();
				                                     return res.sendString(Mono.just(s)).then();
				                                 }))
				        .bindNow();

		Tuple2<String, Integer> content =
				createClient(disposableServer.port())
				        .headers(h -> h.add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE))
				        .post()
				        .uri("/")
				        .send(ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5")))
				        .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res.status().code())))
				        .block(Duration.ofSeconds(5));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(content).isNotNull();
		assertThat(content.getT1()).isEqualTo("12345");
		assertThat(content.getT2()).isEqualTo(200);
	}

	@Test
	void test100ContinueConnectionClose() throws Exception {
		doTest100ContinueConnection(
				(req, res) -> res.status(400).sendString(Mono.just("ERROR")),
				ByteBufFlux.fromString(Flux.just("1", "2", "3", "4", "5").delaySubscription(Duration.ofMillis(100))));
	}

	private void doTest100ContinueConnection(
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> postHandler,
			Publisher<ByteBuf> sendBody) throws Exception {

		CountDownLatch latch = new CountDownLatch(2);
		AtomicReference<List<Channel>> channels = new AtomicReference<>(new ArrayList<>(2));
		disposableServer =
				createServer()
				        .doOnConnection(conn -> {
				            channels.get().add(conn.channel());
				            conn.onTerminate().subscribe(null, t -> latch.countDown(), latch::countDown);
				        })
				        .route(r -> r.post("/post", postHandler)
				                     .get("/get", (req, res) -> res.sendString(Mono.just("OK"))))
				        .bindNow();

		Mono<Tuple2<String, HttpClientResponse>> content1 =
				createClient(disposableServer.port())
				        .headers(h -> h.add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE))
				        .post()
				        .uri("/post")
				        .send(sendBody)
				        .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res)));

		Mono<Tuple2<String, HttpClientResponse>> content2 =
				createClient(disposableServer.port())
				        .get()
				        .uri("/get")
				        .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res)));

		List<Tuple2<String, HttpClientResponse>> responses =
				Flux.concat(content1, content2)
				    .collectList()
				    .block(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(responses).isNotNull();
		assertThat(responses.size()).isEqualTo(2);
		assertThat(responses.get(0).getT1()).isEqualTo("ERROR");
		assertThat(responses.get(0).getT2().status().code()).isEqualTo(400);
		assertThat(responses.get(1).getT1()).isEqualTo("OK");
		assertThat(responses.get(1).getT2().status().code()).isEqualTo(200);

		assertThat(channels.get().size()).isEqualTo(2);
		assertThat(channels.get()).doesNotHaveDuplicates();

		assertThat(responses.get(0).getT2().responseHeaders().get(HttpHeaderNames.CONNECTION)).isNull();
	}

	@Test
	void testAccessLog() throws Exception {
		disposableServer =
				createServer()
				        .route(r -> r.get("/", (req, resp) -> {
				            resp.withConnection(conn -> {
				                ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
				                resp.header(NettyPipeline.AccessLogHandler, handler != null ? "FOUND" : "NOT FOUND");
				            });
				            return resp.send();
				        }))
				        .accessLog(true)
				        .bindNow();

		String okMessage = "GET / HTTP/3.0\" 200";
		String notFoundMessage = "GET /not_found HTTP/3.0\" 404";
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.AccessLog", okMessage, notFoundMessage)) {
			HttpClient client = createClient(disposableServer.port());

			doTestAccessLog(client, "/", res -> Mono.just(res.responseHeaders().get(NettyPipeline.AccessLogHandler)), "FOUND");

			doTestAccessLog(client, "/not_found", res -> Mono.just(res.status().toString()), "404 Not Found");

			assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).isTrue();

			assertThat(logTracker.actualMessages).hasSize(2);
			List<String> actual = new ArrayList<>(2);
			logTracker.actualMessages.forEach(e -> {
				String msg = e.getFormattedMessage();
				int startInd = msg.indexOf('"') + 1;
				int endInd = msg.lastIndexOf('"') + 5;
				actual.add(e.getFormattedMessage().substring(startInd, endInd));
			});
			assertThat(actual).hasSameElementsAs(Arrays.asList(okMessage, notFoundMessage));
		}
	}

	static void doTestAccessLog(HttpClient client, String uri, Function<HttpClientResponse, Mono<String>> response, String expectation) {
		client.get()
		      .uri(uri)
		      .responseSingle((res, bytes) -> response.apply(res))
		      .as(StepVerifier::create)
		      .expectNext(expectation)
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
	}

	@Test
	void testAccessLogWithForwardedHeader() throws Exception {
		Function<SocketAddress, String> applyAddress = addr ->
				addr instanceof InetSocketAddress ? ((InetSocketAddress) addr).getHostString() : "-";

		disposableServer =
				createServer()
				        .handle((req, resp) -> {
				            resp.withConnection(conn -> {
				                ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.AccessLogHandler);
				                resp.header(NettyPipeline.AccessLogHandler, handler != null ? "FOUND" : "NOT FOUND");
				            });
				            return resp.send();
				        })
				        .forwarded(true)
				        .accessLog(true, args -> AccessLog.create(
				            "{} {} {}",
				            applyAddress.apply(args.connectionInformation().remoteAddress()),
				            applyAddress.apply(args.connectionInformation().hostAddress()), args.connectionInformation().scheme()))
				        .bindNow();

		String expectedLogRecord = "192.0.2.60 203.0.113.43 http";
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.AccessLog", expectedLogRecord)) {
			createClient(disposableServer.port())
			        .doOnRequest((req, cnx) -> req.addHeader("Forwarded", "for=192.0.2.60;proto=http;host=203.0.113.43"))
			        .get()
			        .uri("/")
			        .responseSingle((res, bytes) -> Mono.just(res.responseHeaders().get(NettyPipeline.AccessLogHandler)))
			        .as(StepVerifier::create)
			        .expectNext("FOUND")
			        .expectComplete()
			        .verify(Duration.ofSeconds(5));

			assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void testConcurrentRequestsDefaultPool() {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("testConcurrentRequestsDefaultPool")))
				        .bindNow();

		doTestConcurrentRequests(createClient(disposableServer.port()));
	}

	@Test
	void testConcurrentRequestsOneConnection() {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("testConcurrentRequestsOneConnection")))
				        .bindNow();

		ConnectionProvider provider = ConnectionProvider.newConnection();
		try {
			doTestConcurrentRequests(createClient(provider, disposableServer.port()));
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testConcurrentRequestsCustomPool() {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("testConcurrentRequestsCustomPool")))
				        .bindNow();

		ConnectionProvider provider =
				ConnectionProvider.builder("testConcurrentRequestsCustomPool")
				                  .maxConnections(1)
				                  .pendingAcquireMaxCount(10)
				                  .build();
		try {
			doTestConcurrentRequests(createClient(provider, disposableServer.port()));
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	static void doTestConcurrentRequests(HttpClient client) {
		List<String> responses =
				Flux.range(0, 10)
				    .flatMapDelayError(i ->
				            client.get()
				                  .uri("/")
				                  .responseContent()
				                  .aggregate()
				                  .asString(), 256, 32)
				    .collectList()
				    .block(Duration.ofSeconds(5));

		assertThat(responses).isNotNull();
		assertThat(responses.size()).isEqualTo(10);
	}

	@Test
	void testGetRequest() {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("Hello")))
				        .bindNow();

		createClient(disposableServer.port())
		        .get()
		        .uri("/")
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("Hello")
		        .expectComplete()
		        .verify(Duration.ofSeconds(5));
	}

	@Test
	void testHttpClientNoSecurityHttp3Fails() {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("Hello")))
				        .bindNow();

		createClient(disposableServer.port())
		          .noSSL()
		          .get()
		          .uri("/")
		          .responseContent()
		          .aggregate()
		          .asString()
		          .as(StepVerifier::create)
		          .verifyErrorMessage(HTTP3_WITHOUT_TLS_CLIENT);
	}

	@Test
	void testHttpServerNoSecurityHttp3Fails() {
		createServer()
		        .noSSL()
		        .handle((req, res) -> res.sendString(Mono.just("Hello")))
		        .bind()
		        .as(StepVerifier::create)
		        .verifyErrorMessage(HTTP3_WITHOUT_TLS_SERVER);
	}

	@Test
	@Disabled
	void testMetrics() throws Exception {
		disposableServer =
				createServer()
				        .metrics(true, Function.identity())
				        .handle((req, res) -> res.send(req.receive().retain()))
				        .bindNow();

		MeterRegistry registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
		try {
			AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(2);
			registry.config().onMeterAdded(meter -> {
				if (CLIENT_RESPONSE_TIME.equals(meter.getId().getName())) {
					latch.countDown();
				}
			});
			createClient(disposableServer.port())
			        .doAfterRequest((req, conn) -> {
			            serverAddress.set(((QuicChannel) conn.channel().parent()).remoteSocketAddress());
			            conn.channel().closeFuture().addListener(f -> latch.countDown());
			        })
			        .metrics(true, Function.identity())
			        .post()
			        .uri("/")
			        .send(ByteBufFlux.fromString(Flux.just("Hello", " ", "World", "!")))
			        .responseContent()
			        .aggregate()
			        .asString()
			        .as(StepVerifier::create)
			        .expectNext("Hello World!")
			        .expectComplete()
			        .verify(Duration.ofSeconds(5));

			assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();

			Thread.sleep(1000);
			InetSocketAddress sa = (InetSocketAddress) serverAddress.get();
			String[] timerTags1 = new String[]{URI, "/", METHOD, "POST", STATUS, "200"};
			String[] timerTags2 = new String[]{URI, "/", METHOD, "POST"};
			assertTimer(registry, SERVER_RESPONSE_TIME, timerTags1)
					.hasCountEqualTo(1)
					.hasTotalTimeGreaterThan(0);
			assertTimer(registry, SERVER_DATA_SENT_TIME, timerTags1)
					.hasCountEqualTo(1)
					.hasTotalTimeGreaterThan(0);
			assertTimer(registry, SERVER_DATA_RECEIVED_TIME, timerTags2)
					.hasCountEqualTo(1)
					.hasTotalTimeGreaterThan(0);

			String serverAddressStr = sa.getHostString() + ":" + sa.getPort();
			timerTags1 = new String[] {REMOTE_ADDRESS, serverAddressStr, PROXY_ADDRESS, NA, URI, "/", METHOD, "POST", STATUS, "200"};
			timerTags2 = new String[] {REMOTE_ADDRESS, serverAddressStr, PROXY_ADDRESS, NA, URI, "/", METHOD, "POST"};
			assertTimer(registry, CLIENT_RESPONSE_TIME, timerTags1)
					.hasCountEqualTo(1)
					.hasTotalTimeGreaterThan(0);
			assertTimer(registry, CLIENT_DATA_SENT_TIME, timerTags2)
					.hasCountEqualTo(1)
					.hasTotalTimeGreaterThan(0);
			assertTimer(registry, CLIENT_DATA_RECEIVED_TIME, timerTags1)
					.hasCountEqualTo(1)
					.hasTotalTimeGreaterThan(0);
		}
		finally {
			Metrics.removeRegistry(registry);
			registry.clear();
			registry.close();
		}
	}

	@Test
	void testPostRequest() {
		doTestPostRequest(false);
	}

	@Test
	void testPostRequestExternalThread() {
		doTestPostRequest(true);
	}

	void doTestPostRequest(boolean externalThread) {
		disposableServer =
				createServer()
				        .handle((req, res) -> {
				            Flux<ByteBuf> publisher = req.receive().retain();
				            if (externalThread) {
				                publisher = publisher.subscribeOn(Schedulers.boundedElastic());
				            }
				            return res.send(publisher);
				        })
				        .bindNow();

		createClient(disposableServer.port())
		        .post()
		        .uri("/")
		        .send(ByteBufFlux.fromString(Mono.just("Hello")))
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("Hello")
		        .expectComplete()
		        .verify(Duration.ofSeconds(5));
	}

	@Test
	void testTrailerHeadersChunkedResponse() {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set("foo", "bar"))
				               .sendString(Flux.just("testTrailerHeaders", "ChunkedResponse")))
				        .bindNow();
		doTestTrailerHeaders(createClient(disposableServer.port()), "bar", "testTrailerHeadersChunkedResponse");
	}

	@Test
	void testTrailerHeadersDisallowedNotSent() {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, HttpHeaderNames.CONTENT_LENGTH)
				               .trailerHeaders(h -> h.set(HttpHeaderNames.CONTENT_LENGTH, "33"))
				               .sendString(Flux.just("testDisallowedTrailer", "HeadersNotSent")))
				        .bindNow();

		// Trailer header name [content-length] not declared with [Trailer] header, or it is not a valid trailer header name
		doTestTrailerHeaders(createClient(disposableServer.port()), "empty", "testDisallowedTrailerHeadersNotSent");
	}

	@Test
	void testTrailerHeadersFailedChunkedResponse() {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set("foo", "bar"))
				               .sendString(Flux.range(0, 3)
				                               .delayElements(Duration.ofMillis(50))
				                               .flatMap(i -> i == 2 ? Mono.error(new RuntimeException()) : Mono.just(i + ""))
				                               .doOnError(t -> res.trailerHeaders(h -> h.set("foo", "error")))
				                               .onErrorResume(t -> Mono.empty())))
				        .bindNow();

		doTestTrailerHeaders(createClient(disposableServer.port()), "error", "01");
	}

	@Test
	void testTrailerHeadersFullResponse() {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set("foo", "bar"))
				               .sendString(Mono.just("testTrailerHeadersFullResponse")))
				        .bindNow();

		doTestTrailerHeaders(createClient(disposableServer.port()), "empty", "testTrailerHeadersFullResponse");
	}

	@Test
	void testTrailerHeadersNotSpecifiedUpfront() {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set(HttpHeaderNames.CONTENT_LENGTH, "33"))
				               .sendString(Flux.just("testTrailerHeaders", "NotSpecifiedUpfront")))
				        .bindNow();

		// Trailer header name [content-length] not declared with [Trailer] header, or it is not a valid trailer header name
		doTestTrailerHeaders(createClient(disposableServer.port()), "empty", "testTrailerHeadersNotSpecifiedUpfront");
	}

	private static void doTestTrailerHeaders(HttpClient client, String expectedHeaderValue, String expectedResponse) {
		client.get()
		      .uri("/")
		      .responseSingle((res, bytes) -> bytes.asString().zipWith(res.trailerHeaders()))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> expectedResponse.equals(t.getT1()) &&
		              expectedHeaderValue.equals(t.getT2().get("foo", "empty")))
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
	}

	static HttpClient createClient(int port) {
		return createClient(null, port);
	}

	static HttpClient createClient(@Nullable ConnectionProvider pool, int port) {
		Http3SslContextSpec clientCtx =
				Http3SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		HttpClient client = pool == null ? HttpClient.create() : HttpClient.create(pool);
		return client.port(port)
		             .wiretap(true)
		             .protocol(HttpProtocol.HTTP3)
		             .secure(spec -> spec.sslContext(clientCtx))
		             .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5))
		                                        .maxData(10000000)
		                                        .maxStreamDataBidirectionalLocal(1000000));
	}

	static HttpServer createServer() {
		Http3SslContextSpec serverCtx = Http3SslContextSpec.forServer(ssc.key(), null, ssc.cert());
		return HttpServer.create()
		                 .port(0)
		                 .wiretap(true)
		                 .protocol(HttpProtocol.HTTP3)
		                 .secure(spec -> spec.sslContext(serverCtx))
		                 .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5))
		                                            .maxData(10000000)
		                                            .maxStreamDataBidirectionalLocal(1000000)
		                                            .maxStreamDataBidirectionalRemote(1000000)
		                                            .maxStreamsBidirectional(100)
		                                            .tokenHandler(InsecureQuicTokenHandler.INSTANCE));
	}

	private static final String CLIENT_RESPONSE_TIME = HTTP_CLIENT_PREFIX + RESPONSE_TIME;
	private static final String CLIENT_DATA_SENT_TIME = HTTP_CLIENT_PREFIX + DATA_SENT_TIME;
	private static final String CLIENT_DATA_RECEIVED_TIME = HTTP_CLIENT_PREFIX + DATA_RECEIVED_TIME;
	private static final String SERVER_RESPONSE_TIME = HTTP_SERVER_PREFIX + RESPONSE_TIME;
	private static final String SERVER_DATA_SENT_TIME = HTTP_SERVER_PREFIX + DATA_SENT_TIME;
	private static final String SERVER_DATA_RECEIVED_TIME = HTTP_SERVER_PREFIX + DATA_RECEIVED_TIME;
}
