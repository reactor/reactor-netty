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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Violeta Georgieva
 */
class SslProviderTests extends BaseHttpTest {
	static SelfSignedCertificate cert;
	static SelfSignedCertificate localhostCert;
	static SelfSignedCertificate anotherCert;

	private List<String> protocols;
	private SslContext sslContext;
	private HttpServer server;
	private SslContextBuilder serverSslContextBuilder;
	private SslContext localhostSslContext;
	private SslContext anotherSslContext;
	private SslContextBuilder clientSslContextBuilder;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		cert = new SelfSignedCertificate("default");
		localhostCert = new SelfSignedCertificate("localhost");
		anotherCert = new SelfSignedCertificate("another");
	}

	@BeforeEach
	void setUp() throws Exception {
		serverSslContextBuilder = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());

		SslContextBuilder localhostSslContextBuilder =
				SslContextBuilder.forServer(localhostCert.certificate(), localhostCert.privateKey());
		localhostSslContext = localhostSslContextBuilder.build();

		SslContextBuilder anotherSslContextBuilder =
				SslContextBuilder.forServer(anotherCert.certificate(), anotherCert.privateKey());
		anotherSslContext = anotherSslContextBuilder.build();

		clientSslContextBuilder = SslContextBuilder.forClient()
		                                           .trustManager(InsecureTrustManagerFactory.INSTANCE);
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
	void testProtocolH2SslConfiguration() {
		disposableServer =
				server.protocol(HttpProtocol.H2)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .bindNow();
		assertThat(protocols.size()).isEqualTo(2);
		assertThat(protocols.contains("h2")).isTrue();
		assertThat(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext).isTrue();
	}

	@Test
	void testSslConfigurationProtocolH2_1() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertThat(protocols.size()).isEqualTo(2);
		assertThat(protocols.contains("h2")).isTrue();
		assertThat(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext).isTrue();
	}

	@Test
	void testSslConfigurationProtocolH2_2() {
		disposableServer =
				server.protocol(HttpProtocol.HTTP11)
				      .secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertThat(protocols.size()).isEqualTo(2);
		assertThat(protocols.contains("h2")).isTrue();
		assertThat(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext).isTrue();
	}

	@Test
	void testTls13Support() {
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder.protocols("TLSv1.3")))
				      .handle((req, res) -> res.sendString(Mono.just("testTls13Support")))
				      .bindNow();

		StepVerifier.create(
		        createClient(disposableServer.port())
		                  .secure(spec -> spec.sslContext(clientSslContextBuilder.protocols("TLSv1.3")))
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
	void testTls13UnsupportedProtocol_1() {
		doTestTls13UnsupportedProtocol(true, false);
	}

	@Test
	void testTls13UnsupportedProtocol_2() {
		doTestTls13UnsupportedProtocol(false, true);
	}

	private void doTestTls13UnsupportedProtocol(boolean serverSupport, boolean clientSupport) {
		if (serverSupport) {
			serverSslContextBuilder.protocols("TLSv1.3");
		}
		else {
			serverSslContextBuilder.protocols("TLSv1.2");
		}
		disposableServer =
				server.secure(spec -> spec.sslContext(serverSslContextBuilder))
				      .handle((req, res) -> res.sendString(Mono.just("testTls13Support")))
				      .bindNow();

		if (clientSupport) {
			clientSslContextBuilder.protocols("TLSv1.3");
		}
		else {
			clientSslContextBuilder.protocols("TLSv1.2");
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
	void testAdd() throws Exception {
		SslProvider.Builder builder =
				SslProvider.builder()
				           .sslContext(serverSslContextBuilder.build())
				           .addSniMapping("localhost", spec -> spec.sslContext(localhostSslContext));

		SniProvider provider = builder.build().sniProvider;
		assertThat(mappings(provider).map("localhost")).isSameAs(localhostSslContext);

		provider = builder.addSniMapping("localhost", spec -> spec.sslContext(anotherSslContext))
		                  .build()
		                  .sniProvider;
		assertThat(mappings(provider).map("localhost")).isSameAs(anotherSslContext);
	}

	@Test
	void testAddBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(serverSslContextBuilder.build())
						.addSniMapping(null, spec -> spec.sslContext(localhostSslContext)));

		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(serverSslContextBuilder.build())
						.addSniMapping("localhost", null));
	}

	@Test
	void testAddAll() throws Exception {
		Map<String, Consumer<? super SslProvider.SslContextSpec>> map = new HashMap<>();
		map.put("localhost", spec -> spec.sslContext(localhostSslContext));

		SslProvider.Builder builder =
				SslProvider.builder()
				           .sslContext(serverSslContextBuilder.build())
				           .addSniMappings(map);

		SniProvider provider = builder.build().sniProvider;
		assertThat(mappings(provider).map("localhost")).isSameAs(localhostSslContext);

		map.put("another", spec -> spec.sslContext(anotherSslContext));

		provider = builder.addSniMappings(map).build().sniProvider;
		assertThat(mappings(provider).map("localhost")).isSameAs(localhostSslContext);
		assertThat(mappings(provider).map("another")).isSameAs(anotherSslContext);
	}

	@Test
	void testAddAllBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(serverSslContextBuilder.build())
						.addSniMappings(null));
	}

	@Test
	void testSetAll() throws Exception {
		Map<String, Consumer<? super SslProvider.SslContextSpec>> map = new HashMap<>();
		map.put("localhost", spec -> spec.sslContext(localhostSslContext));

		SslContext defaultSslContext = serverSslContextBuilder.build();
		SslProvider.Builder builder =
				SslProvider.builder()
				           .sslContext(defaultSslContext)
				           .setSniMappings(map);

		SniProvider provider = builder.build().sniProvider;
		assertThat(mappings(provider).map("localhost")).isSameAs(localhostSslContext);

		map.clear();
		map.put("another", spec -> spec.sslContext(anotherSslContext));

		provider = builder.setSniMappings(map).build().sniProvider;
		assertThat(mappings(provider).map("localhost")).isSameAs(defaultSslContext);
		assertThat(mappings(provider).map("another")).isSameAs(anotherSslContext);
	}

	@Test
	void testSetAllBadValues() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(serverSslContextBuilder.build())
						.setSniMappings(null));
	}

	@Test
	void testServerNames() throws Exception {
		SslContext defaultSslContext = clientSslContextBuilder.build();
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
		SslContext defaultSslContext = clientSslContextBuilder.build();
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> SslProvider.builder()
						.sslContext(defaultSslContext)
						.serverNames((SNIServerName[]) null));
	}

	static Mapping<String, SslContext> mappings(SniProvider provider) {
		DomainWildcardMappingBuilder<SslContext> mappingsBuilder =
				new DomainWildcardMappingBuilder<>(provider.defaultSslProvider.getSslContext());
		provider.confPerDomainName.forEach((s, sslProvider) -> mappingsBuilder.add(s, sslProvider.getSslContext()));
		return mappingsBuilder.build();
	}
}
