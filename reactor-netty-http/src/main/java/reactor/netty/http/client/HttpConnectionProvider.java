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

import io.netty.resolver.AddressResolverGroup;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.TransportConfig;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static reactor.netty.http.client.Http2Resources.DEFAULT_MAX_HTTP2_CONNECTIONS;

/**
 * {@link ConnectionProvider} for HTTP protocol.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class HttpConnectionProvider implements ConnectionProvider {

	@Override
	public Mono<? extends Connection> acquire(
			TransportConfig config,
			ConnectionObserver connectionObserver,
			@Nullable Supplier<? extends SocketAddress> remoteAddress,
			@Nullable AddressResolverGroup<?> resolverGroup) {
		if (((HttpClientConfig) config)._protocols == HttpClientConfig.h11) {
			return http1ConnectionProvider.acquire(config, connectionObserver, remoteAddress, resolverGroup);
		}
		else {
			return h2ConnectionProviderSupplier.get().acquire(config, connectionObserver, remoteAddress, resolverGroup);
		}
	}

	@Override
	public void disposeWhen(SocketAddress address) {
		http1ConnectionProvider.disposeWhen(address);
	}

	final ConnectionProvider http1ConnectionProvider;
	final Supplier<ConnectionProvider> h2ConnectionProviderSupplier;

	HttpConnectionProvider(ConnectionProvider http1ConnectionProvider) {
		this(http1ConnectionProvider, DEFAULT_MAX_HTTP2_CONNECTIONS);
	}

	HttpConnectionProvider(ConnectionProvider http1ConnectionProvider, int maxHttp2Connections) {
		this(http1ConnectionProvider, () -> getOrCreate(http1ConnectionProvider, maxHttp2Connections));
	}

	HttpConnectionProvider(ConnectionProvider http1ConnectionProvider, Supplier<ConnectionProvider> h2ConnectionProviderSupplier) {
		this.http1ConnectionProvider = http1ConnectionProvider;
		this.h2ConnectionProviderSupplier = h2ConnectionProviderSupplier;
	}

	static ConnectionProvider getOrCreate(ConnectionProvider http1ConnectionProvider, int maxHttp2Connections) {
		ConnectionProvider provider = h2ConnectionProvider.get();
		if (provider == null) {
			h2ConnectionProvider.compareAndSet(null,
					new Http2ConnectionProvider(http1ConnectionProvider, maxHttp2Connections));
			provider = getOrCreate(http1ConnectionProvider, maxHttp2Connections);
		}
		return provider;
	}

	static final AtomicReference<ConnectionProvider> h2ConnectionProvider = new AtomicReference<>();
}
