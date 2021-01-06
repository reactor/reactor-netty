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
package reactor.netty.http;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.HTTP_CLIENT_PREFIX;
import static reactor.netty.Metrics.HTTP_SERVER_PREFIX;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.URI;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * @author Violeta Georgieva
 */
class HttpMetricsHandlerTests extends BaseHttpTest {
	HttpServer httpServer;
	private ConnectionProvider provider;
	HttpClient httpClient;
	private MeterRegistry registry;

	final Flux<ByteBuf> body = ByteBufFlux.fromString(Flux.just("Hello", " ", "World", "!")).delayElements(Duration.ofMillis(10));

	@BeforeEach
	void setUp() {
		httpServer = customizeServerOptions(
				createServer()
				          .host("127.0.0.1")
				          .metrics(true, Function.identity())
				          .route(r -> r.post("/1", (req, res) -> res.header("Connection", "close")
				                                                    .send(req.receive().retain().delayElements(Duration.ofMillis(10))))
				                       .post("/2", (req, res) -> res.header("Connection", "close")
				                                                    .send(req.receive().retain().delayElements(Duration.ofMillis(10))))));

		provider = ConnectionProvider.create("HttpMetricsHandlerTests", 1);
		httpClient =
				customizeClientOptions(createClient(provider, () -> disposableServer.address())
				                                 .metrics(true, Function.identity()));

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
		provider.disposeLater()
		        .block(Duration.ofSeconds(30));

		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@Test
	void testExistingEndpoint() throws Exception {
		disposableServer = httpServer.bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = httpClient.doAfterRequest((req, conn) ->
			serverAddress.set(conn.channel().remoteAddress())
		);

		CountDownLatch latch1 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch1.countDown()))
		                              .post()
		                              .uri("/1")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("/1", sa.getHostString() + ":" + sa.getPort(), 1);

