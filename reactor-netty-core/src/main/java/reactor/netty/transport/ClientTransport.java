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
package reactor.netty.transport;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.resolver.AddressResolverGroup;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;

/**
 * A generic client {@link Transport} that will {@link #connect()} to a remote address and provide a {@link Connection}
 *
 * @param <T> ClientTransport implementation
 * @param <CONF> Client Configuration implementation
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public abstract class ClientTransport<T extends ClientTransport<T, CONF>,
		CONF extends ClientTransportConfig<CONF>>
		extends Transport<T, CONF> {

	/**
	 * Connect the {@link ClientTransport} and return a {@link Mono} of {@link Connection}. If
	 * {@link Mono} is cancelled, the underlying connection will be aborted. Once the
	 * {@link Connection} has been emitted and is not necessary anymore, disposing must be
	 * done by the user via {@link Connection#dispose()}.
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	protected Mono<? extends Connection> connect() {
		CONF config = configuration();

		ConnectionObserver observer = config.defaultConnectionObserver().then(config.observer);

		AddressResolverGroup<?> resolver = config.resolverInternal();

		Mono<? extends Connection> mono = config.connectionProvider()
		                                        .acquire(config, observer, config.remoteAddress, resolver);
		if (config.doOnConnect != null) {
			mono = mono.doOnSubscribe(s -> config.doOnConnect.accept(config));
		}
		return mono;
	}

	/**
	 * Block the {@link ClientTransport} and return a {@link Connection}. Disposing must be
	 * done by the user via {@link Connection#dispose()}. The max connection
	 * timeout is 45 seconds.
	 *
	 * @return a {@link Connection}
	 */
	protected Connection connectNow() {
		return connectNow(Duration.ofSeconds(45));
	}

	/**
	 * Block the {@link ClientTransport} and return a {@link Connection}. Disposing must be
	 * done by the user via {@link Connection#dispose()}.
	 *
	 * @param timeout connect timeout (resolution: ns)
	 * @return a {@link Connection}
	 */
	protected Connection connectNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		try {
			return Objects.requireNonNull(connect().block(timeout), "aborted");
		}
		catch (IllegalStateException e) {
			if (e.getMessage().contains("blocking read")) {
				throw new IllegalStateException(getClass().getSimpleName() + " couldn't be started within " + timeout.toMillis() + "ms");
			}
			throw e;
		}
	}

	/**
	 * Set or add a callback called when {@link ClientTransport} is about to connect to the remote endpoint.
	 *
	 * @param doOnConnect a consumer observing connect events
	 * @return a new {@link ClientTransport} reference
	 */
	public T doOnConnect(Consumer<? super CONF> doOnConnect) {
		Objects.requireNonNull(doOnConnect, "doOnConnect");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<CONF> current = (Consumer<CONF>) configuration().doOnConnect;
		dup.configuration().doOnConnect = current == null ? doOnConnect : current.andThen(doOnConnect);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link Connection} has been connected.
	 *
	 * @param doOnConnected a consumer observing connected events
	 * @return a new {@link ClientTransport} reference
	 */
	public T doOnConnected(Consumer<? super Connection> doOnConnected) {
		Objects.requireNonNull(doOnConnected, "doOnConnected");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<Connection> current = (Consumer<Connection>) configuration().doOnConnected;
		dup.configuration().doOnConnected = current == null ? doOnConnected : current.andThen(doOnConnected);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link Connection} has been disconnected.
	 *
	 * @param doOnDisconnected a consumer observing disconnected events
	 * @return a new {@link ClientTransport} reference
	 */
	public T doOnDisconnected(Consumer<? super Connection> doOnDisconnected) {
		Objects.requireNonNull(doOnDisconnected, "doOnDisconnected");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<Connection> current = (Consumer<Connection>) configuration().doOnDisconnected;
		dup.configuration().doOnDisconnected = current == null ? doOnDisconnected : current.andThen(doOnDisconnected);
		return dup;
	}

	/**
	 * The host to which this client should connect.
	 *
	 * @param host the host to connect to
	 * @return a new {@link ClientTransport} reference
	 */
	public T host(String host) {
		Objects.requireNonNull(host, "host");
		return remoteAddress(() -> AddressUtils.updateHost(configuration().remoteAddress(), host));
	}

	/**
	 * Remove any previously applied Proxy configuration customization
	 *
	 * @return a new {@link ClientTransport} reference
	 */
	public T noProxy() {
		if (configuration().hasProxy()) {
			T dup = duplicate();
			dup.configuration().proxyProvider = null;
			return dup;
		}
		@SuppressWarnings("unchecked")
		T dup = (T) this;
		return dup;
	}

	/**
	 * The port to which this client should connect.
	 *
	 * @param port the port to connect to
	 * @return a new {@link ClientTransport} reference
	 */
	public T port(int port) {
		return remoteAddress(() -> AddressUtils.updatePort(configuration().remoteAddress(), port));
	}

	/**
	 * Apply a proxy configuration
	 *
	 * @param proxyOptions the proxy configuration callback
	 * @return a new {@link ClientTransport} reference
	 */
	public T proxy(Consumer<? super ProxyProvider.TypeSpec> proxyOptions) {
		Objects.requireNonNull(proxyOptions, "proxyOptions");
		T dup = duplicate();
		ProxyProvider.Build builder = (ProxyProvider.Build) ProxyProvider.builder();
		proxyOptions.accept(builder);
		dup.configuration().proxyProvider = builder.build();
		return dup;
	}

	/**
	 * The address to which this client should connect on each subscribe.
	 *
	 * @param remoteAddressSupplier A supplier of the address to connect to.
	 * @return a new {@link ClientTransport}
	 */
	public T remoteAddress(Supplier<? extends SocketAddress> remoteAddressSupplier) {
		Objects.requireNonNull(remoteAddressSupplier, "remoteAddressSupplier");
		T dup = duplicate();
		dup.configuration().remoteAddress = remoteAddressSupplier;
		return dup;
	}

	/**
	 * Assign an {@link AddressResolverGroup}.
	 *
	 * @param resolver the new {@link AddressResolverGroup}
	 * @return a new {@link ClientTransport} reference
	 */
	public T resolver(AddressResolverGroup<?> resolver) {
		Objects.requireNonNull(resolver, "resolver");
		T dup = duplicate();
		dup.configuration().resolver = resolver;
		dup.configuration().nameResolverProvider = null;
		return dup;
	}

	/**
	 * Apply a name resolver configuration.
	 *
	 * @param nameResolverSpec the name resolver callback
	 * @return a new {@link ClientTransport} reference
	 */
	public T resolver(Consumer<NameResolverProvider.NameResolverSpec> nameResolverSpec) {
		Objects.requireNonNull(nameResolverSpec, "nameResolverSpec");
		NameResolverProvider.Build builder = new NameResolverProvider.Build();
		nameResolverSpec.accept(builder);
		T dup = duplicate();
		NameResolverProvider provider = builder.build();
		dup.configuration().nameResolverProvider = provider;
		dup.configuration().resolver = provider.newNameResolverGroup(dup.configuration().defaultLoopResources());
		return dup;
	}
}