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
package reactor.netty.tcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static reactor.netty.Metrics.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.channel.BootstrapHandlers;
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
				customizeServerOptions(TcpServer.create()
				                                .host("127.0.0.1")
				                                .port(0)
				                                .metrics(true));

		provider = ConnectionProvider.create("TcpMetricsTests", 1);
		tcpClient =
				customizeClientOptions(TcpClient.create(provider)
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
				tcpServer.handle((in, out) -> {
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

		connection = tcpClient.connectNow();

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
	public void testFailedConnect() throws Exception {
		disposableServer = tcpServer.bindNow();

		CountDownLatch latch = new CountDownLatch(1);
		int port = SocketUtils.findAvailableTcpPort();
		try {
			connection = tcpClient.host("127.0.0.1")
			                      .port(port)
			                      .doOnConnect(b ->
			                          BootstrapHandlers.updateConfiguration(b, "testFailedConnect",
			                              (o, c) ->
			                                  c.pipeline()
			                                   .addLast(new ChannelInboundHandlerAdapter() {

			                                       @Override
			                                       public void channelUnregistered(ChannelHandlerContext ctx) {
			                                           latch.countDown();
			                                           ctx.fireChannelUnregistered();
			                                       }
			                                   })))
			                      .connectNow();
			fail("Connect should fail.");
		}
		catch(Exception e) {
			// expected
		}

		assertTrue(latch.await(30, TimeUnit.SECONDS));

		checkExpectationsNegative(port);
	}

	private void checkExpectationsPositive() {
		InetSocketAddress ca = (InetSocketAddress) connection.channel().localAddress();
		String clientAddress = ca.getHostString() + ":" + ca.getPort();
		String[] timerTags = new String[] {REMOTE_ADDRESS, clientAddress, STATUS, "SUCCESS"};
		String[] summaryTags = new String[] {REMOTE_ADDRESS, clientAddress, URI, "tcp"};

		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags, true);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags, 1, 5, true);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags, 1, 5, true);
		checkCounter(SERVER_ERRORS, summaryTags, 0, false);

		InetSocketAddress sa = (InetSocketAddress) disposableServer.channel().localAddress();
		String serverAddress = sa.getHostString() + ":" + sa.getPort();
		timerTags = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		summaryTags = new String[] {REMOTE_ADDRESS, serverAddress, URI, "tcp"};
		checkTimer(CLIENT_CONNECT_TIME, timerTags, true);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags, true);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags, 1, 5, true);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags, 1, 5, true);
		checkCounter(CLIENT_ERRORS, summaryTags, 0, false);
	}

	private void checkExpectationsNegative(int port) {
		String address = "127.0.0.1:" + port;
		String[] timerTags1 = new String[] {REMOTE_ADDRESS, address, STATUS, "ERROR"};
		String[] timerTags2 = new String[] {REMOTE_ADDRESS, address, STATUS, "SUCCESS"};
		String[] summaryTags = new String[] {REMOTE_ADDRESS, address, URI, "tcp"};

		checkTimer(CLIENT_CONNECT_TIME, timerTags1, true);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags2, false);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags, 0, 0, false);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags, 0, 0, false);
		checkCounter(CLIENT_ERRORS, summaryTags, 0, false);
	}


	protected TcpServer customizeServerOptions(TcpServer tcpServer) {
		return tcpServer;
	}

	protected TcpClient customizeClientOptions(TcpClient tcpClient) {
		return tcpClient;
	}

	protected void checkTlsTimer(String name, String[] tags, boolean exists) {
		//no-op
	}


	void checkTimer(String name, String[] tags, boolean exists) {
		Timer timer = registry.find(name).tags(tags).timer();
		if (exists) {
			assertNotNull(timer);
			assertEquals(1, timer.count());
			assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) >= 0);
		}
		else {
			assertNull(timer);
		}
	}

	void checkDistributionSummary(String name, String[] tags, long expectedCount, int expectedAmount, boolean exists) {
		DistributionSummary summary = registry.find(name).tags(tags).summary();
		if (exists) {
			assertNotNull(summary);
			assertEquals(expectedCount, summary.count());
			assertTrue(summary.totalAmount() >= expectedAmount);
		}
		else {
			assertNull(summary);
		}
	}

	void checkCounter(String name, String[] tags, double expectedCount, boolean exists) {
		Counter counter = registry.find(name).tags(tags).counter();
		if (exists) {
			assertNotNull(counter);
			assertTrue(counter.count() >= expectedCount);
		}
		else {
			assertNull(counter);
		}
	}


	static final String SERVER_DATA_SENT = TCP_SERVER_PREFIX + DATA_SENT;
	static final String SERVER_DATA_RECEIVED = TCP_SERVER_PREFIX + DATA_RECEIVED;
	static final String SERVER_ERRORS = TCP_SERVER_PREFIX + ERRORS;
	static final String SERVER_TLS_HANDSHAKE_TIME = TCP_SERVER_PREFIX + TLS_HANDSHAKE_TIME;

	static final String CLIENT_DATA_SENT = TCP_CLIENT_PREFIX + DATA_SENT;
	static final String CLIENT_DATA_RECEIVED = TCP_CLIENT_PREFIX + DATA_RECEIVED;
	static final String CLIENT_ERRORS = TCP_CLIENT_PREFIX + ERRORS;
	static final String CLIENT_CONNECT_TIME = TCP_CLIENT_PREFIX + CONNECT_TIME;
	static final String CLIENT_TLS_HANDSHAKE_TIME = TCP_CLIENT_PREFIX + TLS_HANDSHAKE_TIME;
}
