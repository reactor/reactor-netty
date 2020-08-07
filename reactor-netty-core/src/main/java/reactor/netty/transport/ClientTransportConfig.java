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
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.resolver.AddressResolverGroup;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.annotation.Nullable;

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
		return Objects.hash(super.channelHash(), proxyProvider, nameResolverProvider != null ? nameResolverProvider : resolver);
	}

	/**
	 * Return the {@link ConnectionProvider}
	 *
	 * @return the {@link ConnectionProvider}
	 */
	public final ConnectionProvider connectionProvider() {
		return connectionProvider;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super CONF> doOnConnect() {
		return doOnConnect;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super Connection> doOnConnected() {
		return doOnConnected;
	}

	/**
	 * Return the configured callback or null
	 *
	 * @return the configured callback or null
	 */
	@Nullable
	public final Consumer<? super Connection> doOnDisconnected() {
		return doOnDisconnected;
	}

	/**
	 * Return true if that {@link ClientTransportConfig} is configured with a proxy
	 *
	 * @return true if that {@link ClientTransportConfig} is configured with a proxy
	 */
	public final boolean hasProxy() {
		return proxyProvider != null;
	}

	/**
	 * Return the configured {@link NameResolverProvider} or null
	 *
	 * @return the configured {@link NameResolverProvider} or null
	 */
	@Nullable
	public NameResolverProvider getNameResolverProvider() {
		return nameResolverProvider;
	}

	/**
	 * Return the {@link ProxyProvider} if any or null
	 *
	 * @return the {@link ProxyProvider} if any or null
	 */
	@Nullable
	public final ProxyProvider proxyProvider() {
		return proxyProvider;
	}

	/**
	 * Return the remote configured {@link SocketAddress}
	 *
	 * @return the remote configured {@link SocketAddress}
	 */
	public final Supplier<? extends SocketAddress> remoteAddress() {
		return remoteAddress;
	}

	/**
	 * Return the configured {@link AddressResolverGroup}
	 *
	 * @return the configured {@link AddressResolverGroup}
	 */
	public final AddressResolverGroup<?> resolver() {
		return resolver != null ? resolver : defaultResolver();
	}


	// Package private creators

	final ConnectionProvider connectionProvider;

	Consumer<? super CONF>            doOnConnect;
	Consumer<? super Connection>      doOnConnected;
	Consumer<? super Connection>      doOnDisconnected;
	NameResolverProvider              nameResolverProvider;
	ProxyProvider                     proxyProvider;
	Supplier<? extends SocketAddress> remoteAddress;
	AddressResolverGroup<?>           resolver;

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
		this.nameResolverProvider = parent.nameResolverProvider;
		this.proxyProvider = parent.proxyProvider;
		this.remoteAddress = parent.remoteAddress;
		this.resolver = parent.resolver;
	}

	@Override
	protected Class<? extends Channel> channelType(boolean isDomainSocket) {
		return isDomainSocket ? DomainSocketChannel.class : SocketChannel.class;
	}

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

	/**
	 * Return the configured default {@link AddressResolverGroup}.
	 *
	 * @return the configured default {@link AddressResolverGroup}
	 */
	protected abstract AddressResolverGroup<?> defaultResolver();

	@Override
	protected EventLoopGroup eventLoopGroup() {
		return loopResources().onClient(isPreferNative());
	}

	protected void proxyProvider(ProxyProvider proxyProvider) {
		this.proxyProvider = proxyProvider;
	}

	@SuppressWarnings("unchecked")
	protected AddressResolverGroup<?> resolverInternal() {
		if (metricsRecorder != null) {
			return new AddressResolverGroupMetrics(
					(AddressResolverGroup<SocketAddress>) resolver(),
					Objects.requireNonNull(metricsRecorder.get(), "Metrics recorder supplier returned null"));
		}
		else {
			return resolver();
		}
	}

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