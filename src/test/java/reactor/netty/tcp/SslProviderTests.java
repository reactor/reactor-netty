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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Violeta Georgieva
 */
public class SslProviderTests {
	static final String PROTOCOL_TLS_V1_3 = "TLSv1.3";

	// expected TLSv1.3 cipher suites commonly used by JDK 11+ and OpenSSL
	static final String[] TLSV13_CIPHER_SUITES = { "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384" };

	// expected cipher suites that are mandatory for HTTP/2
	static final String[] HTTP2_MANDATORY_CIPHER_SUITES = { "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" };

	private List<String> protocols;
	private SslContext sslContext;
	private HttpServer server;
	private SslContextBuilder serverSslContextBuilder;
	private SslContextBuilder clientSslContextBuilder;
	private DisposableServer disposableServer;

	@Before
	public void setUp() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		serverSslContextBuilder = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		clientSslContextBuilder = SslContextBuilder.forClient()
		                                           .trustManager(InsecureTrustManagerFactory.INSTANCE);
		protocols = new ArrayList<>();
		sslContext = null;
		server = HttpServer.create()
		                   .port(0)
		                   .tcpConfiguration(tcpServer -> tcpServer.doOnBind(b -> {
		                       SslProvider ssl = reactor.netty.tcp.SslProvider.findSslSupport(b);
		                       if (ssl != null) {
		                           protocols.addAll(ssl.sslContext.applicationProtocolNegotiator().protocols());
		                           sslContext = ssl.sslContext;
		                       }
		                   }));
	}

	@After
	public void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Test
	public void testProtocolHttp11SslConfiguration() {
		disposableServer =
				server.protocol(HttpProtocol.HTTP11)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .bindNow();
		assertThat(protocols).isEmpty();
		assertThat(sslContext).isInstanceOf(OpenSslContext.class).satisfies(ctx -> {
			assertThat(ctx.cipherSuites()).contains(TLSV13_CIPHER_SUITES);
		});
	}

	@Test
	public void testSslConfigurationProtocolHttp11_1() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.HTTP11)
				      .bindNow();
		assertThat(protocols).isEmpty();
		assertThat(sslContext).isInstanceOf(OpenSslContext.class).satisfies(ctx -> {
			assertThat(ctx.cipherSuites()).contains(TLSV13_CIPHER_SUITES);
		});
	}

	@Test
	public void testSslConfigurationProtocolHttp11_2() {
		disposableServer =
				server.protocol(HttpProtocol.H2)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.HTTP11)
				      .bindNow();
		assertThat(protocols).isEmpty();
		assertThat(sslContext).isInstanceOf(OpenSslContext.class).satisfies(ctx -> {
			assertThat(ctx.cipherSuites()).contains(TLSV13_CIPHER_SUITES);
		});
	}

	@Test
	public void testProtocolH2SslConfiguration() {
		disposableServer =
				server.protocol(HttpProtocol.H2)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .bindNow();
		assertThat(protocols).containsExactly(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1);
		assertThat(sslContext).isInstanceOf(OpenSslContext.class).satisfies(ctx -> {
			assertThat(ctx.cipherSuites()).contains(TLSV13_CIPHER_SUITES).contains(HTTP2_MANDATORY_CIPHER_SUITES);
		});
	}

	@Test
	public void testSslConfigurationProtocolH2_1() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertThat(protocols).containsExactly(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1);
		assertThat(sslContext).isInstanceOf(OpenSslContext.class).satisfies(ctx -> {
			assertThat(ctx.cipherSuites()).contains(TLSV13_CIPHER_SUITES).contains(HTTP2_MANDATORY_CIPHER_SUITES);
		});
	}

	@Test
	public void testSslConfigurationProtocolH2_2() {
		disposableServer =
				server.protocol(HttpProtocol.HTTP11)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertThat(protocols).containsExactly(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1);
		assertThat(sslContext).isInstanceOf(OpenSslContext.class).satisfies(ctx -> {
			assertThat(ctx.cipherSuites()).contains(TLSV13_CIPHER_SUITES).contains(HTTP2_MANDATORY_CIPHER_SUITES);
		});
	}

	@Test
	public void testTls13Support() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder.protocols(PROTOCOL_TLS_V1_3)))
				      .handle((req, res) -> res.sendString(Mono.just("testTls13Support")))
				      .bindNow();

		StepVerifier.create(HttpClient.create()
		                  .port(disposableServer.port())
		                  .secure(spec -> spec.sslContext(clientSslContextBuilder.protocols(PROTOCOL_TLS_V1_3)))
		                  .get()
		                  .uri("/")
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		            .expectNext("testTls13Support")
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	public void testTls13UnsupportedProtocol_1() {
		doTestTls13UnsupportedProtocol(true, false);
	}

	@Test
	public void testTls13UnsupportedProtocol_2() {
		doTestTls13UnsupportedProtocol(false, true);
	}

	private void doTestTls13UnsupportedProtocol(boolean serverSupport, boolean clientSupport) {
		if (serverSupport) {
			serverSslContextBuilder.protocols(PROTOCOL_TLS_V1_3);
		}
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .handle((req, res) -> res.sendString(Mono.just("testTls13Support")))
				      .bindNow();

		if (clientSupport) {
			clientSslContextBuilder.protocols(PROTOCOL_TLS_V1_3);
		}
		StepVerifier.create(
		        HttpClient.create()
		                  .port(disposableServer.port())
		                  .secure(spec -> spec.sslContext(clientSslContextBuilder))
		                  .get()
		                  .uri("/")
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		            .expectError(SSLHandshakeException.class)
		            .verify(Duration.ofSeconds(30));
	}
}
