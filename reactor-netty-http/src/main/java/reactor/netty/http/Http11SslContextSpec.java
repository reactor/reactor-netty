/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.netty.tcp.AbstractProtocolSslContextSpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.io.File;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;

import static io.netty.handler.ssl.SslProvider.JDK;
import static io.netty.handler.ssl.SslProvider.OPENSSL;

/**
 * SslContext builder that provides default configuration specific to HTTP/1.x as follows:
 * <ul>
 *     <li>{@link io.netty.handler.ssl.SslProvider} will be set depending on {@code OpenSsl.isAvailable()}</li>
 *     <li>The default cipher suites will be used</li>
 *     <li>Application protocol negotiation configuration is disabled</li>
 * </ul>
 * <p>The default configuration is applied prior any other custom configuration.</p>
 *
 * @author Violeta Georgieva
 * @since 1.0.6
 */
public final class Http11SslContextSpec extends AbstractProtocolSslContextSpec<Http11SslContextSpec> {

	/**
	 * Creates a builder for new client-side {@link SslContext}.
	 *
	 * @return {@literal this}
	 */
	public static Http11SslContextSpec forClient() {
		return new Http11SslContextSpec(SslContextBuilder.forClient());
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(File, File)
	 */
	public static Http11SslContextSpec forServer(File keyCertChainFile, File keyFile) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(keyCertChainFile, keyFile));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(File, File, String)
	 */
	public static Http11SslContextSpec forServer(File keyCertChainFile, File keyFile, String keyPassword) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(keyCertChainFile, keyFile, keyPassword));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(InputStream, InputStream)
	 */
	public static Http11SslContextSpec forServer(InputStream keyCertChainInputStream, InputStream keyInputStream) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(keyCertChainInputStream, keyInputStream));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(InputStream, InputStream, String)
	 */
	public static Http11SslContextSpec forServer(
			InputStream keyCertChainInputStream, InputStream keyInputStream, String keyPassword) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(keyCertChainInputStream, keyInputStream, keyPassword));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(KeyManager)
	 */
	public static Http11SslContextSpec forServer(KeyManager keyManager) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(keyManager));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(KeyManagerFactory)
	 */
	public static Http11SslContextSpec forServer(KeyManagerFactory keyManagerFactory) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(keyManagerFactory));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(PrivateKey, Iterable)
	 */
	public static Http11SslContextSpec forServer(PrivateKey key, Iterable<? extends X509Certificate> keyCertChain) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(key, keyCertChain));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(PrivateKey, String, Iterable)
	 */
	public static Http11SslContextSpec forServer(
			PrivateKey key, String keyPassword, Iterable<? extends X509Certificate> keyCertChain) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(key, keyPassword, keyCertChain));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(PrivateKey, String, X509Certificate...)
	 */
	public static Http11SslContextSpec forServer(PrivateKey key, String keyPassword, X509Certificate... keyCertChain) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(key, keyPassword, keyCertChain));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(PrivateKey, X509Certificate...)
	 */
	public static Http11SslContextSpec forServer(PrivateKey key, X509Certificate... keyCertChain) {
		return new Http11SslContextSpec(SslContextBuilder.forServer(key, keyCertChain));
	}

	Http11SslContextSpec(SslContextBuilder sslContextBuilder) {
		super(sslContextBuilder);
	}

	@Override
	public Http11SslContextSpec get() {
		return this;
	}

	@Override
	protected Consumer<SslContextBuilder> defaultConfiguration() {
		return DEFAULT_CONFIGURATOR;
	}

	static final Consumer<SslContextBuilder> DEFAULT_CONFIGURATOR =
			sslCtxBuilder ->
					sslCtxBuilder.sslProvider(OpenSsl.isAvailable() ? OPENSSL : JDK)
					             .ciphers(null, IdentityCipherSuiteFilter.INSTANCE)
					             .applicationProtocolConfig(null);
}
