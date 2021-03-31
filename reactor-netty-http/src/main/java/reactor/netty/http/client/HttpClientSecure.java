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
package reactor.netty.http.client;

import java.util.function.Consumer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import io.netty.handler.ssl.SslHandler;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.tcp.SslProvider;

/**
 * Initializes the default {@link SslProvider} for the HTTP client.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class HttpClientSecure {

	private HttpClientSecure() {
	}

	static SslProvider defaultSslProvider(HttpClientConfig config) {
		if (config.checkProtocol(HttpClientConfig.h2)) {
			return DEFAULT_HTTP2_SSL_PROVIDER;
		}
		else {
			return DEFAULT_HTTP_SSL_PROVIDER;
		}
	}

	@SuppressWarnings("ReferenceEquality")
	static boolean hasDefaultSslProvider(HttpClientConfig config) {
		// Reference comparison is deliberate
		return DEFAULT_HTTP_SSL_PROVIDER == config.sslProvider || DEFAULT_HTTP2_SSL_PROVIDER == config.sslProvider;
	}

	static SslProvider sslProvider(SslProvider sslProvider) {
		return SslProvider.addHandlerConfigurator(sslProvider, DEFAULT_HOSTNAME_VERIFICATION);
	}

	static final Consumer<? super SslHandler> DEFAULT_HOSTNAME_VERIFICATION = handler -> {
		SSLEngine sslEngine = handler.engine();
		SSLParameters sslParameters = sslEngine.getSSLParameters();
		sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
		sslEngine.setSSLParameters(sslParameters);
	};

	static final SslProvider HTTP2_SSL_PROVIDER;
	static {
		SslProvider sslProvider;
		try {
			sslProvider = SslProvider.builder()
			                         .sslContext(Http2SslContextSpec.forClient())
			                         .build();
		}
		catch (Exception e) {
			sslProvider = null;
		}
		HTTP2_SSL_PROVIDER = sslProvider;
	}

	static final SslProvider DEFAULT_HTTP_SSL_PROVIDER =
			SslProvider.addHandlerConfigurator(SslProvider.defaultClientProvider(), DEFAULT_HOSTNAME_VERIFICATION);

	static final SslProvider DEFAULT_HTTP2_SSL_PROVIDER =
			SslProvider.addHandlerConfigurator(HTTP2_SSL_PROVIDER, DEFAULT_HOSTNAME_VERIFICATION);
}
