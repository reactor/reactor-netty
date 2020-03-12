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

import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientSecure extends HttpClientOperator {

	static HttpClient secure(HttpClient client, Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");

		SslProvider.SslContextSpec builder = SslProvider.builder();
		sslProviderBuilder.accept(builder);
		return new HttpClientSecure(client, ((SslProvider.Builder) builder).build());
	}

	final SslProvider sslProvider;

	HttpClientSecure(HttpClient client, @Nullable SslProvider sslProvider) {
		super(client);
		this.sslProvider = sslProvider;
	}

	@Override
	protected TcpClient tcpConfiguration() {
		if (sslProvider == null) {
			return source.tcpConfiguration()
			             .secure(DEFAULT_HTTP_SSL_PROVIDER);
		}
		return source.tcpConfiguration().secure(
				SslProvider.addHandlerConfigurator(sslProvider, DEFAULT_HOSTNAME_VERIFICATION));
	}

	static final Consumer<? super SslHandler> DEFAULT_HOSTNAME_VERIFICATION = handler -> {
		SSLEngine sslEngine = handler.engine();
		SSLParameters sslParameters = sslEngine.getSSLParameters();
		sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
		sslEngine.setSSLParameters(sslParameters);
	};

	static final SslProvider DEFAULT_HTTP_SSL_PROVIDER =
			SslProvider.addHandlerConfigurator(SslProvider.defaultClientProvider(), DEFAULT_HOSTNAME_VERIFICATION);

	static final SslContext DEFAULT_CLIENT_HTTP2_CONTEXT;
	static {
		SslContext sslCtx;
		try {
			io.netty.handler.ssl.SslProvider provider =
					io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
							io.netty.handler.ssl.SslProvider.OPENSSL :
							io.netty.handler.ssl.SslProvider.JDK;
			sslCtx =
					SslContextBuilder.forClient()
					                 .sslProvider(provider)
					                 .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
					                 .applicationProtocolConfig(new ApplicationProtocolConfig(
							                 ApplicationProtocolConfig.Protocol.ALPN,
							                 ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
							                 ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
							                 ApplicationProtocolNames.HTTP_2,
							                 ApplicationProtocolNames.HTTP_1_1))
					                 .build();
		}
		catch (Exception e) {
			sslCtx = null;
		}
		DEFAULT_CLIENT_HTTP2_CONTEXT = sslCtx;
	}

	static final Consumer<SslProvider.SslContextSpec> SSL_DEFAULT_SPEC_HTTP2 =
			sslProviderBuilder -> sslProviderBuilder.sslContext(
					DEFAULT_CLIENT_HTTP2_CONTEXT);
}
