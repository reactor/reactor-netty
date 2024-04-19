/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import io.netty.handler.ssl.SslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import reactor.netty.tcp.SslProvider;
import reactor.util.annotation.Incubating;
import reactor.util.annotation.Nullable;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.function.Consumer;

import static io.netty.incubator.codec.http3.Http3.supportedApplicationProtocols;

/**
 * SslContext builder that provides default configuration specific to HTTP/3 as follows:
 * <ul>
 *     <li>Supported application protocols</li>
 * </ul>
 * <p>The default configuration is applied prior any other custom configuration.</p>
 *
 * @author Violeta Georgieva
 * @since 1.2.0
 * @see io.netty.incubator.codec.http3.Http3#supportedApplicationProtocols()
 */
@Incubating
public final class Http3SslContextSpec implements SslProvider.GenericSslContextSpec<QuicSslContextBuilder> {

	/**
	 * Creates a builder for new client-side {@link SslContext}.
	 *
	 * @see QuicSslContextBuilder#forClient()
	 */
	public static Http3SslContextSpec forClient() {
		return new Http3SslContextSpec(QuicSslContextBuilder.forClient());
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see QuicSslContextBuilder#forServer(File, String, File)
	 */
	public static Http3SslContextSpec forServer(File keyFile, @Nullable String keyPassword, File certChainFile) {
		return new Http3SslContextSpec(QuicSslContextBuilder.forServer(keyFile, keyPassword, certChainFile));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see QuicSslContextBuilder#forServer(KeyManager, String)
	 */
	public static Http3SslContextSpec forServer(KeyManager keyManager, @Nullable String keyPassword) {
		return new Http3SslContextSpec(QuicSslContextBuilder.forServer(keyManager, keyPassword));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see QuicSslContextBuilder#forServer(KeyManagerFactory, String)
	 */
	public static Http3SslContextSpec forServer(KeyManagerFactory keyManagerFactory, @Nullable String password) {
		return new Http3SslContextSpec(QuicSslContextBuilder.forServer(keyManagerFactory, password));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see QuicSslContextBuilder#forServer(PrivateKey, String, X509Certificate...)
	 */
	public static Http3SslContextSpec forServer(PrivateKey key, @Nullable String keyPassword, X509Certificate... certChain) {
		return new Http3SslContextSpec(QuicSslContextBuilder.forServer(key, keyPassword, certChain));
	}

	@Override
	public Http3SslContextSpec configure(Consumer<QuicSslContextBuilder> sslCtxBuilder) {
		Objects.requireNonNull(sslCtxBuilder, "sslCtxBuilder");
		sslCtxBuilder.accept(sslContextBuilder);
		return this;
	}

	@Override
	public SslContext sslContext() throws SSLException {
		return sslContextBuilder.build();
	}

	final QuicSslContextBuilder sslContextBuilder;

	Http3SslContextSpec(QuicSslContextBuilder sslContextBuilder) {
		this.sslContextBuilder = sslContextBuilder;
		configure(DEFAULT_CONFIGURATOR);
	}

	static final Consumer<QuicSslContextBuilder> DEFAULT_CONFIGURATOR =
			sslCtxBuilder -> sslCtxBuilder.applicationProtocols(supportedApplicationProtocols());
}
