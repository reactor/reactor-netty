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

import java.util.ArrayList;
import java.util.List;

import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.Before;
import org.junit.Test;
import reactor.netty.DisposableServer;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Violeta Georgieva
 */
public class SslProviderTests {
	private List<String> protocols;
	private SslContext sslContext;
	private HttpServer server;
	private SslContextBuilder builder;

	@Before
	public void setUp() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		builder = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		protocols = new ArrayList<>();
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

	@Test
	public void testProtocolHttp11SslConfiguration() {
		DisposableServer disposableServer =
				server.protocol(HttpProtocol.HTTP11)
				      .secure(spec -> spec.sslContext(builder))
				      .bindNow();
		assertTrue(protocols.isEmpty());
		assertTrue(OpenSsl.isAvailable() ? sslContext instanceof OpenSslContext :
		                                   sslContext instanceof JdkSslContext);
		disposableServer.disposeNow();
	}

	@Test
	public void testSslConfigurationProtocolHttp11_1() {
		DisposableServer disposableServer =
				server.secure(spec -> spec.sslContext(builder))
				      .protocol(HttpProtocol.HTTP11)
				      .bindNow();
		assertTrue(protocols.isEmpty());
		assertTrue(OpenSsl.isAvailable() ? sslContext instanceof OpenSslContext :
		                                   sslContext instanceof JdkSslContext);
		disposableServer.disposeNow();
	}

	@Test
	public void testSslConfigurationProtocolHttp11_2() {
		DisposableServer disposableServer =
				server.protocol(HttpProtocol.H2)
				      .secure(spec -> spec.sslContext(builder))
				      .protocol(HttpProtocol.HTTP11)
				      .bindNow();
		assertTrue(protocols.isEmpty());
		assertTrue(OpenSsl.isAvailable() ? sslContext instanceof OpenSslContext :
		                                   sslContext instanceof JdkSslContext);
		disposableServer.disposeNow();
	}

	@Test
	public void testProtocolH2SslConfiguration() {
		DisposableServer disposableServer =
				server.protocol(HttpProtocol.H2)
				      .secure(spec -> spec.sslContext(builder))
				      .bindNow();
		assertEquals(2, protocols.size());
		assertTrue(protocols.contains("h2"));
		assertTrue(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext);
		disposableServer.disposeNow();
	}

	@Test
	public void testSslConfigurationProtocolH2_1() {
		DisposableServer disposableServer =
				server.secure(spec -> spec.sslContext(builder))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertEquals(2, protocols.size());
		assertTrue(protocols.contains("h2"));
		assertTrue(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext);
		disposableServer.disposeNow();
	}

	@Test
	public void testSslConfigurationProtocolH2_2() {
		DisposableServer disposableServer =
				server.protocol(HttpProtocol.HTTP11)
				      .secure(spec -> spec.sslContext(builder))
				      .protocol(HttpProtocol.H2)
				      .bindNow();
		assertEquals(2, protocols.size());
		assertTrue(protocols.contains("h2"));
		assertTrue(io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
		                                       sslContext instanceof OpenSslContext :
		                                       sslContext instanceof JdkSslContext);
		disposableServer.disposeNow();
	}
}
