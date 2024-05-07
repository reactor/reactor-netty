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
package reactor.netty.tcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.CONNECTIONS_TOTAL;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.LOCAL_ADDRESS;
import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.PROXY_ADDRESS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TCP_CLIENT_PREFIX;
import static reactor.netty.Metrics.TCP_SERVER_PREFIX;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.URI;
import static reactor.netty.micrometer.CounterAssert.assertCounter;
import static reactor.netty.micrometer.DistributionSummaryAssert.assertDistributionSummary;
import static reactor.netty.micrometer.GaugeAssert.assertGauge;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.channel.ContextAwareChannelMetricsRecorder;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This test class verifies TCP metrics functionality.
 *
 * @author Violeta Georgieva
 */
class TcpMetricsTests {
	TcpServer tcpServer;
	DisposableServer disposableServer;
	TcpClient tcpClient;
	Connection connection;
	private ConnectionProvider provider;
	MeterRegistry registry;

	@BeforeEach
	void setUp() {
		tcpServer =
				customizeServerOptions(TcpServer.create()
				                                .host("127.0.0.1")
				                                .port(0)
				                                .metrics(true));

		provider = ConnectionProvider.create("TcpMetricsTests", 1);
		tcpClient =
				customizeClientOptions(TcpClient.create(provider)
				                                .remoteAddress(() -> disposableServer.address())
				                                .metrics(true));

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
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
	void testSuccessfulCommunication() throws Exception {
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

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		checkExpectationsPositive();
	}

	@Test
	void testFailedConnect() throws Exception {
		disposableServer = tcpServer.bindNow();

		CountDownLatch latch = new CountDownLatch(1);
		int port = SocketUtils.findAvailableTcpPort();
		try {
			connection = tcpClient.host("127.0.0.1")
			                      .port(port)
			                      .doOnChannelInit((observer, channel, address) ->
	                                  channel.pipeline()
			                                 .addLast(new ChannelInboundHandlerAdapter() {

			                                     @Override
			                                     public void channelUnregistered(ChannelHandlerContext ctx) {
			                                         latch.countDown();
			                                         ctx.fireChannelUnregistered();
			                                     }
			                                 }))
			                      .connectNow();
			fail("Connect should fail.");
		}
		catch (Exception e) {
			// expected
		}

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		checkExpectationsNegative(port);
	}

	@Test
	void testContextAwareRecorder() throws Exception {
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

		ContextAwareRecorder recorder = ContextAwareRecorder.INSTANCE;
		connection =
				tcpClient.metrics(true, () -> recorder)
				         .connect()
				         .contextWrite(Context.of("testContextAwareRecorder", "OK"))
				         .block(Duration.ofSeconds(30));

		assertThat(connection).isNotNull();

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

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		assertThat(recorder.onDataReceivedContextView).isTrue();
		assertThat(recorder.onDataSentContextView).isTrue();
	}

	@Test
	void smokeTestNoContextPropagation() {
		assertThatExceptionOfType(ClassNotFoundException.class)
				.isThrownBy(() -> Class.forName("io.micrometer.context.ContextRegistry"));
	}

	private void checkExpectationsPositive() {
		InetSocketAddress ca = (InetSocketAddress) connection.channel().localAddress();
		String clientAddress = ca.getHostString() + ":" + ca.getPort();
		InetSocketAddress sa = (InetSocketAddress) disposableServer.channel().localAddress();
		String serverAddress = sa.getHostString() + ":" + sa.getPort();
		String[] timerTags = new String[] {REMOTE_ADDRESS, clientAddress, STATUS, "SUCCESS"};
		String[] summaryTags = new String[] {REMOTE_ADDRESS, clientAddress, URI, "tcp"};
		String[] totalConnectionsTags = new String[] {URI, "tcp", LOCAL_ADDRESS, serverAddress};

		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags, true);
		assertDistributionSummary(registry, SERVER_DATA_SENT, summaryTags)
				.hasCountEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(5);
		assertDistributionSummary(registry, SERVER_DATA_RECEIVED, summaryTags)
				.hasCountEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(5);
		assertCounter(registry, SERVER_ERRORS, summaryTags).isNull();

		timerTags = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, STATUS, "SUCCESS"};
		summaryTags = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, "tcp"};
		assertTimer(registry, CLIENT_CONNECT_TIME, timerTags)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThanOrEqualTo(0);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags, true);
		assertDistributionSummary(registry, CLIENT_DATA_SENT, summaryTags)
				.hasCountEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(5);
		assertDistributionSummary(registry, CLIENT_DATA_RECEIVED, summaryTags)
				.hasCountEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(5);
		assertCounter(registry, CLIENT_ERRORS, summaryTags).isNull();
		assertGauge(registry, SERVER_CONNECTIONS_TOTAL, totalConnectionsTags).hasValueEqualTo(1);
	}

	private void checkExpectationsNegative(int port) {
		String address = "127.0.0.1:" + port;
		String[] timerTags1 = new String[] {REMOTE_ADDRESS, address, PROXY_ADDRESS, NA, STATUS, "ERROR"};
		String[] timerTags2 = new String[] {REMOTE_ADDRESS, address, PROXY_ADDRESS, NA, STATUS, "SUCCESS"};
		String[] summaryTags = new String[] {REMOTE_ADDRESS, address, PROXY_ADDRESS, NA, URI, "tcp"};

		assertTimer(registry, CLIENT_CONNECT_TIME, timerTags1)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThanOrEqualTo(0);
		checkTlsTimer(CLIENT_TLS_HANDSHAKE_TIME, timerTags2, false);
		assertDistributionSummary(registry, CLIENT_DATA_SENT, summaryTags).isNull();
		assertDistributionSummary(registry, CLIENT_DATA_RECEIVED, summaryTags).isNull();
		assertCounter(registry, CLIENT_ERRORS, summaryTags).isNull();
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

	static final String SERVER_CONNECTIONS_TOTAL = TCP_SERVER_PREFIX + CONNECTIONS_TOTAL;
	static final String SERVER_DATA_SENT = TCP_SERVER_PREFIX + DATA_SENT;
	static final String SERVER_DATA_RECEIVED = TCP_SERVER_PREFIX + DATA_RECEIVED;
	static final String SERVER_ERRORS = TCP_SERVER_PREFIX + ERRORS;
	static final String SERVER_TLS_HANDSHAKE_TIME = TCP_SERVER_PREFIX + TLS_HANDSHAKE_TIME;

	static final String CLIENT_DATA_SENT = TCP_CLIENT_PREFIX + DATA_SENT;
	static final String CLIENT_DATA_RECEIVED = TCP_CLIENT_PREFIX + DATA_RECEIVED;
	static final String CLIENT_ERRORS = TCP_CLIENT_PREFIX + ERRORS;
	static final String CLIENT_CONNECT_TIME = TCP_CLIENT_PREFIX + CONNECT_TIME;
	static final String CLIENT_TLS_HANDSHAKE_TIME = TCP_CLIENT_PREFIX + TLS_HANDSHAKE_TIME;

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
