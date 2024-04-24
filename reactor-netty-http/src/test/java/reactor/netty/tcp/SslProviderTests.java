/*
 * Copyright (c) 2018-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslClientContext;
import io.netty.handler.ssl.OpenSslContext;
import io.netty.handler.ssl.OpenSslServerContext;
import io.netty.handler.ssl.OpenSslSessionContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * This test class verifies {@link SslProvider}.
 *
 * @author Violeta Georgieva
 */
class SslProviderTests extends BaseHttpTest {
	static SelfSignedCertificate cert;
	static SelfSignedCertificate localhostCert;
	static SelfSignedCertificate anotherCert;

	private List<String> protocols;
	private SslContext sslContext;
	private HttpServer server;
	private Http11SslContextSpec serverSslContextBuilder;
	private Http2SslContextSpec serverSslContextBuilderH2;
	private SslContext localhostSslContext;
	private SslContext anotherSslContext;
	private Http11SslContextSpec clientSslContextBuilder;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		cert = new SelfSignedCertificate("default");
		localhostCert = new SelfSignedCertificate("localhost");
		anotherCert = new SelfSignedCertificate("another");
	}

	@BeforeEach
	void setUp() throws Exception {
		serverSslContextBuilder = Http11SslContextSpec.forServer(cert.certificate(), cert.privateKey());
		serverSslContextBuilderH2 = Http2SslContextSpec.forServer(cert.certificate(), cert.privateKey());

		localhostSslContext =
				Http11SslContextSpec.forServer(localhostCert.certificate(), localhostCert.privateKey())
				                    .sslContext();

		anotherSslContext =
				Http11SslContextSpec.forServer(anotherCert.certificate(), anotherCert.privateKey())
				                    .sslContext();

		clientSslContextBuilder =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		protocols = new ArrayList<>();
		server = createServer()
		                   .doOnBind(conf -> {
		                       SslProvider ssl = conf.sslProvider();
		                       if (ssl != null) {
		                           protocols.addAll(ssl.sslContext.applicationProtocolNegotiator().protocols());
		                           sslContext = ssl.sslContext;
		                       }
		                   });
	}

	@Test
	@SuppressWarnings("deprecation")
	void testProtocolHttp11SslConfiguration() {
		disposableServer =
				server.protocol(HttpProtocol.HTTP11)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .bindNow();
		assertThat(protocols.isEmpty()).isTrue();
		assertThat(OpenSsl.isAvailable() ? sslContext instanceof OpenSslContext :
		                                   sslContext instanceof JdkSslContext).isTrue();
	}

	@Test
	@SuppressWarnings("deprecation")
	void testSslConfigurationProtocolHttp11_1() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.HTTP11)
				      .bindNow();
		assertThat(protocols.isEmpty()).isTrue();
		assertThat(OpenSsl.isAvailable() ? sslContext instanceof OpenSslContext :
		                                   sslContext instanceof JdkSslContext).isTrue();
	}

	@Test
	@SuppressWarnings("deprecation")
	void testSslConfigurationProtocolHttp11_2() {
		disposableServer =
				server.protocol(HttpProtocol.H2)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.HTTP11)
				      .bindNow();
		assertThat(protocols.isEmpty()).isTrue();
		assertThat(OpenSsl.isAvailable() ? sslContext instanceof OpenSslContext :
		                                   sslContext instanceof JdkSslContext).isTrue();
	}

	@Test
	@SuppressWarnings("deprecation")
	void testProtocolH2SslConfiguration() {
		disposableServer =
				server.protocol(HttpProtocol.H2)
				      .secure(spec -> spec.sslContext(serverSslContextBuilderH2))
				      .bindNow();
		assertThat(protocols.size()).isEqualTo(2);
		assertThat(protocols.contains("h2")).isTrue();
		assertThat(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext).isTrue();
	}

	@Test
	@SuppressWarnings("deprecation")
	void testSslConfigurationProtocolH2_1() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilderH2))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertThat(protocols.size()).isEqualTo(2);
		assertThat(protocols.contains("h2")).isTrue();
		assertThat(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext).isTrue();
	}

	@Test
	@SuppressWarnings("deprecation")
	void testSslConfigurationProtocolH2_2() {
		disposableServer =
				server.protocol(HttpProtocol.HTTP11)
				      .secure(spec -> spec.sslContext(serverSslContextBuilderH2))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertThat(protocols.size()).isEqualTo(2);
		assertThat(protocols.contains("h2")).isTrue();
		assertThat(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext).isTrue();
	}

	@Test
	@SuppressWarnings("deprecation")
	void testTls13Support() {
		disposableServer =
				server.secure(spec ->
				          spec.sslContext(serverSslContextBuilder.configure(builder -> builder.protocols("TLSv1.3"))))
				      .handle((req, res) -> res.sendString(Mono.just("testTls13Support")))
				      .bindNow();

		createClient(disposableServer.port())
		        .secure(spec ->
		            spec.sslContext(clientSslContextBuilder.configure(builder -> builder.protocols("TLSv1.3"))))
		        .get()
		        .uri("/")
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext("testTls13Support")
		        .expectComplete()
		        .verify(Duration.ofSeconds(30));
	}

	@Test
	void testTls13UnsupportedProtocol_1() {
		doTestTls13UnsupportedProtocol(true, false);
	}

	@Test
	void testTls13UnsupportedProtocol_2() {
		doTestTls13UnsupportedProtocol(false, true);
	}

	@SuppressWarnings("deprecation")
	private void doTestTls13UnsupportedProtocol(boolean serverSupport, boolean clientSupport) {
		if (serverSupport) {
			serverSslContextBuilder.configure(builder -> builder.protocols("TLSv1.3"));
		}
		else {
			serverSslContextBuilder.configure(builder -> builder.protocols("TLSv1.2"));
		}
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .handle((req, res) -> res.sendString(Mono.just("testTls13Support")))
				      .bindNow();

		if (clientSupport) {
			clientSslContextBuilder.configure(builder -> builder.protocols("TLSv1.3"));
		}
		else {
			clientSslContextBuilder.configure(builder -> builder.protocols("TLSv1.2"));
		}
		StepVerifier.create(
		        createClient(disposableServer.port())
		                  .secure(spec -> spec.sslContext(clientSslContextBuilder))
		                  .get()
		                  .uri("/")
		                  .responseContent()
		                  .aggregate()
		                  .asString())
		            .expectError(SSLHandshakeException.class)
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testAdd() {
		SslProvider.Builder builder =
				SslProvider.builder()
				           .sslContext(serverSslContextBuilder)
				           .addSniMapping("localhost", spec -> spec.sslContext(localhostSslContext));

		SniProvider provider = builder.build().sniProvider;
		assertThat(provider.mappings.map("localhost", GlobalEventExecutor.INSTANCE.newPromise()).getNow().sslContext)
				.isSameAs(localhostSslContext);

		provider = builder.addSniMapping("localhost", spec -> spec.sslContext(anotherSslContext))
		                  .build()
		                  .sniProvider;
		assertThat(provider.mappings.map("localhost", GlobalEventExecutor.INSTANCE.newPromise()).getNow().sslContext)
				.isSameAs(anotherSslContext);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testAddBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(serverSslContextBuilder)
						.addSniMapping(null, spec -> spec.sslContext(localhostSslContext)));

		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(serverSslContextBuilder)
						.addSniMapping("localhost", null));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testAddAll() {
		Map<String, Consumer<? super SslProvider.SslContextSpec>> map = new HashMap<>();
		map.put("localhost", spec -> spec.sslContext(localhostSslContext));

		SslProvider.Builder builder =
				SslProvider.builder()
				           .sslContext(serverSslContextBuilder)
				           .addSniMappings(map);

		SniProvider provider = builder.build().sniProvider;
		assertThat(provider.mappings.map("localhost", GlobalEventExecutor.INSTANCE.newPromise()).getNow().sslContext)
				.isSameAs(localhostSslContext);

		map.put("another", spec -> spec.sslContext(anotherSslContext));

		provider = builder.addSniMappings(map).build().sniProvider;
		assertThat(provider.mappings.map("localhost", GlobalEventExecutor.INSTANCE.newPromise()).getNow().sslContext)
				.isSameAs(localhostSslContext);
		assertThat(provider.mappings.map("another", GlobalEventExecutor.INSTANCE.newPromise()).getNow().sslContext)
				.isSameAs(anotherSslContext);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testAddAllBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(serverSslContextBuilder)
						.addSniMappings(null));
	}

	@Test
	void testSetAll() throws Exception {
		Map<String, Consumer<? super SslProvider.SslContextSpec>> map = new HashMap<>();
		map.put("localhost", spec -> spec.sslContext(localhostSslContext));

		SslContext defaultSslContext = serverSslContextBuilder.sslContext();
		SslProvider.Builder builder =
				SslProvider.builder()
				           .sslContext(defaultSslContext)
				           .setSniMappings(map);

		SniProvider provider = builder.build().sniProvider;
		assertThat(provider.mappings.map("localhost", GlobalEventExecutor.INSTANCE.newPromise()).getNow().sslContext)
				.isSameAs(localhostSslContext);

		map.clear();
		map.put("another", spec -> spec.sslContext(anotherSslContext));

		provider = builder.setSniMappings(map).build().sniProvider;
		assertThat(provider.mappings.map("localhost", GlobalEventExecutor.INSTANCE.newPromise()).getNow().sslContext)
				.isSameAs(defaultSslContext);
		assertThat(provider.mappings.map("another", GlobalEventExecutor.INSTANCE.newPromise()).getNow().sslContext)
				.isSameAs(anotherSslContext);
	}

	@Test
	@SuppressWarnings("deprecation")
	void testSetAllBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(serverSslContextBuilder)
						.setSniMappings(null));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testSetSniAsyncMappingsBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(serverSslContextBuilder)
						.setSniAsyncMappings(null));
	}

	@Test
	void testServerNames() throws Exception {
		SslContext defaultSslContext = clientSslContextBuilder.sslContext();
		SslProvider.Builder builder =
				SslProvider.builder()
				          .sslContext(defaultSslContext)
				          .serverNames(new SNIHostName("test"))
				          .handlerConfigurator(h -> {
				              List<SNIServerName> list = h.engine().getSSLParameters().getServerNames();
				              assertThat(list).isNotNull();
				              assertThat(list.get(0)).isInstanceOf(SNIHostName.class);
				              assertThat(((SNIHostName) list.get(0)).getAsciiName()).isEqualTo("test");
				          });

		SslProvider provider = builder.build();
		SslHandler handler = provider.getSslContext().newHandler(ByteBufAllocator.DEFAULT);
		provider.configure(handler);
	}

	@Test
	void testServerNamesBadValues() throws Exception {
		SslContext defaultSslContext = clientSslContextBuilder.sslContext();
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(defaultSslContext)
						.serverNames((SNIServerName[]) null));
	}

	@Test
	void testDefaultClientProviderIsOpenSsl() {
		final SslProvider clientProvider = SslProvider.defaultClientProvider();

		final OpenSslClientContext clientContext = (OpenSslClientContext) clientProvider.getSslContext();
		assertThat(clientContext.isClient()).isTrue();
		assertThat(clientContext.applicationProtocolNegotiator().protocols())
				.isEmpty();
		assertThat(clientContext.cipherSuites())
				.containsExactlyInAnyOrder("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
						"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
						"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
						"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
						"TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
						"TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
						"TLS_RSA_WITH_AES_128_GCM_SHA256",
						"TLS_RSA_WITH_AES_128_CBC_SHA",
						"TLS_RSA_WITH_AES_256_CBC_SHA",
						"TLS_AES_128_GCM_SHA256",
						"TLS_AES_256_GCM_SHA384",
						"TLS_CHACHA20_POLY1305_SHA256");

		final OpenSslSessionContext sessionContext = clientContext.sessionContext();
		assertThat(sessionContext.getSessionTimeout()).isEqualTo(300);
		assertThat(sessionContext.isSessionCacheEnabled()).isTrue();
	}

	@Test
	@SuppressWarnings("deprecation")
	void testServerSslProviderIsOpenSsl() {
		final SslProvider serverProvider = SslProvider.builder()
												.sslContext(serverSslContextBuilderH2)
												.build();

		final OpenSslServerContext serverContext = (OpenSslServerContext) serverProvider.getSslContext();
		assertThat(serverContext.isServer()).isTrue();
		assertThat(serverContext.applicationProtocolNegotiator().protocols())
				.containsExactly("h2", "http/1.1");
		assertThat(serverContext.cipherSuites())
				.containsExactlyInAnyOrder("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
						"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
						"TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
						"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
						"TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
						"TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
						"TLS_AES_128_GCM_SHA256",
						"TLS_AES_256_GCM_SHA384",
						"TLS_CHACHA20_POLY1305_SHA256");

		final OpenSslSessionContext sessionContext = serverContext.sessionContext();
		assertThat(sessionContext.getSessionTimeout()).isEqualTo(300);
		assertThat(sessionContext.isSessionCacheEnabled()).isTrue();
		assertThat(sessionContext.getSessionCacheSize()).isEqualTo(20480);
	}

}
