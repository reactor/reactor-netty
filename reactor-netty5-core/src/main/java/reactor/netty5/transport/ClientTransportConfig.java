/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.transport;

import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.ServerChannel;
import io.netty5.channel.ServerChannelFactory;
import io.netty5.channel.group.ChannelGroup;
import io.netty5.resolver.AddressResolverGroup;
import io.netty5.resolver.dns.DnsAddressResolverGroup;
import reactor.netty5.ChannelPipelineConfigurer;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.channel.MicrometerChannelMetricsRecorder;
import reactor.netty5.resources.ConnectionProvider;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.internal.util.MapUtils;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulate all necessary configuration for client transport. The public API is read-only.
 *
 * @param <CONF> Configuration implementation
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public abstract class ClientTransportConfig<CONF extends TransportConfig> extends TransportConfig {

	@Override
	public int channelHash() {
		int result = super.channelHash();
		result = 31 * result + Objects.hashCode(proxyProvider);
		result = 31 * result + Objects.hashCode(resolver);
		return result;
	}

	/**
	 * Return the {@link ConnectionProvider}.
	 *
	 * @return the {@link ConnectionProvider}
	 */
	public ConnectionProvider connectionProvider() {
		return connectionProvider;
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	public final @Nullable Consumer<? super CONF> doOnConnect() {
		return doOnConnect;
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	public final @Nullable Consumer<? super Connection> doOnConnected() {
		return doOnConnected;
	}

	/**
	 * Return the configured callback or null.
	 *
	 * @return the configured callback or null
	 */
	public final @Nullable Consumer<? super Connection> doOnDisconnected() {
		return doOnDisconnected;
	}

	/**
	 * Return true if that {@link ClientTransportConfig} is configured with a proxy.
	 *
	 * @return true if that {@link ClientTransportConfig} is configured with a proxy
	 */
	public final boolean hasProxy() {
		return proxyProvider != null || proxyProviderSupplier != null;
	}

	/**
	 * Return the configured {@link NameResolverProvider} or null.
	 *
	 * @return the configured {@link NameResolverProvider} or null
	 */
	public @Nullable NameResolverProvider getNameResolverProvider() {
		return nameResolverProvider;
	}

	/**
	 * Return the {@link ProxyProvider} if any or null.
	 *
	 * @return the {@link ProxyProvider} if any or null
	 */
	public final @Nullable ProxyProvider proxyProvider() {
		return proxyProvider;
	}

	/**
	 * Return the {@link ProxyProvider} supplier if any or null.
	 *
	 * @return the {@link ProxyProvider} supplier if any or null
	 */
	public final @Nullable Supplier<ProxyProvider> proxyProviderSupplier() {
		return proxyProviderSupplier;
	}

	/**
	 * Return the remote configured {@link SocketAddress}.
	 *
	 * @return the remote configured {@link SocketAddress}
	 */
	public final Supplier<? extends SocketAddress> remoteAddress() {
		return remoteAddress;
	}

	/**
	 * Return the configured {@link AddressResolverGroup} or null.
	 * If there is no {@link AddressResolverGroup} configured, the default will be used.
	 *
	 * @return the configured {@link AddressResolverGroup} or null
	 */
	public final @Nullable AddressResolverGroup<?> resolver() {
		return resolver;
	}


	// Package private creators

	final ConnectionProvider connectionProvider;

	Consumer<? super CONF>                   doOnConnect;
	Consumer<? super Connection>             doOnConnected;
	Consumer<? super Connection>             doOnDisconnected;
	Consumer<? super Connection>             doOnResolve;
	BiConsumer<? super Connection, ? super SocketAddress> doAfterResolve;
	BiConsumer<? super Connection, ? super Throwable> doOnResolveError;
	NameResolverProvider                     nameResolverProvider;
	ProxyProvider                            proxyProvider;
	Supplier<ProxyProvider>                  proxyProviderSupplier;
	Supplier<? extends SocketAddress>        remoteAddress;
	AddressResolverGroup<?>                  resolver;

	protected ClientTransportConfig(ConnectionProvider connectionProvider, Map<ChannelOption<?>, ?> options,
			Supplier<? extends SocketAddress> remoteAddress) {
		super(options);
		this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
		this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
	}

	protected ClientTransportConfig(ClientTransportConfig<CONF> parent) {
		super(parent);
		this.connectionProvider = parent.connectionProvider;
		this.doOnConnect = parent.doOnConnect;
		this.doOnConnected = parent.doOnConnected;
		this.doOnDisconnected = parent.doOnDisconnected;
		this.doOnResolve = parent.doOnResolve;
		this.doAfterResolve = parent.doAfterResolve;
		this.doOnResolveError = parent.doOnResolveError;
		this.nameResolverProvider = parent.nameResolverProvider;
		this.proxyProvider = parent.proxyProvider;
		this.proxyProviderSupplier = parent.proxyProviderSupplier;
		this.remoteAddress = parent.remoteAddress;
		this.resolver = parent.resolver;
	}

	/**
	 * Provides a global {@link AddressResolverGroup} that is shared amongst all clients.
	 *
	 * @return the global {@link AddressResolverGroup}
	 */
	protected abstract AddressResolverGroup<?> defaultAddressResolverGroup();

	@Override
	protected ConnectionObserver defaultConnectionObserver() {
		if (channelGroup() == null && doOnConnected() == null && doOnDisconnected() == null) {
			return ConnectionObserver.emptyListener();
		}
		return new ClientTransportDoOn(channelGroup(), doOnConnected(), doOnDisconnected());
	}

	@Override
	protected ChannelPipelineConfigurer defaultOnChannelInit() {
		if (proxyProvider != null) {
			return new ClientTransportChannelInitializer(proxyProvider);
		}
		else {
			return ChannelPipelineConfigurer.emptyConfigurer();
		}
	}

	@Override
	protected EventLoopGroup eventLoopGroup() {
		return loopResources().onClient(isPreferNative());
	}

	protected void proxyProvider(ProxyProvider proxyProvider) {
		this.proxyProvider = proxyProvider;
	}

	protected AddressResolverGroup<?> resolverInternal() {
		AddressResolverGroup<?> resolverGroup = resolver != null ? resolver : defaultAddressResolverGroup();
		if (metricsRecorder != null) {
			if (metricsRecorder instanceof MicrometerChannelMetricsRecorder micrometerChannelMetricsRecorder) {
				return MicrometerAddressResolverGroupMetrics.getOrCreate(resolverGroup, micrometerChannelMetricsRecorder);
			}
			else {
				return AddressResolverGroupMetrics.getOrCreate(resolverGroup, metricsRecorder);
			}
		}
		else {
			return resolverGroup;
		}
	}

	@Override
	protected Class<? extends ServerChannel> serverChannelType() {
		throw new UnsupportedOperationException();
	}

	@Override
	protected ServerChannelFactory<? extends ServerChannel> serverConnectionFactory(@Nullable ProtocolFamily protocolFamily) {
		throw new UnsupportedOperationException();
	}

	static final ConcurrentMap<Integer, DnsAddressResolverGroup> RESOLVERS_CACHE = new ConcurrentHashMap<>();

	static DnsAddressResolverGroup getOrCreateResolver(
			NameResolverProvider nameResolverProvider,
			LoopResources loopResources,
			boolean preferNative) {
		return MapUtils.computeIfAbsent(RESOLVERS_CACHE, Objects.hash(nameResolverProvider, loopResources, preferNative),
				key -> nameResolverProvider.newNameResolverGroup(loopResources, preferNative));
	}

	static final NameResolverProvider DEFAULT_NAME_RESOLVER_PROVIDER = NameResolverProvider.builder().build();

	static final class ClientTransportChannelInitializer implements ChannelPipelineConfigurer {

		final ProxyProvider proxyProvider;

		ClientTransportChannelInitializer(ProxyProvider proxyProvider) {
			this.proxyProvider = proxyProvider;
		}

		@Override
		public void onChannelInit(ConnectionObserver connectionObserver, Channel channel, SocketAddress remoteAddress) {
			if (proxyProvider.shouldProxy(remoteAddress)) {
				proxyProvider.addProxyHandler(channel);
			}
		}
	}

	static final class ClientTransportDoOn implements ConnectionObserver {

		final ChannelGroup channelGroup;
		final Consumer<? super Connection> doOnConnected;
		final Consumer<? super Connection> doOnDisconnected;

		ClientTransportDoOn(@Nullable ChannelGroup channelGroup,
				@Nullable Consumer<? super Connection> doOnConnected,
				@Nullable Consumer<? super Connection> doOnDisconnected) {
			this.channelGroup = channelGroup;
			this.doOnConnected = doOnConnected;
			this.doOnDisconnected = doOnDisconnected;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (channelGroup != null && newState == State.CONNECTED) {
				channelGroup.add(connection.channel());
				return;
			}
			if (doOnConnected != null && newState == State.CONFIGURED) {
				doOnConnected.accept(connection);
				return;
			}
			if (doOnDisconnected != null) {
				if (newState == State.DISCONNECTING) {
					connection.onDispose(() -> doOnDisconnected.accept(connection));
				}
				else if (newState == State.RELEASED) {
					doOnDisconnected.accept(connection);
				}
			}
		}
	}
}