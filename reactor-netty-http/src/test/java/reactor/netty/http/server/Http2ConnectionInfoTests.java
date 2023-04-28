/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

/**
 * Tests for HTTP/2.0 and {@link ConnectionInfo}
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
class Http2ConnectionInfoTests extends ConnectionInfoTests {
	@Override
	protected HttpClient customizeClientOptions(HttpClient httpClient) {
		try {
			SslContext ctx = SslContextBuilder.forClient()
			                                  .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
			return httpClient.protocol(HttpProtocol.H2)
			                 .secure(ssl -> ssl.sslContext(ctx));
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected HttpServer customizeServerOptions(HttpServer httpServer) {
		try {
			SslContext ctx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
			return httpServer.protocol(HttpProtocol.H2)
			                 .secure(ssl -> ssl.sslContext(ctx));
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	void forwardedHostEmptyHostHeader() {
		// HTTP/2 does not allow ':authority' to be empty
		// https://datatracker.ietf.org/doc/html/rfc9113#section-8.3.1
	}

	@Override
	void noHeadersEmptyHostHeader() {
		// HTTP/2 does not allow ':authority' to be empty
		// https://datatracker.ietf.org/doc/html/rfc9113#section-8.3.1
	}

	@Override
	void xForwardedHostEmptyHostHeader(boolean useCustomForwardedHandler) {
		// HTTP/2 does not allow ':authority' to be empty
		// https://datatracker.ietf.org/doc/html/rfc9113#section-8.3.1
	}
}
