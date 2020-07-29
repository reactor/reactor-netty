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

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.BeforeClass;
import org.junit.Test;
import reactor.core.publisher.Mono;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 */
public class TcpSecureMetricsTests extends TcpMetricsTests {
	private static SelfSignedCertificate ssc;

	@BeforeClass
	public static void createSelfSignedCertificate() throws CertificateException {
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
		checkTimer(name, tags, exists);
	}

	@Test
	public void testFailedTlsHandshake() throws Exception {
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

		assertTrue(latch.await(30, TimeUnit.SECONDS));

		checkExpectationsNegative();
	}

	private void checkExpectationsNegative() {
		InetSocketAddress ca = (InetSocketAddress) connection.channel().localAddress();
		String clientAddress = ca.getHostString() + ":" + ca.getPort();
		String[] timerTags = new String[] {REMOTE_ADDRESS, clientAddress, STATUS, "ERROR"};
		String[] summaryTags = new String[] {REMOTE_ADDRESS, clientAddress, URI, "tcp"};

		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags, true);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags, 0, 0, false);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags, 0, 0, false);
		checkCounter(SERVER_ERRORS, summaryTags, 1, true);

		InetSocketAddress sa = (InetSocketAddress) disposableServer.channel().localAddress();
		String serverAddress = sa.getHostString() + ":" + sa.getPort();
		timerTags = new String[] {REMOTE_ADDRESS, serverAddress, STATUS, "SUCCESS"};
		summaryTags = new String[] {REMOTE_ADDRESS, serverAddress, URI, "tcp"};

		checkTimer(CLIENT_CONNECT_TIME, timerTags, true);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags, 1, 5, true);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags, 0, 0, false);
		checkCounter(CLIENT_ERRORS, summaryTags, 0, false);
	}
}
