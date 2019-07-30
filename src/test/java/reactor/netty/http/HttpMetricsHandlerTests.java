/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * @author Violeta Georgieva
 */
public class HttpMetricsHandlerTests {
	private HttpServer httpServer;
	private DisposableServer disposableServer;
	private ConnectionProvider provider;
	private HttpClient httpClient;
	private MeterRegistry registry;

	@Before
	public void setUp() {
		httpServer = customizeServerOptions(
				HttpServer.create()
				          .host("127.0.0.1")
				          .port(0)
				          .metrics(true)
				          .route(r -> r.post("/1", (req, res) -> res.send(req.receive().retain()))
				                       .post("/2", (req, res) -> res.send(req.receive().retain()))));

		provider = ConnectionProvider.fixed("test", 1);
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
		disposableServer = customizeServerOptions(httpServer).bindNow();

		StepVerifier.create(httpClient.post()
		                              .uri("/1")
		                              .send(ByteBufFlux.fromString(Flux.just("Hello", " ", "World", "!")))
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(httpClient.post()
		                              .uri("/2")
		                              .send(ByteBufFlux.fromString(Flux.just("Hello", " ", "World", "!")))
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectNext("Hello World!")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		Thread.sleep(5000);
		checkExpectationsExisting("/1");
		checkExpectationsExisting("/2");
	}

	@Test
	public void testNonExistingEndpoint() throws Exception {
		disposableServer = customizeServerOptions(httpServer).bindNow();

		StepVerifier.create(httpClient.post()
		                              .uri("/3")
		                              .send(ByteBufFlux.fromString(Flux.just("Hello", " ", "World", "!")))
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(httpClient.post()
		                              .uri("/3")
		                              .send(ByteBufFlux.fromString(Flux.just("Hello", " ", "World", "!")))
		                              .responseContent()
		                              .aggregate()
		                              .asString())
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		Thread.sleep(5000);
		checkExpectationsNonExisting();
	}

	private void checkExpectationsExisting(String uri) {
		String address = disposableServer.address().getHostString();
		String[] timerTags1 = new String[] {URI, uri, METHOD, "POST", STATUS, "200"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "POST"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, address, STATUS, "SUCCESS"};
		String[] summaryTags1 = new String[] {REMOTE_ADDRESS, address, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, address, URI, "tcp"};

		checkTimer(SERVER_RESPONSE_TIME, timerTags1, true, 1);
		checkTimer(SERVER_DATA_SENT_TIME, timerTags1, true, 1);
		checkTimer(SERVER_DATA_RECEIVED_TIME, timerTags2, true, 1);
		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags3, true, 1);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags1, true, 1, 12);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags1, true, 1, 12);
		checkCounter(SERVER_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags2, true, 28, 168);
		//checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags2, true, 6, 292);
		checkCounter(SERVER_ERRORS, summaryTags2, true, 0);

		timerTags1 = new String[] {REMOTE_ADDRESS, address, URI, uri, METHOD, "POST", STATUS, "200"};
		timerTags2 = new String[] {REMOTE_ADDRESS, address, URI, uri, METHOD, "POST"};

		checkTimer(CLIENT_RESPONSE_TIME, timerTags1, true, 1);
		checkTimer(CLIENT_DATA_SENT_TIME, timerTags2, true, 1);
		checkTimer(CLIENT_DATA_RECEIVED_TIME, timerTags1, true, 1);
		checkTimer(CLIENT_CONNECT_TIME, timerTags3, true, 1);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags3, true, 1);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags1, true, 1, 12);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags1, true, 1, 12);
		checkCounter(CLIENT_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags2, true, 28, 292);
		//checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, true, 6, 168);
		checkCounter(CLIENT_ERRORS, summaryTags2, true, 0);
	}

	private void checkExpectationsNonExisting() {
		String uri = "/3";
		String address = disposableServer.address().getHostString();
		String[] timerTags1 = new String[] {URI, uri, METHOD, "POST", STATUS, "404"};
		String[] timerTags2 = new String[] {URI, uri, METHOD, "POST"};
		String[] timerTags3 = new String[] {REMOTE_ADDRESS, address, STATUS, "SUCCESS"};
		String[] summaryTags1 = new String[] {REMOTE_ADDRESS, address, URI, uri};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, address, URI, "tcp"};

		checkTimer(SERVER_RESPONSE_TIME, timerTags1, true, 2);
		checkTimer(SERVER_DATA_SENT_TIME, timerTags1, true, 2);
		checkTimer(SERVER_DATA_RECEIVED_TIME, timerTags2, true, 2);
		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags3, true, 1);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags1, true, 2, 0);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags1, true, 2, 0);
		checkCounter(SERVER_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags2, true, 2, 90);
		//checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags2, true, 6, 292);
		checkCounter(SERVER_ERRORS, summaryTags2, true, 0);

		timerTags1 = new String[] {REMOTE_ADDRESS, address, URI, uri, METHOD, "POST", STATUS, "404"};
		timerTags2 = new String[] {REMOTE_ADDRESS, address, URI, uri, METHOD, "POST"};

		checkTimer(CLIENT_RESPONSE_TIME, timerTags1, true, 2);
		checkTimer(CLIENT_DATA_SENT_TIME, timerTags2, true, 2);
		checkTimer(CLIENT_DATA_RECEIVED_TIME, timerTags1, true, 2);
		checkTimer(CLIENT_CONNECT_TIME, timerTags3, true, 1);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags3, true, 1);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags1, true, 2, 24);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags1, true, 2, 0);
		checkCounter(CLIENT_ERRORS, summaryTags1, false, 0);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags2, true, 28, 292);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2, true, 2, 90);
		checkCounter(CLIENT_ERRORS, summaryTags2, true, 0);
	}


	protected HttpServer customizeServerOptions(HttpServer httpServer) {
		return httpServer;
	}

	protected HttpClient customizeClientOptions(HttpClient httpClient) {
		return httpClient;
	}

	protected void checkTlsTimer(String name, String[] tags, boolean exists, long expectedCount) {
		//no-op
	}

	void checkTimer(String name, String[] tags, boolean exists, long expectedCount) {
		Timer timer = registry.find(name).tags(tags).timer();
		if (exists) {
			assertNotNull(timer);
			assertEquals(expectedCount, timer.count());
			assertTrue(timer.totalTime(TimeUnit.SECONDS) > 0);
		}
		else {
			assertNull(timer);
		}
	}

	private void checkDistributionSummary(String name, String[] tags, boolean exists, long expectedCount, double expectedAmound) {
		DistributionSummary summary = registry.find(name).tags(tags).summary();
		if (exists) {
			assertNotNull(summary);
			assertEquals(expectedCount, summary.count());
			assertTrue(summary.totalAmount() >= expectedAmound);
		}
		else {
			assertNull(summary);
		}
	}

	private void checkCounter(String name, String[] tags, boolean exists, double expectedCount) {
		Counter counter = registry.find(name).tags(tags).counter();
		if (exists) {
			assertNotNull(counter);
			assertEquals(expectedCount, counter.count(), 0.0);
		}
		else {
			assertNull(counter);
		}
	}


	private static final String SERVER_METRICS_NAME = "reactor.netty.http.server";
	private static final String SERVER_RESPONSE_TIME = SERVER_METRICS_NAME + RESPONSE_TIME;
	private static final String SERVER_DATA_SENT_TIME = SERVER_METRICS_NAME + DATA_SENT_TIME;
	private static final String SERVER_DATA_RECEIVED_TIME = SERVER_METRICS_NAME + DATA_RECEIVED_TIME;
	private static final String SERVER_DATA_SENT = SERVER_METRICS_NAME + DATA_SENT;
	private static final String SERVER_DATA_RECEIVED = SERVER_METRICS_NAME + DATA_RECEIVED;
	private static final String SERVER_ERRORS = SERVER_METRICS_NAME + ERRORS;
	private static final String SERVER_TLS_HANDSHAKE_TIME = SERVER_METRICS_NAME + TLS_HANDSHAKE_TIME;

	private static final String CLIENT_METRICS_NAME = "reactor.netty.http.client";
	private static final String CLIENT_RESPONSE_TIME = CLIENT_METRICS_NAME + RESPONSE_TIME;
	private static final String CLIENT_DATA_SENT_TIME = CLIENT_METRICS_NAME + DATA_SENT_TIME;
	private static final String CLIENT_DATA_RECEIVED_TIME = CLIENT_METRICS_NAME + DATA_RECEIVED_TIME;
	private static final String CLIENT_DATA_SENT = CLIENT_METRICS_NAME + DATA_SENT;
	private static final String CLIENT_DATA_RECEIVED = CLIENT_METRICS_NAME + DATA_RECEIVED;
	private static final String CLIENT_ERRORS = CLIENT_METRICS_NAME + ERRORS;
	private static final String CLIENT_CONNECT_TIME = CLIENT_METRICS_NAME + CONNECT_TIME;
	private static final String CLIENT_TLS_HANDSHAKE_TIME = CLIENT_METRICS_NAME + TLS_HANDSHAKE_TIME;
}
