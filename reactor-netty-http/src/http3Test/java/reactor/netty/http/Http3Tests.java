/*
 * Copyright (c) 2024-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SniCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ByteBufFlux;
import reactor.netty.ByteBufMono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.LogTracker;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.http.client.HttpConnectionPoolMetrics;
import reactor.netty.http.client.HttpMeterRegistrarAdapter;
import reactor.netty.http.server.ConnectionInformation;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import javax.net.ssl.SNIHostName;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

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

	static X509Bundle ssc;

	@Nullable DisposableServer disposableServer;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
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
		Function<@Nullable SocketAddress, String> applyAddress = addr ->
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
				        .accessLog(true, args -> {
				            ConnectionInformation connectionInformation = args.connectionInformation();
				            return connectionInformation != null ?
				                    AccessLog.create("{} {} {}",
				                            applyAddress.apply(connectionInformation.remoteAddress()),
				                            applyAddress.apply(connectionInformation.hostAddress()), connectionInformation.scheme()) :
				                    null;
				        })
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
	void testConcurrentRequestsDefaultPool() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) -> res.sendString(Mono.just("testConcurrentRequestsDefaultPool")))
				        .bindNow();

		doTestConcurrentRequests(createClient(disposableServer.port()));
	}

	@Test
	void testConcurrentRequestsOneConnection() throws Exception {
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
	void testConcurrentRequestsCustomPool() throws Exception {
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
	void testGetRequest() throws Exception {
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
	void testHttp3ForMemoryLeaks() throws Exception {
		disposableServer =
				createServer()
				        .wiretap(false)
				        .handle((req, res) ->
				                res.sendString(Flux.range(0, 10)
				                                   .map(i -> "test")
				                                   .delayElements(Duration.ofMillis(4))))
				        .bindNow();

		HttpClient client = createClient(disposableServer.port()).wiretap(false);
		for (int i = 0; i < 1000; ++i) {
			try {
				client.get()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .timeout(Duration.ofMillis(ThreadLocalRandom.current().nextInt(1, 35)))
				      .block(Duration.ofMillis(100));
			}
			catch (Throwable t) {
				// ignore
			}
		}

		System.gc();
		for (int i = 0; i < 100000; ++i) {
			@SuppressWarnings("UnusedVariable")
			int[] arr = new int[100000];
		}
		System.gc();
	}

	@Test
	void testHttpClientNoSecurityHttp3Fails() throws Exception {
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
	void testHttpServerNoSecurityHttp3Fails() throws Exception {
		createServer()
		        .noSSL()
		        .handle((req, res) -> res.sendString(Mono.just("Hello")))
		        .bind()
		        .as(StepVerifier::create)
		        .verifyErrorMessage(HTTP3_WITHOUT_TLS_SERVER);
	}

	@Test
	void testIssue3524Flux() throws Exception {
		// sends the message and then last http content
		testRequestBody(sender -> sender.send((req, out) -> out.sendString(Flux.just("te", "st"))), 3);
	}

	@Test
	void testIssue3524Mono() throws Exception {
		// sends "full" request
		testRequestBody(sender -> sender.send((req, out) -> out.sendString(Mono.just("test"))), 1);
	}

	@Test
	void testIssue3524MonoEmpty() throws Exception {
		// sends "full" request
		testRequestBody(sender -> sender.send((req, out) -> Mono.empty()), 0);
	}

	@Test
	void testIssue3524NoBody() throws Exception {
		// sends "full" request
		testRequestBody(sender -> sender.send((req, out) -> out), 0);
	}

	@Test
	void testIssue3524Object() throws Exception {
		// sends "full" request
		testRequestBody(sender -> sender.send((req, out) -> out.sendObject(Unpooled.wrappedBuffer("test".getBytes(Charset.defaultCharset())))), 1);
	}

	@Test
	void testMaxActiveStreamsCustomPool() throws Exception {
		ConnectionProvider provider = ConnectionProvider.create("testMaxActiveStreamsCustomPool", 1);
		try {
			doTestMaxActiveStreams(provider, 2, 2, 0);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testMaxActiveStreamsCustomPoolOneMaxActiveStream() throws Exception {
		ConnectionProvider provider =
				ConnectionProvider.builder("testMaxActiveStreamsCustomPoolOneMaxActiveStream")
				                  .maxConnections(1)
				                  .pendingAcquireTimeout(Duration.ofMillis(10))
				                  .build();
		try {
			doTestMaxActiveStreams(provider, 1, 1, 1);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testMaxActiveStreamsDefaultPool() throws Exception {
		doTestMaxActiveStreams(2, 2, 0);
	}

	@Test
	void testMaxActiveStreamsNoPool() throws Exception {
		ConnectionProvider provider = ConnectionProvider.newConnection();
		try {
			doTestMaxActiveStreams(provider, 2, 2, 0);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	void doTestMaxActiveStreams(int maxActiveStreams, int expectedOnNext, int expectedOnError) throws Exception {
		doTestMaxActiveStreams(null, maxActiveStreams, expectedOnNext, expectedOnError);
	}

	void doTestMaxActiveStreams(@Nullable ConnectionProvider provider, int maxActiveStreams, int expectedOnNext, int expectedOnError) throws Exception {
		disposableServer =
				createServer()
				        .route(routes ->
				                routes.post("/echo", (req, res) -> res.send(req.receive()
				                                                               .aggregate()
				                                                               .retain()
				                                                               .delayElement(Duration.ofMillis(100)))))
				        .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5))
				                                   .maxData(10000000)
				                                   .maxStreamDataBidirectionalLocal(1000000)
				                                   .maxStreamDataBidirectionalRemote(1000000)
				                                   .maxStreamsBidirectional(maxActiveStreams)
				                                   .tokenHandler(InsecureQuicTokenHandler.INSTANCE))
				        .bindNow();

		HttpClient client = createClient(provider, disposableServer.port());

		CountDownLatch latch = new CountDownLatch(1);
		List<? extends Signal<? extends String>> list =
				Flux.range(0, 2)
				    .flatMapDelayError(i ->
				            client.post()
				                  .uri("/echo")
				                  .send(ByteBufFlux.fromString(Mono.just("doTestMaxActiveStreams")))
				                  .responseContent()
				                  .aggregate()
				                  .asString()
				                  .materialize(), 256, 32)
				    .collectList()
				    .doFinally(fin -> latch.countDown())
				    .block(Duration.ofSeconds(5));

		assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch 5s").isTrue();

		assertThat(list).isNotNull().hasSize(2);

		int onNext = 0;
		int onError = 0;
		String msg = "Pool#acquire(Duration) has been pending for more than the configured timeout of 10ms";
		for (int i = 0; i < 2; i++) {
			Signal<? extends String> signal = list.get(i);
			if (signal.isOnNext()) {
				onNext++;
			}
			else if (signal.getThrowable() instanceof PoolAcquireTimeoutException &&
					signal.getThrowable().getMessage().contains(msg)) {
				onError++;
			}
		}

		assertThat(onNext).isEqualTo(expectedOnNext);
		assertThat(onError).isEqualTo(expectedOnError);
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
	void testMonoRequestBodySentAsFullRequest_Flux() throws Exception {
		// sends the message and then last http content
		testRequestBody(sender -> sender.send(ByteBufFlux.fromString(Mono.just("test"))), 2);
	}

	@Test
	void testMonoRequestBodySentAsFullRequest_Mono() throws Exception {
		// sends "full" request
		testRequestBody(sender -> sender.send(ByteBufMono.fromString(Mono.just("test"))), 1);
	}

	@Test
	void testMonoRequestBodySentAsFullRequest_MonoEmpty() throws Exception {
		// sends "full" request
		testRequestBody(sender -> sender.send(Mono.empty()), 0);
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	private void testRequestBody(Function<HttpClient.RequestSender, HttpClient.ResponseReceiver<?>> sendFunction, int expectedMsg)
			throws Exception {
		disposableServer =
				createServer().handle((req, res) -> req.receive()
				                                       .then(res.send()))
				              .bindNow(Duration.ofSeconds(30));

		AtomicInteger counterHeaders = new AtomicInteger();
		AtomicInteger counterData = new AtomicInteger();
		sendFunction.apply(
				createClient(disposableServer.port())
				        .port(disposableServer.port())
				        .doOnRequest((req, conn) -> {
				            ChannelPipeline pipeline = conn.channel().pipeline();
				            ChannelHandlerContext ctx = pipeline.context(NettyPipeline.LoggingHandler);
				            if (ctx != null) {
				                pipeline.addAfter(ctx.name(), "testRequestBody",
				                        new ChannelOutboundHandlerAdapter() {
				                            boolean done;

				                            @Override
				                            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
				                                ctx.channel().closeFuture().addListener(f -> done = true);
				                                super.handlerAdded(ctx);
				                            }

				                            @Override
				                            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
				                                if (!done) {
				                                    if (msg instanceof Http3HeadersFrame) {
				                                        counterHeaders.getAndIncrement();
				                                    }
				                                    else if (msg instanceof Http3DataFrame) {
				                                        counterData.getAndIncrement();
				                                    }
				                                }
				                                //"FutureReturnValueIgnored" this is deliberate
				                                ctx.write(msg, promise);
				                            }
				                        });
				            }
				        })
				        .post()
				        .uri("/"))
				        .responseContent()
				        .aggregate()
				        .asString()
				        .block(Duration.ofSeconds(30));

		assertThat(counterHeaders.get()).isEqualTo(1);
		assertThat(counterData.get()).isEqualTo(expectedMsg);
	}

	@Test
	void testPostRequest() throws Exception {
		doTestPostRequest(false);
	}

	@Test
	void testPostRequestExternalThread() throws Exception {
		doTestPostRequest(true);
	}

	void doTestPostRequest(boolean externalThread) throws Exception {
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
	void testProtocolVersion() throws Exception {
		disposableServer =
				createServer().handle((req, res) -> res.sendString(Mono.just(req.protocol())))
				              .bindNow();

		createClient(disposableServer.port())
		        .get()
		        .uri("/")
		        .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res.version().text())))
		        .as(StepVerifier::create)
		        .expectNextMatches(t -> t.getT1().equals(t.getT2()) && "HTTP/3.0".equals(t.getT1()))
		        .expectComplete()
		        .verify(Duration.ofSeconds(5));
	}

	@Test
	void testResponseTimeout() throws Exception {
		disposableServer =
				createServer().handle((req, res) -> res.sendString(Mono.just("testResponseTimeout")))
				              .bindNow();

		HttpClient client = createClient(disposableServer.port()).responseTimeout(Duration.ofMillis(100));
		doTestResponseTimeout(client, 100);

		client = client.doOnRequest((req, conn) -> req.responseTimeout(Duration.ofMillis(200)));
		doTestResponseTimeout(client, 200);
	}

	private static void doTestResponseTimeout(HttpClient client, long expectedTimeout) throws Exception {
		AtomicBoolean onRequest = new AtomicBoolean();
		AtomicBoolean onResponse = new AtomicBoolean();
		AtomicBoolean onDisconnected = new AtomicBoolean();
		AtomicLong timeout = new AtomicLong();
		Predicate<Connection> handlerAvailable =
				conn -> conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler) != null;
		HttpClient localClient =
				client.doOnRequest((req, conn) -> onRequest.set(handlerAvailable.test(conn)))
				      .doOnResponse((req, conn) -> {
				          if (handlerAvailable.test(conn)) {
				              ChannelHandler handler = conn.channel().pipeline().get(NettyPipeline.ResponseTimeoutHandler);
				              onResponse.set(true);
				              timeout.set(((ReadTimeoutHandler) handler).getReaderIdleTimeInMillis());
				          }
				      })
				      .doOnDisconnected(conn -> onDisconnected.set(conn.channel().isActive() && handlerAvailable.test(conn)));

		Mono<String> response =
				localClient.get()
				           .uri("/")
				           .responseContent()
				           .aggregate()
				           .asString();

		StepVerifier.create(response)
		            .expectNext("testResponseTimeout")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(onRequest.get()).isFalse();
		assertThat(onResponse.get()).isTrue();
		assertThat(onDisconnected.get()).isFalse();
		assertThat(timeout.get()).isEqualTo(expectedTimeout);

		Thread.sleep(expectedTimeout + 50);

		StepVerifier.create(response)
		            .expectNext("testResponseTimeout")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(onRequest.get()).isFalse();
		assertThat(onResponse.get()).isTrue();
		assertThat(onDisconnected.get()).isFalse();
		assertThat(timeout.get()).isEqualTo(expectedTimeout);
	}

	@Test
	void testSniSupport() throws Exception {
		X509Bundle defaultCert = new CertificateBuilder().subject("CN=default").setIsCertificateAuthority(true).buildSelfSigned();
		X509Bundle testCert = new CertificateBuilder().subject("CN=test.com").setIsCertificateAuthority(true).buildSelfSigned();

		AtomicReference<String> hostname = new AtomicReference<>();

		Http3SslContextSpec defaultSslContextBuilder = Http3SslContextSpec.forServer(defaultCert.toTempPrivateKeyPem(), null, defaultCert.toTempCertChainPem());
		Http3SslContextSpec testSslContextBuilder = Http3SslContextSpec.forServer(testCert.toTempPrivateKeyPem(), null, testCert.toTempCertChainPem());

		disposableServer =
				createServer().port(8080)
				              .secure(spec -> spec.sslContext(defaultSslContextBuilder)
				                                  .addSniMapping("*.test.com", domainSpec -> domainSpec.sslContext(testSslContextBuilder)))
				              .doOnChannelInit((obs, channel, remoteAddress) ->
				                  channel.pipeline()
				                         .addLast(new ChannelInboundHandlerAdapter() {
				                               @Override
				                               public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
				                                 if (evt instanceof SniCompletionEvent) {
				                                     hostname.set(((SniCompletionEvent) evt).hostname());
				                                 }
				                                 ctx.fireUserEventTriggered(evt);
				                             }
				                           }))
				              .handle((req, res) -> res.sendString(Mono.just("testSniSupport")))
				              .bindNow();

		Http3SslContextSpec clientSslContextBuilder =
				Http3SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		createClient(disposableServer.port())
		         .secure(spec -> spec.sslContext(clientSslContextBuilder)
		                             .serverNames(new SNIHostName("test.com")))
		         .get()
		         .uri("/")
		         .responseContent()
		         .aggregate()
		         .block(Duration.ofSeconds(30));

		assertThat(hostname.get()).isNotNull();
		assertThat(hostname.get()).isEqualTo("test.com");
	}

	@Test
	void testTrailerHeadersChunkedResponse() throws Exception {
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
	void testTrailerHeadersDisallowedNotSent() throws Exception {
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
	void testTrailerHeadersFailedChunkedResponse() throws Exception {
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
	void testTrailerHeadersFullResponseSend() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set("foo", "bar"))
				               .send())
				        .bindNow();

		doTestTrailerHeaders(createClient(disposableServer.port()), "bar", "empty");
	}

	@Test
	void testTrailerHeadersFullResponseSendFluxContentAlwaysEmpty() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set("foo", "bar"))
				               .status(HttpResponseStatus.NO_CONTENT)
				               .sendString(Flux.just("test", "Trailer", "Headers", "Full", "Response")))
				        .bindNow();

		doTestTrailerHeaders(createClient(disposableServer.port()), "bar", "empty");
	}

	@Test
	void testTrailerHeadersFullResponseSendFluxContentLengthZero() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .header(HttpHeaderNames.CONTENT_LENGTH, "0")
				               .trailerHeaders(h -> h.set("foo", "bar"))
				               .sendString(Flux.just("test", "Trailer", "Headers", "Full", "Response")))
				        .bindNow();

		doTestTrailerHeaders(createClient(disposableServer.port()), "bar", "empty");
	}

	@Test
	void testTrailerHeadersFullResponseSendHeaders() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set("foo", "bar"))
				               .sendHeaders())
				        .bindNow();

		doTestTrailerHeaders(createClient(disposableServer.port()), "bar", "empty");
	}

	@Test
	void testTrailerHeadersFullResponseSendMono() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set("foo", "bar"))
				               .sendString(Mono.just("testTrailerHeadersFullResponse")))
				        .bindNow();

		doTestTrailerHeaders(createClient(disposableServer.port()), "bar", "testTrailerHeadersFullResponse");
	}

	@Test
	void testTrailerHeadersFullResponseSendMonoEmpty() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) -> {
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set("foo", "bar"));
				            return Mono.empty();
				        })
				        .bindNow();

		doTestTrailerHeaders(createClient(disposableServer.port()), "bar", "empty");
	}

	@Test
	void testTrailerHeadersFullResponseSendObject() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, "foo")
				               .trailerHeaders(h -> h.set("foo", "bar"))
				               .sendObject(Unpooled.wrappedBuffer("testTrailerHeadersFullResponse".getBytes(Charset.defaultCharset()))))
				        .bindNow();

		doTestTrailerHeaders(createClient(disposableServer.port()), "bar", "testTrailerHeadersFullResponse");
	}

	@Test
	void testTrailerHeadersNotSpecifiedUpfront() throws Exception {
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

	@Test
	void testTrailerHeadersPseudoHeaderNotAllowed() throws Exception {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				            res.header(HttpHeaderNames.TRAILER, ":protocol")
				               .trailerHeaders(h -> h.set(":protocol", "test"))
				               .sendString(Flux.just("testTrailerHeaders", "PseudoHeaderNotAllowed")))
				        .bindNow();

		// Trailers MUST NOT include pseudo-header fields
		doTestTrailerHeaders(createClient(disposableServer.port()), "empty", "testTrailerHeadersPseudoHeaderNotAllowed");
	}

	private static void doTestTrailerHeaders(HttpClient client, String expectedHeaderValue, String expectedResponse) {
		client.get()
		      .uri("/")
		      .responseSingle((res, bytes) -> bytes.asString().defaultIfEmpty("empty").zipWith(res.trailerHeaders()))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> expectedResponse.equals(t.getT1()) &&
		              expectedHeaderValue.equals(t.getT2().get("foo", "empty")))
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
	}

	@Test
	void clientSendsError() throws Exception {
		TestHttpMeterRegistrarAdapter metricsRegistrar = new TestHttpMeterRegistrarAdapter();

		ConnectionProvider provider =
				ConnectionProvider.builder("clientSendsError")
				                  .maxConnections(1)
				                  .metrics(true, () -> metricsRegistrar)
				                  .build();
		try {
			disposableServer =
					createServer()
					        .handle((req, res) -> Mono.empty())
					        .bindNow();

			Mono<String> content =
					createClient(provider, disposableServer.port())
					        .post()
					        .uri("/")
					        .send(Mono.error(new RuntimeException("clientSendsError")))
					        .responseContent()
					        .aggregate()
					        .asString();

			List<Signal<String>> result =
					Flux.range(1, 3)
					    .flatMapDelayError(i -> content, 256, 32)
					    .materialize()
					    .collectList()
					    .block(Duration.ofSeconds(10));

			assertThat(result)
					.isNotNull()
					.hasSize(1)
					.allMatch(Signal::hasError);
			Throwable error = result.get(0).getThrowable();
			assertThat(error).isNotNull();
			assertThat(error.getSuppressed())
					.isNotNull()
					.hasSize(3)
					.allMatch(throwable -> "clientSendsError".equals(throwable.getMessage()));

			HttpConnectionPoolMetrics metrics = metricsRegistrar.metrics;
			assertThat(metrics).isNotNull();
			assertThat(metrics.activeStreamSize()).isEqualTo(0);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void clientDropsEmptyFileChunked() throws Exception {
		Path path = Files.createTempFile("empty", ".txt");
		path.toFile().deleteOnExit();

		clientDropsFile((req, out) -> out.sendFileChunked(path, 0, 0));
	}

	@Test
	void clientDropsEmptyFileDefault() throws Exception {
		Path path = Files.createTempFile("empty", ".txt");
		path.toFile().deleteOnExit();

		clientDropsFile((req, out) -> out.sendFile(path));
	}

	@Test
	void clientDropsFileChunked() throws Exception {
		Path path = Paths.get(getClass().getResource("/largeFile.txt").toURI());

		clientDropsFile((req, out) -> out.sendFileChunked(path, 0, 0));
	}

	@Test
	void clientDropsFileDefault() throws Exception {
		Path path = Paths.get(getClass().getResource("/largeFile.txt").toURI());

		clientDropsFile((req, out) -> out.sendFile(path));
	}

	private void clientDropsFile(BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>> sender) throws Exception {
		disposableServer =
				createServer()
				        .route(r -> r.post("/", (req, res) ->
				            res.sendString(req.receive()
				                              .aggregate()
				                              .asString()
				                              .defaultIfEmpty("empty"))))
				        .bindNow();

		createClient(disposableServer.port())
		        .headers(h -> h.set(HttpHeaderNames.CONTENT_LENGTH, "0"))
		        .post()
		        .uri("/")
		        .send(sender)
		        .responseSingle((res, buf) -> buf.asString())
		        .as(StepVerifier::create)
		        .expectNext("empty")
		        .expectComplete()
		        .verify(Duration.ofSeconds(5));
	}

	@Test
	void serverDropsEmptyFileChunked() throws Exception {
		Path path = Files.createTempFile("empty", ".txt");
		path.toFile().deleteOnExit();

		serverDropsFile((req, res) -> res.header(HttpHeaderNames.CONTENT_LENGTH, "0").sendFileChunked(path, 0, 0));
	}

	@Test
	void serverDropsEmptyFileDefault() throws Exception {
		Path path = Files.createTempFile("empty", ".txt");
		path.toFile().deleteOnExit();

		serverDropsFile((req, res) -> res.header(HttpHeaderNames.CONTENT_LENGTH, "0").sendFile(path));
	}

	@Test
	void serverDropsFileChunked() throws Exception {
		Path path = Paths.get(getClass().getResource("/largeFile.txt").toURI());

		serverDropsFile((req, res) -> res.header(HttpHeaderNames.CONTENT_LENGTH, "0").sendFileChunked(path, 0, 0));
	}

	@Test
	void serverDropsFileDefault() throws Exception {
		Path path = Paths.get(getClass().getResource("/largeFile.txt").toURI());

		serverDropsFile((req, res) -> res.header(HttpHeaderNames.CONTENT_LENGTH, "0").sendFile(path));
	}

	private void serverDropsFile(BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> sender) throws Exception {
		disposableServer =
				createServer()
				        .route(r -> r.get("/", sender))
				        .bindNow();

		createClient(disposableServer.port())
		        .get()
		        .uri("/")
		        .responseSingle((res, buf) -> buf.asString().defaultIfEmpty("empty"))
		        .as(StepVerifier::create)
		        .expectNext("empty")
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

	static HttpServer createServer() throws Exception {
		Http3SslContextSpec serverCtx = Http3SslContextSpec.forServer(ssc.toTempPrivateKeyPem(), null, ssc.toTempCertChainPem());
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

	static final class TestHttpMeterRegistrarAdapter extends HttpMeterRegistrarAdapter {
		HttpConnectionPoolMetrics metrics;

		@Override
		protected void registerMetrics(String poolName, String id, SocketAddress remoteAddress, HttpConnectionPoolMetrics metrics) {
			this.metrics = metrics;
		}
	}
}
