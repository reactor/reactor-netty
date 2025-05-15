/*
 * Copyright (c) 2018-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import javax.net.ssl.SSLException;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import org.junit.jupiter.api.BeforeAll;
import reactor.netty.http.client.HttpClient;

class HttpsSendFileTests extends HttpSendFileTests {

	static X509Bundle ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
	}

	@Override
	protected HttpServer customizeServerOptions(HttpServer server) {
		try {
			SslContext ctx = SslContextBuilder.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem()).build();
			return server.secure(ssl -> ssl.sslContext(ctx));
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected HttpClient customizeClientOptions(HttpClient httpClient) {
		try {
			SslContext ctx = SslContextBuilder.forClient()
			                                  .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
			return httpClient.secure(ssl -> ssl.sslContext(ctx));
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}
}
