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

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import reactor.core.publisher.Mono;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.PROXY_ADDRESS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;
import static reactor.netty.micrometer.CounterAssert.assertCounter;
import static reactor.netty.micrometer.DistributionSummaryAssert.assertDistributionSummary;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

/**
 * This test class verifies TCP metrics functionality.
 *
 * @author Violeta Georgieva
 */
class TcpSecureMetricsTests extends TcpMetricsTests {

	static SelfSignedCertificate ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@Override
	protected TcpServer customizeServerOptions(TcpServer tcpServer) {
		try {
			SslContext ctx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
			                                  .sslProvider(SslProvider.JDK)
			                                  .build();
			return tcpServer.secure(ssl -> ssl.sslContext(ctx)).wiretap(true);
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected TcpClient customizeClientOptions(TcpClient tcpClient) {
		try {
			SslContext ctx = SslContextBuilder.forClient()
			                                  .trustManager(InsecureTrustManagerFactory.INSTANCE)
			                                  .sslProvider(SslProvider.JDK)
			                                  .build();
			return tcpClient.secure(ssl -> ssl.sslContext(ctx)).wiretap(true);
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected void checkTlsTimer(String name, String[] tags, boolean exists) {
		if (exists) {
			assertTimer(registry, name, tags)
					.hasCountEqualTo(1)
					.hasTotalTimeGreaterThanOrEqualTo(0);
		}
		else {
			assertTimer(registry, name, tags).isNull();
		}
	}

	@Test
	void testFailedTlsHandshake() throws Exception {
		disposableServer = tcpServer.bindNow();

		connection = tcpClient.noSSL()
		                      .connectNow();

		connection.outbound()
		          .sendString(Mono.just("hello"))
		          .neverComplete()
		          .subscribe();

		CountDownLatch latch = new CountDownLatch(1);
		connection.inbound()
		          .receive()
		          .asString()
		          .subscribe(null, null, latch::countDown);

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();

		checkExpectationsNegative();
	}

	private void checkExpectationsNegative() {
		InetSocketAddress ca = (InetSocketAddress) connection.channel().localAddress();
		String clientAddress = ca.getHostString() + ":" + ca.getPort();
		String[] timerTags = new String[] {REMOTE_ADDRESS, clientAddress, STATUS, "ERROR"};
		String[] summaryTags = new String[] {REMOTE_ADDRESS, clientAddress, URI, "tcp"};

		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags, true);
		assertDistributionSummary(registry, SERVER_DATA_SENT, summaryTags).isNull();
		assertDistributionSummary(registry, SERVER_DATA_RECEIVED, summaryTags).isNull();
		assertCounter(registry, SERVER_ERRORS, summaryTags).hasCountGreaterThanOrEqualTo(1);

		InetSocketAddress sa = (InetSocketAddress) disposableServer.channel().localAddress();
		String serverAddress = sa.getHostString() + ":" + sa.getPort();
		timerTags = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, STATUS, "SUCCESS"};
		summaryTags = new String[] {REMOTE_ADDRESS, serverAddress, PROXY_ADDRESS, NA, URI, "tcp"};

		assertTimer(registry, CLIENT_CONNECT_TIME, timerTags)
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThanOrEqualTo(0);
		assertDistributionSummary(registry, CLIENT_DATA_SENT, summaryTags)
				.hasCountEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(5);
		assertDistributionSummary(registry, CLIENT_DATA_RECEIVED, summaryTags).isNull();
		assertCounter(registry, CLIENT_ERRORS, summaryTags).isNull();
	}
}
