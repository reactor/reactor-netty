/*
 * Copyright (c) 2019-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.udp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.channel.ContextAwareChannelMetricsRecorder;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.UDP_CLIENT_PREFIX;
import static reactor.netty.Metrics.UDP_SERVER_PREFIX;
import static reactor.netty.Metrics.URI;
import static reactor.netty.micrometer.CounterAssert.assertCounter;
import static reactor.netty.micrometer.DistributionSummaryAssert.assertDistributionSummary;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

/**
 * This test class verifies UDP metrics functionality.
 *
 * @author Violeta Georgieva
 */
class UdpMetricsTests {
	private UdpServer udpServer;
	private Connection serverConnection;
	private UdpClient udpClient;
	private Connection clientConnection;
	private MeterRegistry registry;

	@BeforeEach
	void setUp() {
		udpServer =
				UdpServer.create()
				         .host("127.0.0.1")
				         .port(0)
				         .metrics(true);

		udpClient =
				UdpClient.create()
				         .remoteAddress(() -> serverConnection.address())
				         .metrics(true);

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
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
	void testSuccessfulCommunication() throws Exception {
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

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		checkExpectationsPositive();
	}

	@Test
	void testContextAwareRecorder() throws Exception {
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

		ContextAwareRecorder recorder = ContextAwareRecorder.INSTANCE;
		clientConnection =
				udpClient.metrics(true, () -> recorder)
				         .connect()
				         .contextWrite(Context.of("testContextAwareRecorder", "OK"))
				         .block(Duration.ofSeconds(30));

		assertThat(clientConnection).isNotNull();

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

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(recorder.onDataReceivedContextView).isTrue();
		assertThat(recorder.onDataSentContextView).isTrue();
	}

	private void checkExpectationsPositive() {
		InetSocketAddress sa = (InetSocketAddress) serverConnection.channel().localAddress();
		String serverAddress = sa.getHostString() + ":" + sa.getPort();
		InetSocketAddress ca = (InetSocketAddress) clientConnection.channel().localAddress();
		String clientAddress = ca.getHostString() + ":" + ca.getPort();
		String[] timerTags = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		String[] summaryTags1 = new String[] {REMOTE_ADDRESS, clientAddress, URI, "udp"};
		String[] summaryTags2 = new String[] {REMOTE_ADDRESS, serverAddress, URI, "udp"};

		assertDistributionSummary(registry, SERVER_DATA_SENT, summaryTags1)
				.hasCountEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(5);
		assertDistributionSummary(registry, SERVER_DATA_RECEIVED, summaryTags1)
				.hasCountEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(5);
		assertCounter(registry, SERVER_ERRORS, summaryTags1).isNull();

		assertTimer(registry, CLIENT_CONNECT_TIME, timerTags)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
		assertDistributionSummary(registry, CLIENT_DATA_SENT, summaryTags2)
				.hasCountEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(5);
		assertDistributionSummary(registry, CLIENT_DATA_RECEIVED, summaryTags2)
				.hasCountEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(5);
		assertCounter(registry, CLIENT_ERRORS, summaryTags2).isNull();
	}

	private static final String SERVER_DATA_SENT = UDP_SERVER_PREFIX + DATA_SENT;
	private static final String SERVER_DATA_RECEIVED = UDP_SERVER_PREFIX + DATA_RECEIVED;
	private static final String SERVER_ERRORS = UDP_SERVER_PREFIX + ERRORS;

	private static final String CLIENT_DATA_SENT = UDP_CLIENT_PREFIX + DATA_SENT;
	private static final String CLIENT_DATA_RECEIVED = UDP_CLIENT_PREFIX + DATA_RECEIVED;
	private static final String CLIENT_ERRORS = UDP_CLIENT_PREFIX + ERRORS;
	private static final String CLIENT_CONNECT_TIME = UDP_CLIENT_PREFIX + CONNECT_TIME;

	static final class ContextAwareRecorder extends ContextAwareChannelMetricsRecorder {

		static final ContextAwareRecorder INSTANCE = new ContextAwareRecorder();

		final AtomicBoolean onDataReceivedContextView = new AtomicBoolean();
		final AtomicBoolean onDataSentContextView = new AtomicBoolean();

		@Override
		public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		}

		@Override
		public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress) {
		}

		@Override
		public void recordConnectTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status) {
		}

		@Override
		public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, long bytes) {
			onDataReceivedContextView.set("OK".equals(contextView.getOrDefault("testContextAwareRecorder", "KO")));
		}

		@Override
		public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, long bytes) {
			onDataSentContextView.set("OK".equals(contextView.getOrDefault("testContextAwareRecorder", "KO")));
		}

		@Override
		public void recordTlsHandshakeTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status) {
		}
	}
}
