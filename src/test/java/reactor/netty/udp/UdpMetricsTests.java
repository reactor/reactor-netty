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
package reactor.netty.udp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 */
public class UdpMetricsTests {
	private UdpServer udpServer;
	private Connection serverConnection;
	private UdpClient udpClient;
	private Connection clientConnection;
	private MeterRegistry registry;

	@Before
	public void setUp() {
		udpServer =
				UdpServer.create()
				         .host("127.0.0.1")
				         .port(0)
				         .metrics(true, null);

		udpClient =
				UdpClient.create()
				         .addressSupplier(() -> serverConnection.address())
				         .metrics(true, null);

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@After
	public void tearDown() {
		if (serverConnection != null) {
			serverConnection.disposeNow();
		}

		if (clientConnection != null) {
			clientConnection.disposeNow();
		}

		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();
	}

	@Test
	public void testSuccessfulCommunication() throws Exception {
		CountDownLatch latch = new CountDownLatch(2);
		serverConnection =
				udpServer.handle((in, out) ->
				             out.sendObject(
				                 in.receiveObject()
				                   .map(o -> {
				                       if (o instanceof DatagramPacket) {
				                           DatagramPacket p = (DatagramPacket) o;
				                           latch.countDown();
				                           ByteBuf buf = Unpooled.copiedBuffer("hello", CharsetUtil.UTF_8);
				                           return new DatagramPacket(buf, p.sender());
				                       }
				                       else {
				                           return Mono.error(new Exception("Unexpected type of the message: " + o));
				                       }
				                   })))
				         .bindNow(Duration.ofSeconds(30));

		clientConnection = udpClient.connectNow(Duration.ofSeconds(30));

		clientConnection.outbound()
		                .sendString(Mono.just("hello"))
		                .neverComplete()
		                .subscribe();

		clientConnection.inbound()
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

	private void checkExpectationsPositive() {
		InetSocketAddress sa = (InetSocketAddress) serverConnection.channel().localAddress();
		String serverAddress = sa.getHostString() + ":" + sa.getPort();
		InetSocketAddress ca = (InetSocketAddress) clientConnection.channel().localAddress();
		String clientAddress = ca.getHostString() + ":" + ca.getPort();
		String[] timerTags = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		String[] summaryTags1 = new String[] {REMOTE_ADDRESS, clientAddress, URI, "udp"};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "udp"};

		checkDistributionSummary(SERVER_DATA_SENT, summaryTags1);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags1);
		checkCounter(SERVER_ERRORS, summaryTags1, false);

		checkClientConnectTime(timerTags);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags2);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags2);
		checkCounter(CLIENT_ERRORS, summaryTags2, true);
	}


	private void checkClientConnectTime(String[] tags) {
		Timer timer = registry.find(CLIENT_CONNECT_TIME).tags(tags).timer();
		assertNotNull(timer);
		assertEquals(1, timer.count());
		assertTrue(timer.totalTime(TimeUnit.SECONDS) >= 0.0001);
	}

	private void checkDistributionSummary(String name, String[] tags) {
		DistributionSummary summary = registry.find(name).tags(tags).summary();
		assertNotNull(summary);
		assertEquals(1, summary.count());
		assertTrue(summary.totalAmount() >= 5);
	}

	private void checkCounter(String name, String[] tags, boolean exists) {
		Counter counter = registry.find(name).tags(tags).counter();
		if (exists) {
			assertNotNull(counter);
			assertEquals(0, counter.count(), 0.0);
		}
		else {
			assertNull(counter);
		}
	}


	private static final String SERVER_METRICS_NAME = "reactor.netty.udp.server";
	private static final String SERVER_DATA_SENT = SERVER_METRICS_NAME + DATA_SENT;
	private static final String SERVER_DATA_RECEIVED = SERVER_METRICS_NAME + DATA_RECEIVED;
	private static final String SERVER_ERRORS = SERVER_METRICS_NAME + ERRORS;

	private static final String CLIENT_METRICS_NAME = "reactor.netty.udp.client";
	private static final String CLIENT_DATA_SENT = CLIENT_METRICS_NAME + DATA_SENT;
	private static final String CLIENT_DATA_RECEIVED = CLIENT_METRICS_NAME + DATA_RECEIVED;
	private static final String CLIENT_ERRORS = CLIENT_METRICS_NAME + ERRORS;
	private static final String CLIENT_CONNECT_TIME = CLIENT_METRICS_NAME + CONNECT_TIME;
}