		CountDownLatch latch2 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch2.countDown()))
		                              .post()
		                              .uri("/2?i=1&j=2")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch2.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("/2", sa.getHostString() + ":" + sa.getPort(), 2);
	}

	@Test
	void testNonExistingEndpoint() throws Exception {
		disposableServer = httpServer.bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = httpClient.doAfterRequest((req, conn) ->
			serverAddress.set(conn.channel().remoteAddress())
		);

		CountDownLatch latch1 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch1.countDown()))
		                              .headers(h -> h.add("Connection", "close"))
		                              .get()
		                              .uri("/3")
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsNonExisting(sa.getHostString() + ":" + sa.getPort(), 1);

		CountDownLatch latch2 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch2.countDown()))
		                              .headers(h -> h.add("Connection", "close"))
		                              .get()
		                              .uri("/3")
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch2.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsNonExisting(sa.getHostString() + ":" + sa.getPort(), 2);
	}

	@Test
	void testUriTagValueFunction() throws Exception {
		disposableServer = httpServer.metrics(true, s -> "testUriTagValueResolver").bindNow();

		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = httpClient.doAfterRequest((req, conn) ->
			serverAddress.set(conn.channel().remoteAddress())
		);

		CountDownLatch latch1 = new CountDownLatch(1);
		StepVerifier.create(httpClient.doOnResponse((res, conn) ->
		                                  conn.channel()
		                                      .closeFuture()
		                                      .addListener(f -> latch1.countDown()))
		                              .metrics(true, s -> "testUriTagValueResolver")
		                              .post()
		                              .uri("/1")
		                              .send(body)
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("testUriTagValueResolver", sa.getHostString() + ":" + sa.getPort(), 1);
	}

	private void checkExpectationsExisting(String uri, String serverAddress, int index) {
		String[] timerTags1 = new String[] {URI, uri, METHOD, "POST", STATUS, "200"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "POST"};
		String[] summaryTags1 = new String[] {URI, uri};

		checkTimer(SERVER_RESPONSE_TIME, timerTags1, 1);
		checkTimer(SERVER_DATA_SENT_TIME, timerTags1, 1);
		checkTimer(SERVER_DATA_RECEIVED_TIME, timerTags2, 1);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags1, 1, 12);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags1, 1, 12);
		checkCounter(SERVER_ERRORS, summaryTags1, false, 0);

		timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "POST", STATUS, "200"};
		timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "POST"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "http"};

		checkTimer(CLIENT_RESPONSE_TIME, timerTags1, 1);
		checkTimer(CLIENT_DATA_SENT_TIME, timerTags2, 1);
		checkTimer(CLIENT_DATA_RECEIVED_TIME, timerTags1, 1);
		checkTimer(CLIENT_CONNECT_TIME, timerTags3, index);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags3, index);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags1, 1, 12);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags1, 1, 12);
		checkCounter(CLIENT_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags2, 14 * index, 151 * index);
		//checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, true, 3*index, 84*index);
		checkCounter(CLIENT_ERRORS, summaryTags2, false, 0);
	}

	private void checkExpectationsNonExisting(String serverAddress, int index) {
		String uri = "/3";
		String[] timerTags1 = new String[] {URI, uri, METHOD, "GET", STATUS, "404"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "GET"};
		String[] summaryTags1 = new String[] {URI, uri};

		checkTimer(SERVER_RESPONSE_TIME, timerTags1, index);
		checkTimer(SERVER_DATA_SENT_TIME, timerTags1, index);
		checkTimer(SERVER_DATA_RECEIVED_TIME, timerTags2, index);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags1, index, 0);
		checkCounter(SERVER_ERRORS, summaryTags1, false, 0);

		timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "GET", STATUS, "404"};
		timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "GET"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "http"};

		checkTimer(CLIENT_RESPONSE_TIME, timerTags1, index);
		checkTimer(CLIENT_DATA_SENT_TIME, timerTags2, index);
		checkTimer(CLIENT_DATA_RECEIVED_TIME, timerTags1, index);
		checkTimer(CLIENT_CONNECT_TIME, timerTags3, index);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags3, index);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags1, index, 0);
		checkCounter(CLIENT_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags2, index, 123 * index);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, index, 64 * index);
		checkCounter(CLIENT_ERRORS, summaryTags2, false, 0);
	}


	protected HttpServer customizeServerOptions(HttpServer httpServer) {
		return httpServer;
	}

	protected HttpClient customizeClientOptions(HttpClient httpClient) {
		return httpClient;
	}

	protected void checkTlsTimer(String name, String[] tags, long expectedCount) {
		//no-op
	}

	void checkTimer(String name, String[] tags, long expectedCount) {
		Timer timer = registry.find(name).tags(tags).timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(expectedCount);
		assertThat(timer.totalTime(TimeUnit.NANOSECONDS) > 0).isTrue();
	}

	private void checkDistributionSummary(String name, String[] tags, long expectedCount, double expectedAmount) {
		DistributionSummary summary = registry.find(name).tags(tags).summary();
		assertThat(summary).isNotNull();
		assertThat(summary.count()).isEqualTo(expectedCount);
		assertThat(summary.totalAmount() >= expectedAmount).isTrue();
	}

	void checkCounter(String name, String[] tags, boolean exists, double expectedCount) {
		Counter counter = registry.find(name).tags(tags).counter();
		if (exists) {
			assertThat(counter).isNotNull();
			assertThat(counter.count() >= expectedCount).isTrue();
		}
		else {
			assertThat(counter).isNull();
		}
	}


	private static final String SERVER_RESPONSE_TIME = HTTP_SERVER_PREFIX + RESPONSE_TIME;
	private static final String SERVER_DATA_SENT_TIME = HTTP_SERVER_PREFIX + DATA_SENT_TIME;
	private static final String SERVER_DATA_RECEIVED_TIME = HTTP_SERVER_PREFIX + DATA_RECEIVED_TIME;
	private static final String SERVER_DATA_SENT = HTTP_SERVER_PREFIX + DATA_SENT;
	private static final String SERVER_DATA_RECEIVED = HTTP_SERVER_PREFIX + DATA_RECEIVED;
	private static final String SERVER_ERRORS = HTTP_SERVER_PREFIX + ERRORS;

	private static final String CLIENT_RESPONSE_TIME = HTTP_CLIENT_PREFIX + RESPONSE_TIME;
	private static final String CLIENT_DATA_SENT_TIME = HTTP_CLIENT_PREFIX + DATA_SENT_TIME;
	private static final String CLIENT_DATA_RECEIVED_TIME = HTTP_CLIENT_PREFIX + DATA_RECEIVED_TIME;
	private static final String CLIENT_DATA_SENT = HTTP_CLIENT_PREFIX + DATA_SENT;
	private static final String CLIENT_DATA_RECEIVED = HTTP_CLIENT_PREFIX + DATA_RECEIVED;
	static final String CLIENT_ERRORS = HTTP_CLIENT_PREFIX + ERRORS;
	private static final String CLIENT_CONNECT_TIME = HTTP_CLIENT_PREFIX + CONNECT_TIME;
	private static final String CLIENT_TLS_HANDSHAKE_TIME = HTTP_CLIENT_PREFIX + TLS_HANDSHAKE_TIME;
}
