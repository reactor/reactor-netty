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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.resources.LoopResources;

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
	 * Set or add a callback called before {@link SocketAddress} is resolved.
	 *
	 * @param doOnResolve a consumer observing resolve events
	 * @return a new {@link ClientTransport} reference
	 * @since 1.0.1
	 */
	public final T doOnResolve(Consumer<? super Connection> doOnResolve) {
		Objects.requireNonNull(doOnResolve, "doOnResolve");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		Consumer<Connection> current = (Consumer<Connection>) configuration().doOnResolve;
		dup.configuration().doOnResolve = current == null ? doOnResolve : current.andThen(doOnResolve);
		return dup;
	}

	/**
	 * Set or add a callback called after {@link SocketAddress} is resolved successfully.
	 *
	 * @param doAfterResolve a consumer observing resolved events
	 * @return a new {@link ClientTransport} reference
	 * @since 1.0.1
	 */
	public final T doAfterResolve(BiConsumer<? super Connection, ? super SocketAddress> doAfterResolve) {
		Objects.requireNonNull(doAfterResolve, "doAfterResolve");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<Connection, SocketAddress> current =
				(BiConsumer<Connection, SocketAddress>) configuration().doAfterResolve;
		dup.configuration().doAfterResolve = current == null ? doAfterResolve : current.andThen(doAfterResolve);
		return dup;
	}

	/**
	 * Set or add a callback called if an exception happens while resolving to a {@link SocketAddress}.
	 *
	 * @param doOnResolveError a consumer observing resolve error events
	 * @return a new {@link ClientTransport} reference
	 * @since 1.0.1
	 */
	public final T doOnResolveError(BiConsumer<? super Connection, ? super Throwable> doOnResolveError) {
		Objects.requireNonNull(doOnResolveError, "doOnResolveError");
		T dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<Connection, Throwable> current =
				(BiConsumer<Connection, Throwable>) configuration().doOnResolveError;
		dup.configuration().doOnResolveError = current == null ? doOnResolveError : current.andThen(doOnResolveError);
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
			if (dup.configuration().resolver == NoopAddressResolverGroup.INSTANCE) {
				dup.configuration().resolver = null;
			}
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
		CONF conf = dup.configuration();
		conf.proxyProvider = builder.build();
		if (conf.resolver == null) {
			conf.resolver = NoopAddressResolverGroup.INSTANCE;
		}
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
		NameResolverProvider provider = builder.build();
		if (provider.equals(configuration().nameResolverProvider)) {
			@SuppressWarnings("unchecked")
			T dup = (T) this;
			return dup;
		}
		T dup = duplicate();
		CONF conf = dup.configuration();
		conf.nameResolverProvider = provider;
		conf.resolver = provider.newNameResolverGroup(conf.loopResources(), conf.preferNative);
		return dup;
	}

	@Override
	public T runOn(LoopResources loopResources, boolean preferNative) {
		T dup = super.runOn(loopResources, preferNative);
		CONF conf = dup.configuration();
		if (conf.nameResolverProvider != null) {
			conf.resolver = conf.nameResolverProvider.newNameResolverGroup(conf.loopResources(), conf.preferNative);
		}
		return dup;
	}

	/**
	 * Based on the actual configuration, returns a {@link Mono} that triggers:
	 * <ul>
	 *     <li>an initialization of the event loop group</li>
	 *     <li>an initialization of the host name resolver</li>
	 *     <li>loads the necessary native libraries for the transport</li>
	 * </ul>
	 * By default, when method is not used, the {@code connect operation} absorbs the extra time needed to initialize and
	 * load the resources.
	 *
	 * @return a {@link Mono} representing the completion of the warmup
	 * @since 1.0.3
	 */
	public Mono<Void> warmup() {
		return Mono.fromRunnable(() -> {
			configuration().eventLoopGroup();

			// By default the host name resolver uses the event loop group configured on client level
			configuration().resolverInternal();
		});
	}
}