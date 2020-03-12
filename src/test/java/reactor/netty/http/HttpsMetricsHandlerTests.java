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

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.BeforeClass;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.tcp.TcpServer;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 */
public class HttpsMetricsHandlerTests extends HttpMetricsHandlerTests {
	private static SelfSignedCertificate ssc;

	@BeforeClass
	public static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@Override
	protected HttpServer customizeServerOptions(HttpServer server) {
		try {
			SslContext ctx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
			                                  .sslProvider(SslProvider.JDK)
			                                  .build();
			return server.secure(ssl -> ssl.sslContext(ctx));
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected HttpClient customizeClientOptions(HttpClient httpClient) {
		try {
			SslContext ctx = SslContextBuilder.forClient()
			                                  .trustManager(InsecureTrustManagerFactory.INSTANCE)
			                                  .sslProvider(SslProvider.JDK)
			                                  .build();
			return httpClient.secure(ssl -> ssl.sslContext(ctx));
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected void checkTlsTimer(String name, String[] tags, long expectedCount) {
		checkTimer(name, tags, expectedCount);
	}


	@Test
	public void testIssue896() throws Exception {
		disposableServer = httpServer.tcpConfiguration(TcpServer::noSSL)
		                             .bindNow();

		CountDownLatch latch = new CountDownLatch(1);
		httpClient.observe((conn, state) -> conn.channel()
		                                        .closeFuture()
		                                        .addListener(f -> latch.countDown()))
		          .post()
		          .uri("/1")
		          .send(ByteBufFlux.fromString(Mono.just("hello")))
		          .responseContent()
		          .subscribe();

		assertTrue(latch.await(30, TimeUnit.SECONDS));

		InetSocketAddress sa = (InetSocketAddress) disposableServer.channel().localAddress();
		String serverAddress = sa.getHostString() + ":" + sa.getPort();
		String[] summaryTags = new String[]{REMOTE_ADDRESS, serverAddress, URI, "unknown"};
		checkCounter(CLIENT_ERRORS, summaryTags, true, 2);
	}
}
