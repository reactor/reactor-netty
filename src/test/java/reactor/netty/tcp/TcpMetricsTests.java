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
package reactor.netty.tcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static reactor.netty.Metrics.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.resources.ConnectionProvider;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author Violeta Georgieva
 */
public class TcpMetricsTests {
	TcpServer tcpServer;
	DisposableServer disposableServer;
	TcpClient tcpClient;
	Connection connection;
	private ConnectionProvider provider;
	private MeterRegistry registry;

	@Before
	public void setUp() {
		tcpServer =
				TcpServer.create()
				         .host("127.0.0.1")
				         .port(0)
				         .metrics(true, null);

		provider = ConnectionProvider.fixed("test", 1);
		tcpClient =
				TcpClient.create(provider)
				         .addressSupplier(() -> disposableServer.address())
				         .metrics(true, null);

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@After
	public void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}

		if (connection != null) {
			connection.disposeNow();
		}

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));

		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@Test
	public void testSuccessfulCommunication() throws Exception {
		CountDownLatch latch = new CountDownLatch(2);
		disposableServer =
				customizeServerOptions(tcpServer)
				        .handle((in, out) -> {
				            in.receive()
				              .asString()
				              .subscribe(s -> {
				                if ("hello".equals(s)) {
				                    latch.countDown();
				                }
				            });
				            return out.sendString(Mono.just("hello"))
				                      .neverComplete();
				        })
				        .bindNow();

		connection = customizeClientOptions(tcpClient).connectNow();

		connection.outbound()
		          .sendString(Mono.just("hello"))
		          .neverComplete()
		          .subscribe();

		connection.inbound()
		          .receive()
		          .asString()
		          .subscribe(s -> {
		              if ("hello".equals(s)) {
		                  latch.countDown();
		              }
		          });

		assertTrue(latch.await(30, TimeUnit.SECONDS));

		checkExpectationsPositive();
	}

	@Test
	@Ignore
	public void testFailedConnect() {
		disposableServer = customizeServerOptions(tcpServer).bindNow();

		int port = SocketUtils.findAvailableTcpPort();
		try {
			connection = customizeClientOptions(tcpClient)
			                     .host("localhost")
			                     .port(port)
			                     .connectNow();
			fail("Connect should fail.");
		}
		catch(Exception e) {
			// expected
		}

		checkExpectationsNegative(port);
	}

	private void checkExpectationsPositive() {
		InetSocketAddress ca = (InetSocketAddress) connection.channel().localAddress();
		String clientAddress = ca.getHostString() + ":" + ca.getPort();
		String[] timerTags = new String[] {REMOTE_ADDRESS, clientAddress, STATUS, "SUCCESS"};
		String[] summaryTags = new String[] {REMOTE_ADDRESS, clientAddress, URI, "tcp"};

		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags, 1, 0.0001);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags, 1, 5);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags, 1, 5);
		checkCounter(SERVER_ERRORS, summaryTags, 0);

		InetSocketAddress sa = (InetSocketAddress) disposableServer.channel().localAddress();
		String serverAddress = sa.getHostString() + ":" + sa.getPort();
		timerTags = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		summaryTags = new String[] {REMOTE_ADDRESS, serverAddress, URI, "tcp"};
		checkTimer(CLIENT_CONNECT_TIME, timerTags, 1, 0.0001);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags, 1, 0.0001);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags, 1, 5);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags, 1, 5);
		checkCounter(CLIENT_ERRORS, summaryTags, 0);
	}

	private void checkExpectationsNegative(int port) {
		String address = "localhost:" + port;
		String[] timerTags1 = new String[] {REMOTE_ADDRESS, address, STATUS, "ERROR"};
		String[] timerTags2 = new String[] {REMOTE_ADDRESS, address, STATUS, "SUCCESS"};
		String[] summaryTags = new String[] {REMOTE_ADDRESS, address, URI, "tcp"};

		checkTimer(CLIENT_CONNECT_TIME, timerTags1, 1, 0.0001);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags2, 0, 0);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags, 0, 0);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags, 0, 0);
		checkCounter(CLIENT_ERRORS, summaryTags, 0);
	}


	protected TcpServer customizeServerOptions(TcpServer tcpServer) {
		return tcpServer;
	}

	protected TcpClient customizeClientOptions(TcpClient tcpClient) {
		return tcpClient;
	}

	protected void checkTlsTimer(String name, String[] tags, long expectedCount, double expectedTime) {
		//no-op
	}


	void checkTimer(String name, String[] tags, long expectedCount, double expectedTime) {
		Timer timer = registry.find(name).tags(tags).timer();
		assertNotNull(timer);
		assertEquals(expectedCount, timer.count());
		assertTrue(timer.totalTime(TimeUnit.SECONDS) >= expectedTime);
	}

	void checkDistributionSummary(String name, String[] tags, long expectedCount, double expectedAmound) {
		DistributionSummary summary = registry.find(name).tags(tags).summary();
		assertNotNull(summary);
		assertEquals(expectedCount, summary.count());
		assertTrue(summary.totalAmount() >= expectedAmound);
	}

	void checkCounter(String name, String[] tags, double expectedCount) {
		Counter counter = registry.find(name).tags(tags).counter();
		assertNotNull(counter);
		assertEquals(expectedCount, counter.count(), 0.0);
	}


	private static final String SERVER_METRICS_NAME = "reactor.netty.tcp.server";
	static final String SERVER_DATA_SENT = SERVER_METRICS_NAME + DATA_SENT;
	static final String SERVER_DATA_RECEIVED = SERVER_METRICS_NAME + DATA_RECEIVED;
	static final String SERVER_ERRORS = SERVER_METRICS_NAME + ERRORS;
	static final String SERVER_TLS_HANDSHAKE_TIME = SERVER_METRICS_NAME + TLS_HANDSHAKE_TIME;

	private static final String CLIENT_METRICS_NAME = "reactor.netty.tcp.client";
	static final String CLIENT_DATA_SENT = CLIENT_METRICS_NAME + DATA_SENT;
	static final String CLIENT_DATA_RECEIVED = CLIENT_METRICS_NAME + DATA_RECEIVED;
	static final String CLIENT_ERRORS = CLIENT_METRICS_NAME + ERRORS;
	static final String CLIENT_CONNECT_TIME = CLIENT_METRICS_NAME + CONNECT_TIME;
	static final String CLIENT_TLS_HANDSHAKE_TIME = CLIENT_METRICS_NAME + TLS_HANDSHAKE_TIME;
}
