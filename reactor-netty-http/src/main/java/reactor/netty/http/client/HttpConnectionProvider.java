/*
 * Copyright (c) 2020-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.resolver.AddressResolverGroup;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.TransportConfig;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

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
		boolean isHttp3 = ((HttpClientConfig) config)._protocols == HttpClientConfig.h3;
		if (((HttpClientConfig) config)._protocols == HttpClientConfig.h11) {
			return http1ConnectionProvider().acquire(config, connectionObserver, remoteAddress, resolverGroup);
		}
		else if (http1ConnectionProvider == null) {
			if (isHttp3) {
				return HttpResources.get().getOrCreateHttp3ConnectionProvider(HTTP3_CONNECTION_PROVIDER_FACTORY)
						.acquire(config, connectionObserver, remoteAddress, resolverGroup);
			}
			else {
				return HttpResources.get().getOrCreateHttp2ConnectionProvider(HTTP2_CONNECTION_PROVIDER_FACTORY)
						.acquire(config, connectionObserver, remoteAddress, resolverGroup);
			}
		}
		else {
			return isHttp3 ?
					getOrCreateHttp3().acquire(config, connectionObserver, remoteAddress, resolverGroup) :
					getOrCreateHttp2().acquire(config, connectionObserver, remoteAddress, resolverGroup);
		}
	}

	@Override
	public void disposeWhen(SocketAddress address) {
		http1ConnectionProvider().disposeWhen(address);
	}

	@Override
	public int maxConnections() {
		return http1ConnectionProvider().maxConnections();
	}

	@Override
	public Map<SocketAddress, Integer> maxConnectionsPerHost() {
		return http1ConnectionProvider().maxConnectionsPerHost();
	}

	final ConnectionProvider http1ConnectionProvider;

	final AtomicReference<ConnectionProvider> http2ConnectionProvider = new AtomicReference<>();

	final AtomicReference<ConnectionProvider> http3ConnectionProvider = new AtomicReference<>();

	HttpConnectionProvider() {
		this(null);
	}

	HttpConnectionProvider(@Nullable ConnectionProvider http1ConnectionProvider) {
		this.http1ConnectionProvider = http1ConnectionProvider;
	}

	ConnectionProvider getOrCreateHttp2() {
		ConnectionProvider provider = http2ConnectionProvider.get();
		if (provider == null) {
			ConnectionProvider newProvider = HTTP2_CONNECTION_PROVIDER_FACTORY.apply(http1ConnectionProvider);
			if (!http2ConnectionProvider.compareAndSet(null, newProvider)) {
				newProvider.dispose();
			}
			provider = getOrCreateHttp2();
		}
		return provider;
	}

	ConnectionProvider getOrCreateHttp3() {
		ConnectionProvider provider = http3ConnectionProvider.get();
		if (provider == null) {
			ConnectionProvider newProvider = HTTP3_CONNECTION_PROVIDER_FACTORY.apply(http1ConnectionProvider);
			if (!http3ConnectionProvider.compareAndSet(null, newProvider)) {
				newProvider.dispose();
			}
			provider = getOrCreateHttp3();
		}
		return provider;
	}

	ConnectionProvider http1ConnectionProvider() {
		return http1ConnectionProvider != null ? http1ConnectionProvider : HttpResources.get();
	}

	static final Function<ConnectionProvider, ConnectionProvider> HTTP2_CONNECTION_PROVIDER_FACTORY =
			Http2ConnectionProvider::new;

	static final Function<ConnectionProvider, ConnectionProvider> HTTP3_CONNECTION_PROVIDER_FACTORY =
			Http3ConnectionProvider::new;
}
