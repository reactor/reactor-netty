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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static reactor.netty.Metrics.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
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

/**
 * @author Violeta Georgieva
 */
public class HttpMetricsHandlerTests {
	HttpServer httpServer;
	DisposableServer disposableServer;
	private ConnectionProvider provider;
	HttpClient httpClient;
	private MeterRegistry registry;
	
	final Flux<ByteBuf> body = ByteBufFlux.fromString(Flux.just("Hello", " ", "World", "!")).delayElements(Duration.ofMillis(10));

	@Before
	public void setUp() {
		httpServer = customizeServerOptions(
				HttpServer.create()
				          .host("127.0.0.1")
				          .port(0)
				          .metrics(true)
				          .route(r -> r.post("/1", (req, res) -> res.header("Connection", "close")
				                                                    .send(req.receive().retain().delayElements(Duration.ofMillis(10))))
				                       .post("/2", (req, res) -> res.header("Connection", "close")
				                                                    .send(req.receive().retain().delayElements(Duration.ofMillis(10))))));

		provider = ConnectionProvider.create("HttpMetricsHandlerTests", 1);
		httpClient =
				customizeClientOptions(HttpClient.create(provider)
				                                 .addressSupplier(() -> disposableServer.address())
				                                 .metrics(true));

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@After
	public void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));

		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@Test
	public void testExistingEndpoint() throws Exception {
		disposableServer = httpServer.bindNow();

		AtomicReference<SocketAddress> clientAddress = new AtomicReference<>();
		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = httpClient.doAfterRequest((req, conn) -> {
			clientAddress.set(conn.channel().localAddress());
			serverAddress.set(conn.channel().remoteAddress());
		});

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

		assertThat(latch1.await(30, TimeUnit.SECONDS)).isTrue();

		InetSocketAddress ca = (InetSocketAddress) clientAddress.get();
		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("/1", ca.getHostString() + ":" + ca.getPort(),
				sa.getHostString() + ":" + sa.getPort(), 1);

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

		assertThat(latch2.await(30, TimeUnit.SECONDS)).isTrue();

		ca = (InetSocketAddress) clientAddress.get();
		sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsExisting("/2", ca.getHostString() + ":" + ca.getPort(),
				sa.getHostString() + ":" + sa.getPort(), 2);
	}

	@Test
	public void testNonExistingEndpoint() throws Exception {
		disposableServer = httpServer.bindNow();

		AtomicReference<SocketAddress> clientAddress = new AtomicReference<>();
		AtomicReference<SocketAddress> serverAddress = new AtomicReference<>();
		httpClient = httpClient.doAfterRequest((req, conn) -> {
			clientAddress.set(conn.channel().localAddress());
			serverAddress.set(conn.channel().remoteAddress());
		});

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

		assertThat(latch1.await(30, TimeUnit.SECONDS)).isTrue();

		InetSocketAddress ca = (InetSocketAddress) clientAddress.get();
		InetSocketAddress sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsNonExisting(ca.getHostString() + ":" + ca.getPort(),
				sa.getHostString() + ":" + sa.getPort(), 1);

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

		assertThat(latch2.await(30, TimeUnit.SECONDS)).isTrue();

		ca = (InetSocketAddress) clientAddress.get();
		sa = (InetSocketAddress) serverAddress.get();

		Thread.sleep(1000);
		checkExpectationsNonExisting(ca.getHostString() + ":" + ca.getPort(),
				sa.getHostString() + ":" + sa.getPort(), 2);
	}

	private void checkExpectationsExisting(String uri, String clientAddress, String serverAddress, int index) {
		String[] timerTags1 = new String[] {URI, uri, METHOD, "POST", STATUS, "200"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "POST"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, clientAddress, STATUS, "SUCCESS"};
		String[] summaryTags1 = new String[] {REMOTE_ADDRESS, clientAddress, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, clientAddress, URI, "http"};

		checkTimer(SERVER_RESPONSE_TIME, timerTags1, 1);
		checkTimer(SERVER_DATA_SENT_TIME, timerTags1, 1);
		checkTimer(SERVER_DATA_RECEIVED_TIME, timerTags2, 1);
		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags3, 1);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags1, 1, 12);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags1, 1, 12);
		checkCounter(SERVER_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags2, 14, 84);
		//checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags2, true, 2*index, 151*index);
		checkCounter(SERVER_ERRORS, summaryTags2, false, 0);

		timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "POST", STATUS, "200"};
		timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "POST"};
		timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri};
		summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "http"};

		checkTimer(CLIENT_RESPONSE_TIME, timerTags1, 1);
		checkTimer(CLIENT_DATA_SENT_TIME, timerTags2, 1);
		checkTimer(CLIENT_DATA_RECEIVED_TIME, timerTags1, 1);
		checkTimer(CLIENT_CONNECT_TIME, timerTags3, index);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags3, index);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags1, 1, 12);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags1, 1, 12);
		checkCounter(CLIENT_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags2, 14*index, 151*index);
		//checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, true, 3*index, 84*index);
		checkCounter(CLIENT_ERRORS, summaryTags2, false, 0);
	}

	private void checkExpectationsNonExisting(String clientAddress, String serverAddress, int index) {
		String uri = "/3";
		String[] timerTags1 = new String[] {URI, uri, METHOD, "GET", STATUS, "404"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "GET"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, clientAddress, STATUS, "SUCCESS"};
		String[] summaryTags1 = new String[] {REMOTE_ADDRESS, clientAddress, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, clientAddress, URI, "http"};

		checkTimer(SERVER_RESPONSE_TIME, timerTags1, index);
		checkTimer(SERVER_DATA_SENT_TIME, timerTags1, index);
		checkTimer(SERVER_DATA_RECEIVED_TIME, timerTags2, index);
		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags3, 1);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags1, 1, 0);
		checkCounter(SERVER_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags2, 1, 64);
		checkCounter(SERVER_ERRORS, summaryTags2, false, 0);

		timerTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "GET", STATUS, "404"};
		timerTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri, METHOD, "GET"};
		timerTags3 = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		summaryTags1 = new String[] {REMOTE_ADDRESS, serverAddress, URI, uri};
		summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "http"};

		checkTimer(CLIENT_RESPONSE_TIME, timerTags1, index);
		checkTimer(CLIENT_DATA_SENT_TIME, timerTags2, index);
		checkTimer(CLIENT_DATA_RECEIVED_TIME, timerTags1, index);
		checkTimer(CLIENT_CONNECT_TIME, timerTags3, index);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags3, index);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags1, index, 0);
		checkCounter(CLIENT_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags2, index, 123*index);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, index, 64*index);
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
		assertNotNull(timer);
		assertEquals(expectedCount, timer.count());
		assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
	}

	private void checkDistributionSummary(String name, String[] tags, long expectedCount, double expectedAmount) {
		DistributionSummary summary = registry.find(name).tags(tags).summary();
		assertNotNull(summary);
		assertEquals(expectedCount, summary.count());
		assertTrue(summary.totalAmount() >= expectedAmount);
	}

	void checkCounter(String name, String[] tags, boolean exists, double expectedCount) {
		Counter counter = registry.find(name).tags(tags).counter();
		if (exists) {
			assertNotNull(counter);
			assertEquals(expectedCount, counter.count(), 0.0);
		}
		else {
			assertNull(counter);
		}
	}


	private static final String SERVER_RESPONSE_TIME = HTTP_SERVER_PREFIX + RESPONSE_TIME;
	private static final String SERVER_DATA_SENT_TIME = HTTP_SERVER_PREFIX + DATA_SENT_TIME;
	private static final String SERVER_DATA_RECEIVED_TIME = HTTP_SERVER_PREFIX + DATA_RECEIVED_TIME;
	private static final String SERVER_DATA_SENT = HTTP_SERVER_PREFIX + DATA_SENT;
	private static final String SERVER_DATA_RECEIVED = HTTP_SERVER_PREFIX + DATA_RECEIVED;
	private static final String SERVER_ERRORS = HTTP_SERVER_PREFIX + ERRORS;
	private static final String SERVER_TLS_HANDSHAKE_TIME = HTTP_SERVER_PREFIX + TLS_HANDSHAKE_TIME;

	private static final String CLIENT_RESPONSE_TIME = HTTP_CLIENT_PREFIX + RESPONSE_TIME;
	private static final String CLIENT_DATA_SENT_TIME = HTTP_CLIENT_PREFIX + DATA_SENT_TIME;
	private static final String CLIENT_DATA_RECEIVED_TIME = HTTP_CLIENT_PREFIX + DATA_RECEIVED_TIME;
	private static final String CLIENT_DATA_SENT = HTTP_CLIENT_PREFIX + DATA_SENT;
	private static final String CLIENT_DATA_RECEIVED = HTTP_CLIENT_PREFIX + DATA_RECEIVED;
	static final String CLIENT_ERRORS = HTTP_CLIENT_PREFIX + ERRORS;
	private static final String CLIENT_CONNECT_TIME = HTTP_CLIENT_PREFIX + CONNECT_TIME;
	private static final String CLIENT_TLS_HANDSHAKE_TIME = HTTP_CLIENT_PREFIX + TLS_HANDSHAKE_TIME;
}
