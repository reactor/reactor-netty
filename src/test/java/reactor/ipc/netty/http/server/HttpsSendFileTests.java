/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.server;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLException;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.BeforeClass;
import reactor.ipc.netty.http.client.HttpClientOptions;

public class HttpsSendFileTests extends HttpSendFileTests {
	static SelfSignedCertificate ssc;

	@BeforeClass
	public static void createSelfSignedCertificate() throws CertificateException, SSLException {
		ssc = new SelfSignedCertificate();
	}

	@Override
	protected void customizeServerOptions(HttpServerOptions.Builder options) {
		try {
			options.sslContext(SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build());
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected void customizeClientOptions(HttpClientOptions.Builder options) {
		try {
			options.sslContext(SslContextBuilder.forClient()
			                                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build());
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}
}
