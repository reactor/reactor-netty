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
package reactor.netty.tcp;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import java.io.File;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;

/**
 * SslContext builder that does not provide any default configuration.
 *
 * @author Violeta Georgieva
 * @since 1.0.6
 */
public final class DefaultSslContextSpec extends AbstractProtocolSslContextSpec<DefaultSslContextSpec> {

	/**
	 * Creates a builder for new client-side {@link SslContext}.
	 *
	 * @return {@literal this}
	 */
	public static DefaultSslContextSpec forClient() {
		return new DefaultSslContextSpec(SslContextBuilder.forClient());
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(File, File)
	 */
	public static DefaultSslContextSpec forServer(File keyCertChainFile, File keyFile) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(keyCertChainFile, keyFile));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(File, File, String)
	 */
	public static DefaultSslContextSpec forServer(File keyCertChainFile, File keyFile, String keyPassword) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(keyCertChainFile, keyFile, keyPassword));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(InputStream, InputStream)
	 */
	public static DefaultSslContextSpec forServer(InputStream keyCertChainInputStream, InputStream keyInputStream) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(keyCertChainInputStream, keyInputStream));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(InputStream, InputStream, String)
	 */
	public static DefaultSslContextSpec forServer(
			InputStream keyCertChainInputStream, InputStream keyInputStream, String keyPassword) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(keyCertChainInputStream, keyInputStream, keyPassword));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(KeyManager)
	 */
	public static DefaultSslContextSpec forServer(KeyManager keyManager) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(keyManager));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(KeyManagerFactory)
	 */
	public static DefaultSslContextSpec forServer(KeyManagerFactory keyManagerFactory) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(keyManagerFactory));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(PrivateKey, Iterable)
	 */
	public static DefaultSslContextSpec forServer(PrivateKey key, Iterable<? extends X509Certificate> keyCertChain) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(key, keyCertChain));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(PrivateKey, String, Iterable)
	 */
	public static DefaultSslContextSpec forServer(
			PrivateKey key, String keyPassword, Iterable<? extends X509Certificate> keyCertChain) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(key, keyPassword, keyCertChain));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(PrivateKey, String, X509Certificate...)
	 */
	public static DefaultSslContextSpec forServer(PrivateKey key, String keyPassword, X509Certificate... keyCertChain) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(key, keyPassword, keyCertChain));
	}

	/**
	 * Creates a builder for new server-side {@link SslContext}.
	 *
	 * @see SslContextBuilder#forServer(PrivateKey, X509Certificate...)
	 */
	public static DefaultSslContextSpec forServer(PrivateKey key, X509Certificate... keyCertChain) {
		return new DefaultSslContextSpec(SslContextBuilder.forServer(key, keyCertChain));
	}

	DefaultSslContextSpec(SslContextBuilder sslContextBuilder) {
		super(sslContextBuilder);
	}

	@Override
	public DefaultSslContextSpec get() {
		return this;
	}

	@Override
	protected Consumer<SslContextBuilder> defaultConfiguration() {
		return DEFAULT_CONFIGURATOR;
	}

	static final Consumer<SslContextBuilder> DEFAULT_CONFIGURATOR = sslCtxBuilder -> {};
}
