/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.transport.NameResolverProvider;
import reactor.netty.transport.TransportConfig;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Hold the default Tcp resources.
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public class TcpResources implements ConnectionProvider, LoopResources {

	/**
	 * Shutdown the global {@link TcpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones.
	 * This method is NOT blocking. It is implemented as fire-and-forget.
	 * Use {@link #disposeLoopsAndConnectionsLater()} when you need to observe
	 * the final status of the operation, combined with {@link Mono#block()}
	 * if you need to synchronously wait for the underlying resources to be disposed.
	 */
	public static void disposeLoopsAndConnections() {
		TcpResources resources = tcpResources.getAndSet(null);
		if (resources != null) {
			resources._dispose();
		}
	}

	/**
	 * Prepare to shutdown the global {@link TcpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 * The quiet period will be {@code 2s} and the timeout will be {@code 15s}
	 *
	 * @return a {@link Mono} triggering the {@link #disposeLoopsAndConnections()} when subscribed to.
	 */
	public static Mono<Void> disposeLoopsAndConnectionsLater() {
		return disposeLoopsAndConnectionsLater(Duration.ofSeconds(LoopResources.DEFAULT_SHUTDOWN_QUIET_PERIOD),
				Duration.ofSeconds(LoopResources.DEFAULT_SHUTDOWN_TIMEOUT));
	}

	/**
	 * Prepare to shutdown the global {@link TcpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 * It is guaranteed that the disposal of the underlying LoopResources will not happen before
	 * {@code quietPeriod} is over. If a task is submitted during the {@code quietPeriod},
	 * it is guaranteed to be accepted and the {@code quietPeriod} will start over.
	 *
	 * @param quietPeriod the quiet period as described above
	 * @param timeout the maximum amount of time to wait until the disposal of the underlying
	 * LoopResources regardless if a task was submitted during the quiet period
	 * @return a {@link Mono} triggering the {@link #disposeLoopsAndConnections()} when subscribed to.
	 * @since 0.9.3
	 */
	public static Mono<Void> disposeLoopsAndConnectionsLater(Duration quietPeriod, Duration timeout) {
		requireNonNull(quietPeriod, "quietPeriod");
		requireNonNull(timeout, "timeout");
		return Mono.defer(() -> {
			TcpResources resources = tcpResources.getAndSet(null);
			if (resources != null) {
				return resources._disposeLater(quietPeriod, timeout);
			}
			return Mono.empty();
		});
	}

	/**
	 * Return the global TCP resources for event loops and pooling.
	 *
	 * @return the global TCP resources for event loops and pooling
	 */
	public static TcpResources get() {
		return getOrCreate(tcpResources, null, null, ON_TCP_NEW, "tcp");
	}

	/**
	 * Reset TCP resources to default and return its instance.
	 *
	 * @return the global TCP resources
	 */
	public static TcpResources reset() {
		disposeLoopsAndConnections();
		return getOrCreate(tcpResources, null, null, ON_TCP_NEW, "tcp");
	}

	/**
	 * Update pooling resources and return the global TCP resources.
	 * Note: The previous {@link ConnectionProvider} will be disposed.
	 *
	 * @param provider a new {@link ConnectionProvider} to replace the current
	 * @return the global TCP resources
	 */
	public static TcpResources set(ConnectionProvider provider) {
		return getOrCreate(tcpResources, null, provider, ON_TCP_NEW, "tcp");
	}

	/**
	 * Update event loops resources and return the global TCP resources.
	 * Note: The previous {@link LoopResources} will be disposed.
	 *
	 * @param loops a new {@link LoopResources} to replace the current
	 * @return the global TCP resources
	 */
	public static TcpResources set(LoopResources loops) {
		return getOrCreate(tcpResources, loops, null, ON_TCP_NEW, "tcp");
	}

	final LoopResources                            defaultLoops;
	final ConnectionProvider                       defaultProvider;
	final AtomicReference<AddressResolverGroup<?>> defaultResolver;

	protected TcpResources(LoopResources defaultLoops, ConnectionProvider defaultProvider) {
		this.defaultLoops = defaultLoops;
		this.defaultProvider = defaultProvider;
		this.defaultResolver = new AtomicReference<>();
	}

	@Override
	public Mono<? extends Connection> acquire(TransportConfig config,
			ConnectionObserver observer,
			@Nullable Supplier<? extends SocketAddress> remoteAddress,
			@Nullable AddressResolverGroup<?> resolverGroup) {
		requireNonNull(config, "config");
		requireNonNull(observer, "observer");
		return defaultProvider.acquire(config, observer, remoteAddress, resolverGroup);
	}

	@Override
	public boolean daemon() {
		return defaultLoops.daemon();
	}

	/**
	 * This has a {@code NOOP} implementation by default in order to prevent unintended disposal of
	 * the global TCP resources which has a longer lifecycle than regular {@link LoopResources}
	 * and {@link ConnectionProvider}.
	 * If a disposal of the global TCP resources is needed,
	 * {@link #disposeLoopsAndConnections()} should be used instead.
	 */
	@Override
	public void dispose() {
		//noop on global by default
	}

	/**
	 * This has a {@code NOOP} implementation by default in order to prevent unintended disposal of
	 * the global TCP resources which has a longer lifecycle than regular {@link LoopResources}
	 * and {@link ConnectionProvider}.
	 * If a disposal of the global TCP resources is needed,
	 * {@link #disposeLoopsAndConnectionsLater()} should be used instead.
	 */
	@Override
	public Mono<Void> disposeLater() {
		//noop on global by default
		return Mono.empty();
	}

	/**
	 * This has a {@code NOOP} implementation by default in order to prevent unintended disposal of
	 * the global TCP resources which has a longer lifecycle than regular {@link LoopResources}
	 * and {@link ConnectionProvider}.
	 * If a disposal of the global TCP resources is needed,
	 * {@link #disposeLoopsAndConnectionsLater(Duration, Duration)} should be used instead.
	 */
	@Override
	public Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
		//noop on global by default
		return Mono.empty();
	}

	/**
	 * Dispose all connection pools for the specified remote address.
	 * <p>
	 * As opposed to {@link #dispose()}, this method delegates to the underlying connection provider.
	 * It has a global effect and removes all connection pools for this remote address from the
	 * global TCP resources (making it closer to {@link #disposeLoopsAndConnections()} than
	 * to {@link #dispose()}).
	 *
	 * @param remoteAddress the remote address
	 */
	@Override
	public void disposeWhen(SocketAddress remoteAddress) {
		defaultProvider.disposeWhen(remoteAddress);
	}

	@Override
	public boolean isDisposed() {
		return defaultLoops.isDisposed() && defaultProvider.isDisposed();
	}

	@Override
	public int maxConnections() {
		return defaultProvider.maxConnections();
	}

	@Override
	public Map<SocketAddress, Integer> maxConnectionsPerHost() {
		return defaultProvider.maxConnectionsPerHost();
	}

	@Override
	public Builder mutate() {
		return defaultProvider.mutate();
	}

	@Override
	public String name() {
		return defaultProvider.name();
	}

	@Override
	public <CHANNEL extends Channel> CHANNEL onChannel(Class<CHANNEL> channelType, EventLoopGroup group) {
		requireNonNull(channelType, "channelType");
		requireNonNull(group, "group");
		return defaultLoops.onChannel(channelType, group);
	}

	@Override
	public <CHANNEL extends Channel> Class<? extends CHANNEL> onChannelClass(Class<CHANNEL> channelType, EventLoopGroup group) {
		requireNonNull(channelType, "channelType");
		requireNonNull(group, "group");
		return defaultLoops.onChannelClass(channelType, group);
	}

	@Override
	public EventLoopGroup onClient(boolean useNative) {
		return defaultLoops.onClient(useNative);
	}

	@Override
	public EventLoopGroup onServer(boolean useNative) {
		return defaultLoops.onServer(useNative);
	}

	@Override
	public EventLoopGroup onServerSelect(boolean useNative) {
		return defaultLoops.onServerSelect(useNative);
	}

	/**
	 * Dispose underlying resources.
	 */
	protected void _dispose() {
		defaultProvider.dispose();
		_disposeResolver();
		defaultLoops.dispose();
	}

	/**
	 * Dispose underlying resources in a listenable fashion.
	 * It is guaranteed that the disposal of the underlying LoopResources will not happen before
	 * {@code quietPeriod} is over. If a task is submitted during the {@code quietPeriod},
	 * it is guaranteed to be accepted and the {@code quietPeriod} will start over.
	 *
	 * @param quietPeriod the quiet period as described above
	 * @param timeout the maximum amount of time to wait until the disposal of the underlying
	 * LoopResources regardless if a task was submitted during the quiet period
	 * @return the Mono that represents the end of disposal
	 */
	protected Mono<Void> _disposeLater(Duration quietPeriod, Duration timeout) {
		return Mono.when(
				_disposeResolverLater(),
				defaultLoops.disposeLater(quietPeriod, timeout),
				defaultProvider.disposeLater());
	}

	/**
	 * Safely checks whether a name resolver exists and proceed with a creation if it does not exist.
	 * The name resolver uses as an event loop group the {@link LoopResources} that are configured.
	 * Guarantees that always one and the same instance is returned for a given {@link LoopResources}
	 * and if the {@link LoopResources} is updated the name resolver is also updated.
	 *
	 * @return an existing or new {@link AddressResolverGroup}
	 */
	protected AddressResolverGroup<?> getOrCreateDefaultResolver() {
		AddressResolverGroup<?> resolverGroup = defaultResolver.get();
		if (resolverGroup == null) {
			AddressResolverGroup<?> newResolverGroup =
					DEFAULT_NAME_RESOLVER_PROVIDER.newNameResolverGroup(defaultLoops, LoopResources.DEFAULT_NATIVE);
			if (!defaultResolver.compareAndSet(null, newResolverGroup)) {
				newResolverGroup.close();
			}
			resolverGroup = getOrCreateDefaultResolver();
		}
		return resolverGroup;
	}

	void _disposeResolver() {
		AddressResolverGroup<?> addressResolverGroup = defaultResolver.get();
		if (addressResolverGroup != null) {
			addressResolverGroup.close();
		}
	}

	Mono<Void> _disposeResolverLater() {
		Mono<Void> disposeResolver = Mono.empty();
		AddressResolverGroup<?> addressResolverGroup = defaultResolver.get();
		if (addressResolverGroup != null) {
			disposeResolver = Mono.fromRunnable(addressResolverGroup::close);
		}
		return disposeResolver;
	}

	/**
	 * Safely check if existing resource exist and proceed to update/cleanup if new
	 * resources references are passed.
	 *
	 * @param ref the resources atomic reference
	 * @param loops the eventual new {@link LoopResources}
	 * @param provider the eventual new {@link ConnectionProvider}
	 * @param onNew a {@link TcpResources} factory
	 * @param name a name for resources
	 * @param <T> the reified type of {@link TcpResources}
	 * @return an existing or new {@link TcpResources}
	 */
	protected static <T extends TcpResources> T getOrCreate(AtomicReference<T> ref,
			@Nullable LoopResources loops,
			@Nullable ConnectionProvider provider,
			BiFunction<LoopResources, ConnectionProvider, T> onNew,
			String name) {
		T update;
		for (;;) {
			T resources = ref.get();
			if (resources == null || loops != null || provider != null) {
				update = create(resources, loops, provider, name, onNew);
				if (ref.compareAndSet(resources, update)) {
					if (resources != null) {
						if (loops != null) {
							if (log.isWarnEnabled()) {
								log.warn("[{}] resources will use a new LoopResources: {}, " +
										"the previous LoopResources will be disposed", name, loops);
							}
							resources._disposeResolver();
							resources.defaultLoops.dispose();
						}
						if (provider != null) {
							if (log.isWarnEnabled()) {
								log.warn("[{}] resources will use a new ConnectionProvider: {}, " +
										"the previous ConnectionProvider will be disposed", name, provider);
							}
							resources.defaultProvider.dispose();
						}
					}
					else {
						String loopType = loops == null ? "default" : "provided";
						if (log.isDebugEnabled()) {
							log.debug("[{}] resources will use the {} LoopResources: {}", name, loopType, update.defaultLoops);
						}
						String poolType = provider == null ? "default" : "provided";
						if (log.isDebugEnabled()) {
							log.debug("[{}] resources will use the {} ConnectionProvider: {}", name, poolType, update.defaultProvider);
						}
					}
					return update;
				}
				else {
					update._dispose();
				}
			}
			else {
				return resources;
			}
		}
	}

	static <T extends TcpResources> T create(@Nullable T previous,
			@Nullable LoopResources loops, @Nullable ConnectionProvider provider,
			String name,
			BiFunction<LoopResources, ConnectionProvider, T> onNew) {
		if (previous == null) {
			loops = loops == null ? LoopResources.create("reactor-" + name) : loops;
			provider = provider == null ? ConnectionProvider.create(name, 500) : provider;
		}
		else {
			loops = loops == null ? previous.defaultLoops : loops;
			provider = provider == null ? previous.defaultProvider : provider;
		}
		return onNew.apply(loops, provider);
	}

	static final NameResolverProvider                                        DEFAULT_NAME_RESOLVER_PROVIDER;

	static final Logger                                                      log = Loggers.getLogger(TcpResources.class);

	static final BiFunction<LoopResources, ConnectionProvider, TcpResources> ON_TCP_NEW;

	static final AtomicReference<TcpResources>                               tcpResources;

	static {
		DEFAULT_NAME_RESOLVER_PROVIDER = NameResolverProvider.builder().build();
		ON_TCP_NEW = TcpResources::new;
		tcpResources = new AtomicReference<>();
	}
}
