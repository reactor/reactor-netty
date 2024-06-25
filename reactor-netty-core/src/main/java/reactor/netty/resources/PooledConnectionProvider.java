/*
 * Copyright (c) 2018-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.ReactorNetty;
import reactor.netty.internal.util.Metrics;
import reactor.netty.transport.TransportConfig;
import reactor.netty.internal.util.MapUtils;
import reactor.pool.InstrumentedPool;
import reactor.pool.Pool;
import reactor.pool.PoolBuilder;
import reactor.pool.PoolConfig;
import reactor.pool.PoolMetricsRecorder;
import reactor.pool.PooledRef;
import reactor.pool.PooledRefMetadata;
import reactor.pool.decorators.GracefulShutdownInstrumentedPool;
import reactor.pool.decorators.InstrumentedPoolDecorators;
import reactor.pool.introspection.SamplingAllocationStrategy;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.resources.ConnectionProvider.ConnectionPoolSpec.PENDING_ACQUIRE_MAX_COUNT_NOT_SPECIFIED;

/**
 * Base {@link ConnectionProvider} implementation.
 *
 * @param <T> the poolable resource
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public abstract class PooledConnectionProvider<T extends Connection> implements ConnectionProvider {
	/**
	 * Context key used to propagate the caller event loop in the connection pool subscription.
	 */
	protected static final String CONTEXT_CALLER_EVENTLOOP = "callereventloop";

	final PoolFactory<T> defaultPoolFactory;
	final Map<SocketAddress, PoolFactory<T>> poolFactoryPerRemoteHost = new HashMap<>();

	final ConcurrentMap<PoolKey, InstrumentedPool<T>> channelPools = new ConcurrentHashMap<>();

	final Builder builder;
	final String name;
	final Duration inactivePoolDisposeInterval;
	final Duration poolInactivity;
	final Duration disposeTimeout;
	final Map<SocketAddress, Integer> maxConnections = new HashMap<>();
	Mono<Void> onDispose;

	protected PooledConnectionProvider(Builder builder) {
		this(builder, null);
	}

	// Used only for testing purposes
	PooledConnectionProvider(Builder builder, @Nullable Clock clock) {
		this.builder = builder;
		this.name = builder.name;
		this.inactivePoolDisposeInterval = builder.inactivePoolDisposeInterval;
		this.poolInactivity = builder.poolInactivity;
		this.disposeTimeout = builder.disposeTimeout;
		this.defaultPoolFactory = new PoolFactory<>(builder, builder.disposeTimeout, clock);
		for (Map.Entry<SocketAddress, ConnectionPoolSpec<?>> entry : builder.confPerRemoteHost.entrySet()) {
			poolFactoryPerRemoteHost.put(entry.getKey(), new PoolFactory<>(entry.getValue(), builder.disposeTimeout));
			maxConnections.put(entry.getKey(), entry.getValue().maxConnections);
		}
		this.onDispose = Mono.empty();
		scheduleInactivePoolsDisposal();
	}

	@Override
	public final Mono<? extends Connection> acquire(
			TransportConfig config,
			ConnectionObserver connectionObserver,
			@Nullable Supplier<? extends SocketAddress> remote,
			@Nullable AddressResolverGroup<?> resolverGroup) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(connectionObserver, "connectionObserver");
		Objects.requireNonNull(remote, "remoteAddress");
		return Mono.create(sink -> {
			SocketAddress remoteAddress = Objects.requireNonNull(remote.get(), "Remote Address supplier returned null");
			PoolKey holder = new PoolKey(remoteAddress, config.channelHash());
			PoolFactory<T> poolFactory = poolFactory(remoteAddress);
			InstrumentedPool<T> pool = MapUtils.computeIfAbsent(channelPools, holder, poolKey -> {
				if (log.isDebugEnabled()) {
					log.debug("Creating a new [{}] client pool [{}] for [{}]", name, poolFactory, remoteAddress);
				}

				boolean metricsEnabled = poolFactory.metricsEnabled || config.metricsRecorder() != null;
				String id = metricsEnabled ? poolKey.hashCode() + "" : null;

				InstrumentedPool<T> newPool = metricsEnabled && Metrics.isMicrometerAvailable() ?
						createPool(id, config, poolFactory, remoteAddress, resolverGroup) :
						createPool(config, poolFactory, remoteAddress, resolverGroup);

				if (metricsEnabled) {
					// registrar is null when metrics are enabled on HttpClient level or
					// with the `metrics(boolean metricsEnabled)` method on ConnectionProvider
					if (poolFactory.registrar != null) {
						poolFactory.registrar.get().registerMetrics(name, id, remoteAddress,
								new DelegatingConnectionPoolMetrics(newPool.metrics()));
					}
					else if (Metrics.isMicrometerAvailable()) {
						// work directly with the pool otherwise a weak reference is needed to ConnectionPoolMetrics
						// we don't want to keep another map with weak references
						registerDefaultMetrics(id, remoteAddress, newPool.metrics());
					}
				}
				return newPool;
			});

			EventLoop eventLoop;
			if (sink.contextView().hasKey(CONTEXT_CALLER_EVENTLOOP)) {
				eventLoop = sink.contextView().get(CONTEXT_CALLER_EVENTLOOP);
			}
			else {
				EventLoopGroup group = config.loopResources().onClient(config.isPreferNative());
				if (group instanceof ColocatedEventLoopGroup) {
					eventLoop = ((ColocatedEventLoopGroup) group).nextInternal();
				}
				else {
					eventLoop = null;
				}
			}

			Mono<PooledRef<T>> mono = pool.acquire(Duration.ofMillis(poolFactory.pendingAcquireTimeout));
			if (eventLoop != null) {
				mono = mono.contextWrite(ctx -> ctx.put(CONTEXT_CALLER_EVENTLOOP, eventLoop));
			}
			Context currentContext = Context.of(sink.contextView());
			if ((poolFactory.metricsEnabled || config.metricsRecorder() != null)
					&& Metrics.isMicrometerAvailable()) {
				Object currentObservation = reactor.netty.Metrics.currentObservation(currentContext);
				if (currentObservation != null) {
					currentContext = reactor.netty.Metrics.updateContext(currentContext, currentObservation);
					mono = mono.contextWrite(ctx -> reactor.netty.Metrics.updateContext(ctx, currentObservation));
				}
			}
			mono.subscribe(createDisposableAcquire(config, connectionObserver,
					poolFactory.pendingAcquireTimeout, pool, remoteAddress, sink, currentContext));
		});
	}

	@Override
	public final Mono<Void> disposeLater() {
		return Mono.defer(() -> {
			List<Mono<Void>> pools;
			pools = channelPools.entrySet()
			                    .stream()
			                    .map(e -> {
			                        Pool<T> pool = e.getValue();
			                        SocketAddress remoteAddress = e.getKey().holder;
			                        String id = e.getKey().hashCode() + "";
			                        PoolFactory<T> poolFactory = poolFactory(remoteAddress);
			                        if (pool instanceof GracefulShutdownInstrumentedPool) {
			                            return ((GracefulShutdownInstrumentedPool<T>) pool)
			                                    .disposeGracefully(disposeTimeout)
			                                    .then(deRegisterDefaultMetrics(id, pool.config().metricsRecorder(), poolFactory.registrar, remoteAddress))
			                                    .onErrorResume(t -> {
			                                        log.error("Connection pool for [{}] didn't shut down gracefully", e.getKey(), t);
			                                        return deRegisterDefaultMetrics(id, pool.config().metricsRecorder(), poolFactory.registrar, remoteAddress);
			                                    });
			                        }
			                        return pool.disposeLater()
			                                   .then(deRegisterDefaultMetrics(id, pool.config().metricsRecorder(), poolFactory.registrar, remoteAddress));
			                    })
			                    .collect(Collectors.toList());
			if (pools.isEmpty()) {
				return onDispose;
			}
			channelPools.clear();
			return onDispose.and(Mono.when(pools));
		});
	}

	@Override
	public final void disposeWhen(SocketAddress address) {
		List<Map.Entry<PoolKey, InstrumentedPool<T>>> toDispose;

		toDispose = channelPools.entrySet()
		                        .stream()
		                        .filter(p -> compareAddresses(p.getKey().holder, address))
		                        .collect(Collectors.toList());

		toDispose.forEach(e -> {
			if (channelPools.remove(e.getKey(), e.getValue())) {
				if (log.isDebugEnabled()) {
					log.debug("ConnectionProvider[name={}]: Disposing pool for [{}]", name, e.getKey().holder);
				}
				String id = e.getKey().hashCode() + "";
				PoolFactory<T> poolFactory = poolFactory(address);
				e.getValue().disposeLater().then(
						Mono.<Void>fromRunnable(() -> {
							if (poolFactory.registrar != null) {
								poolFactory.registrar.get().deRegisterMetrics(name, id, address);
							}
							else if (Metrics.isMicrometerAvailable()) {
								deRegisterDefaultMetrics(id, address);
								PoolMetricsRecorder recorder = e.getValue().config().metricsRecorder();
								if (recorder instanceof Disposable) {
									((Disposable) recorder).dispose();
								}
							}
						})
				).subscribe();
			}
		});
	}

	@Override
	public final boolean isDisposed() {
		return channelPools.isEmpty() || channelPools.values()
		                                             .stream()
		                                             .allMatch(Disposable::isDisposed);
	}

	@Override
	public int maxConnections() {
		return defaultPoolFactory.maxConnections;
	}

	@Override
	public Map<SocketAddress, Integer> maxConnectionsPerHost() {
		return maxConnections;
	}

	@Override
	public Builder mutate() {
		return new Builder(builder);
	}

	@Override
	public String name() {
		return name;
	}

	public void onDispose(Mono<Void> disposeMono) {
		onDispose = onDispose.and(disposeMono);
	}

	protected abstract CoreSubscriber<PooledRef<T>> createDisposableAcquire(
			TransportConfig config,
			ConnectionObserver connectionObserver,
			long pendingAcquireTimeout,
			InstrumentedPool<T> pool,
			MonoSink<Connection> sink,
			Context currentContext);

	protected CoreSubscriber<PooledRef<T>> createDisposableAcquire(
			TransportConfig config,
			ConnectionObserver connectionObserver,
			long pendingAcquireTimeout,
			InstrumentedPool<T> pool,
			SocketAddress remoteAddress,
			MonoSink<Connection> sink,
			Context currentContext) {
		return createDisposableAcquire(config, connectionObserver, pendingAcquireTimeout, pool, sink, currentContext);
	}

	protected abstract InstrumentedPool<T> createPool(
			TransportConfig config,
			PoolFactory<T> poolFactory,
			SocketAddress remoteAddress,
			@Nullable AddressResolverGroup<?> resolverGroup);

	protected InstrumentedPool<T> createPool(
			String id,
			TransportConfig config,
			PoolFactory<T> poolFactory,
			SocketAddress remoteAddress,
			@Nullable AddressResolverGroup<?> resolverGroup) {
		return createPool(config, poolFactory, remoteAddress, resolverGroup);
	}

	protected PoolFactory<T> poolFactory(SocketAddress remoteAddress) {
		return poolFactoryPerRemoteHost.getOrDefault(remoteAddress, defaultPoolFactory);
	}

	protected void registerDefaultMetrics(String id, SocketAddress remoteAddress, InstrumentedPool.PoolMetrics metrics) {
		MicrometerPooledConnectionProviderMeterRegistrar.INSTANCE.registerMetrics(name, id, remoteAddress, metrics);
	}

	protected void deRegisterDefaultMetrics(String id, SocketAddress remoteAddress) {
		MicrometerPooledConnectionProviderMeterRegistrar.INSTANCE.deRegisterMetrics(name, id, remoteAddress);
	}

	Mono<Void> deRegisterDefaultMetrics(String id, PoolMetricsRecorder recorder, @Nullable Supplier<? extends MeterRegistrar> registrar, SocketAddress remoteAddress) {
		return Mono.fromRunnable(() -> {
			if (registrar != null) {
				registrar.get().deRegisterMetrics(name, id, remoteAddress);
			}
			else if (Metrics.isMicrometerAvailable()) {
				deRegisterDefaultMetrics(id, remoteAddress);
				if (recorder instanceof Disposable) {
					((Disposable) recorder).dispose();
				}
			}
		});
	}

	final boolean compareAddresses(SocketAddress origin, SocketAddress target) {
		if (origin.equals(target)) {
			return true;
		}
		else if (origin instanceof InetSocketAddress && target instanceof InetSocketAddress) {
			InetSocketAddress isaOrigin = (InetSocketAddress) origin;
			InetSocketAddress isaTarget = (InetSocketAddress) target;
			if (isaOrigin.getPort() == isaTarget.getPort()) {
				InetAddress iaTarget = isaTarget.getAddress();
				return (iaTarget != null && iaTarget.isAnyLocalAddress()) ||
						Objects.equals(isaOrigin.getHostString(), isaTarget.getHostString());
			}
		}
		return false;
	}

	protected static void logPoolState(Channel channel, InstrumentedPool<? extends Connection> pool, String msg) {
		logPoolState(channel, pool, msg, null);
	}

	protected static void logPoolState(Channel channel, InstrumentedPool<? extends Connection> pool, String msg, @Nullable Throwable t) {
		InstrumentedPool.PoolMetrics metrics = pool.metrics();
		log.debug(format(channel, "{}, now: {} active connections, {} inactive connections and {} pending acquire requests."),
				msg,
				metrics.acquiredSize(),
				metrics.idleSize(),
				metrics.pendingAcquireSize(),
				t == null ? "" : t);
	}

	final void scheduleInactivePoolsDisposal() {
		if (!inactivePoolDisposeInterval.isZero()) {
			Schedulers.parallel()
			          .schedule(this::disposeInactivePoolsInBackground, inactivePoolDisposeInterval.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	final void disposeInactivePoolsInBackground() {
		if (!channelPools.isEmpty()) {
			List<Map.Entry<PoolKey, InstrumentedPool<T>>> toDispose;

			toDispose = channelPools.entrySet()
			                        .stream()
			                        .filter(p -> p.getValue().metrics().isInactiveForMoreThan(poolInactivity))
			                        .collect(Collectors.toList());

			toDispose.forEach(e -> {
				if (channelPools.remove(e.getKey(), e.getValue())) {
					if (log.isDebugEnabled()) {
						log.debug("ConnectionProvider[name={}]: Disposing inactive pool for [{}]", name, e.getKey().holder);
					}
					SocketAddress address = e.getKey().holder;
					String id = e.getKey().hashCode() + "";
					PoolFactory<T> poolFactory = poolFactory(address);
					e.getValue().disposeLater().then(
							Mono.<Void>fromRunnable(() -> {
								if (poolFactory.registrar != null) {
									poolFactory.registrar.get().deRegisterMetrics(name, id, address);
								}
								else if (Metrics.isMicrometerAvailable()) {
									deRegisterDefaultMetrics(id, address);
									PoolMetricsRecorder recorder = e.getValue().config().metricsRecorder();
									if (recorder instanceof Disposable) {
										((Disposable) recorder).dispose();
									}
								}
							})
					).subscribe();
				}
			});
		}
		scheduleInactivePoolsDisposal();
	}

	static final Logger log = Loggers.getLogger(PooledConnectionProvider.class);

	protected static final class PoolFactory<T extends Connection> {
		static final double DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE;
		static {
			double getPermitsSamplingRate =
					Double.parseDouble(System.getProperty(ReactorNetty.POOL_GET_PERMITS_SAMPLING_RATE, "0"));
			if (getPermitsSamplingRate > 1d) {
				DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE = 0;
				if (log.isWarnEnabled()) {
					log.warn("Invalid configuration [" + ReactorNetty.POOL_GET_PERMITS_SAMPLING_RATE + "=" + getPermitsSamplingRate +
							"], the value must be between 0d and 1d (percentage). SamplingAllocationStrategy in not enabled.");
				}
			}
			else {
				DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE = getPermitsSamplingRate;
			}
		}

		static final double DEFAULT_POOL_RETURN_PERMITS_SAMPLING_RATE;
		static {
			double returnPermitsSamplingRate =
					Double.parseDouble(System.getProperty(ReactorNetty.POOL_RETURN_PERMITS_SAMPLING_RATE, "0"));
			if (returnPermitsSamplingRate > 1d) {
				DEFAULT_POOL_RETURN_PERMITS_SAMPLING_RATE = 0;
				if (log.isWarnEnabled()) {
					log.warn("Invalid configuration [" + ReactorNetty.POOL_RETURN_PERMITS_SAMPLING_RATE + "=" + returnPermitsSamplingRate +
							"], the value must be between 0d and 1d (percentage). SamplingAllocationStrategy is enabled.");
				}
			}
			else {
				DEFAULT_POOL_RETURN_PERMITS_SAMPLING_RATE = returnPermitsSamplingRate;
			}
		}

		final Duration evictionInterval;
		final String leasingStrategy;
		final int maxConnections;
		final long maxIdleTime;
		final long maxLifeTime;
		final boolean metricsEnabled;
		final int pendingAcquireMaxCount;
		final long pendingAcquireTimeout;
		final Supplier<? extends MeterRegistrar> registrar;
		final Clock clock;
		final Duration disposeTimeout;
		final BiFunction<Runnable, Duration, Disposable> pendingAcquireTimer;
		final AllocationStrategy<?> allocationStrategy;
		final BiPredicate<Connection, ConnectionMetadata> evictionPredicate;

		PoolFactory(ConnectionPoolSpec<?> conf, Duration disposeTimeout) {
			this(conf, disposeTimeout, null);
		}

		// Used only for testing purposes
		PoolFactory(ConnectionPoolSpec<?> conf, Duration disposeTimeout, @Nullable Clock clock) {
			this.evictionInterval = conf.evictionInterval;
			this.leasingStrategy = conf.leasingStrategy;
			this.maxConnections = conf.maxConnections;
			this.maxIdleTime = conf.maxIdleTime != null ? conf.maxIdleTime.toMillis() : -1;
			this.maxLifeTime = conf.maxLifeTime != null ? conf.maxLifeTime.toMillis() : -1;
			this.metricsEnabled = conf.metricsEnabled;
			this.pendingAcquireMaxCount = conf.pendingAcquireMaxCount == PENDING_ACQUIRE_MAX_COUNT_NOT_SPECIFIED ?
					2 * conf.maxConnections : conf.pendingAcquireMaxCount;
			this.pendingAcquireTimeout = conf.pendingAcquireTimeout.toMillis();
			this.registrar = conf.registrar;
			this.clock = clock;
			this.disposeTimeout = disposeTimeout;
			this.pendingAcquireTimer = conf.pendingAcquireTimer;
			this.allocationStrategy = conf.allocationStrategy;
			this.evictionPredicate = conf.evictionPredicate;
		}

		public InstrumentedPool<T> newPool(
				Publisher<T> allocator,
				@Nullable reactor.pool.AllocationStrategy allocationStrategy, // this is not used but kept for backwards compatibility
				Function<T, Publisher<Void>> destroyHandler,
				BiPredicate<T, PooledRefMetadata> evictionPredicate) {
			if (disposeTimeout != null) {
				return newPoolInternal(allocator, destroyHandler, evictionPredicate)
						.buildPoolAndDecorateWith(InstrumentedPoolDecorators::gracefulShutdown);
			}
			return newPoolInternal(allocator, destroyHandler, evictionPredicate).buildPool();
		}

		public InstrumentedPool<T> newPool(
				Publisher<T> allocator,
				Function<T, Publisher<Void>> destroyHandler,
				BiPredicate<T, PooledRefMetadata> evictionPredicate,
				PoolMetricsRecorder poolMetricsRecorder) {
			if (disposeTimeout != null) {
				return newPoolInternal(allocator, destroyHandler, evictionPredicate, poolMetricsRecorder)
						.buildPoolAndDecorateWith(InstrumentedPoolDecorators::gracefulShutdown);
			}
			return newPoolInternal(allocator, destroyHandler, evictionPredicate, poolMetricsRecorder).buildPool();
		}

		public InstrumentedPool<T> newPool(
				Publisher<T> allocator,
				@Nullable reactor.pool.AllocationStrategy allocationStrategy, // this is not used but kept for backwards compatibility
				Function<T, Publisher<Void>> destroyHandler,
				BiPredicate<T, PooledRefMetadata> defaultEvictionPredicate,
				Function<PoolConfig<T>, InstrumentedPool<T>> poolFactory) {
			if (disposeTimeout != null) {
				return newPoolInternal(allocator, destroyHandler, defaultEvictionPredicate)
						.build(poolFactory.andThen(InstrumentedPoolDecorators::gracefulShutdown));
			}
			return newPoolInternal(allocator, destroyHandler, defaultEvictionPredicate).build(poolFactory);
		}

		public InstrumentedPool<T> newPool(
				Publisher<T> allocator,
				Function<T, Publisher<Void>> destroyHandler,
				BiPredicate<T, PooledRefMetadata> defaultEvictionPredicate,
				PoolMetricsRecorder poolMetricsRecorder,
				Function<PoolConfig<T>, InstrumentedPool<T>> poolFactory) {
			if (disposeTimeout != null) {
				return newPoolInternal(allocator, destroyHandler, defaultEvictionPredicate, poolMetricsRecorder)
						.build(poolFactory.andThen(InstrumentedPoolDecorators::gracefulShutdown));
			}
			return newPoolInternal(allocator, destroyHandler, defaultEvictionPredicate, poolMetricsRecorder).build(poolFactory);
		}

		PoolBuilder<T, PoolConfig<T>> newPoolInternal(
				Publisher<T> allocator,
				Function<T, Publisher<Void>> destroyHandler,
				BiPredicate<T, PooledRefMetadata> defaultEvictionPredicate) {
			return newPoolInternal(allocator, destroyHandler, defaultEvictionPredicate, null);
		}

		PoolBuilder<T, PoolConfig<T>> newPoolInternal(
				Publisher<T> allocator,
				Function<T, Publisher<Void>> destroyHandler,
				BiPredicate<T, PooledRefMetadata> defaultEvictionPredicate,
				@Nullable PoolMetricsRecorder poolMetricsRecorder) {
			PoolBuilder<T, PoolConfig<T>> poolBuilder =
					PoolBuilder.from(allocator)
					           .destroyHandler(destroyHandler)
					           .maxPendingAcquire(pendingAcquireMaxCount)
					           .evictInBackground(evictionInterval);

			if (this.evictionPredicate != null) {
				poolBuilder = poolBuilder.evictionPredicate(
						(poolable, meta) -> this.evictionPredicate.test(poolable, new PooledConnectionMetadata(meta)));
			}
			else {
				poolBuilder = poolBuilder.evictionPredicate(defaultEvictionPredicate.or((poolable, meta) ->
						(maxIdleTime != -1 && meta.idleTime() >= maxIdleTime)
								|| (maxLifeTime != -1 && meta.lifeTime() >= maxLifeTime)));
			}

			if (DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE > 0d && DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE <= 1d
					&& DEFAULT_POOL_RETURN_PERMITS_SAMPLING_RATE > 0d && DEFAULT_POOL_RETURN_PERMITS_SAMPLING_RATE <= 1d) {
				poolBuilder = poolBuilder.allocationStrategy(SamplingAllocationStrategy.sizeBetweenWithSampling(
						0,
						maxConnections,
						DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE,
						DEFAULT_POOL_RETURN_PERMITS_SAMPLING_RATE));
			}
			else {
				if (allocationStrategy == null) {
					poolBuilder = poolBuilder.sizeBetween(0, maxConnections);
				}
				else {
					poolBuilder = poolBuilder.allocationStrategy(new DelegatingAllocationStrategy(allocationStrategy.copy()));
				}
			}

			if (pendingAcquireTimer != null) {
				poolBuilder = poolBuilder.pendingAcquireTimer(pendingAcquireTimer);
			}

			if (clock != null) {
				poolBuilder = poolBuilder.clock(clock);
			}

			if (LEASING_STRATEGY_FIFO.equals(leasingStrategy)) {
				poolBuilder = poolBuilder.idleResourceReuseLruOrder();
			}
			else {
				poolBuilder = poolBuilder.idleResourceReuseMruOrder();
			}

			if (poolMetricsRecorder != null) {
				poolBuilder.metricsRecorder(poolMetricsRecorder);
			}

			return poolBuilder;
		}

		@Nullable
		public AllocationStrategy<?> allocationStrategy() {
			return allocationStrategy;
		}

		public long maxIdleTime() {
			return this.maxIdleTime;
		}

		public long maxLifeTime() {
			return maxLifeTime;
		}

		@Override
		public String toString() {
			return "PoolFactory{" +
					"evictionInterval=" + evictionInterval +
					", leasingStrategy=" + leasingStrategy +
					", maxConnections=" + maxConnections +
					", maxIdleTime=" + maxIdleTime +
					", maxLifeTime=" + maxLifeTime +
					", metricsEnabled=" + metricsEnabled +
					", pendingAcquireMaxCount=" + pendingAcquireMaxCount +
					", pendingAcquireTimeout=" + pendingAcquireTimeout +
					'}';
		}

		static final class DelegatingAllocationStrategy implements reactor.pool.AllocationStrategy {

			final AllocationStrategy<?> delegate;

			DelegatingAllocationStrategy(AllocationStrategy<?> delegate) {
				this.delegate = delegate;
			}

			@Override
			public int estimatePermitCount() {
				return delegate.estimatePermitCount();
			}

			@Override
			public int getPermits(int desired) {
				return delegate.getPermits(desired);
			}

			@Override
			public int permitGranted() {
				return delegate.permitGranted();
			}

			@Override
			public int permitMinimum() {
				return delegate.permitMinimum();
			}

			@Override
			public int permitMaximum() {
				return delegate.permitMaximum();
			}

			@Override
			public void returnPermits(int returned) {
				delegate.returnPermits(returned);
			}
		}
	}

	static final class PooledConnectionMetadata implements ConnectionMetadata {

		final PooledRefMetadata delegate;

		PooledConnectionMetadata(PooledRefMetadata delegate) {
			this.delegate = delegate;
		}

		@Override
		public int acquireCount() {
			return delegate.acquireCount();
		}

		@Override
		public long idleTime() {
			return delegate.idleTime();
		}

		@Override
		public long lifeTime() {
			return delegate.lifeTime();
		}

		@Override
		public long releaseTimestamp() {
			return delegate.releaseTimestamp();
		}

		@Override
		public long allocationTimestamp() {
			return delegate.allocationTimestamp();
		}
	}

	static final class PoolKey {
		final String fqdn;
		final SocketAddress holder;
		final int pipelineKey;

		PoolKey(SocketAddress holder, int pipelineKey) {
			String fqdn = null;
			if (holder instanceof InetSocketAddress) {
				InetSocketAddress inetSocketAddress = (InetSocketAddress) holder;
				if (!inetSocketAddress.isUnresolved()) {
					// Use FQDN as a tie-breaker over IP's
					fqdn = inetSocketAddress.getHostString().toLowerCase();
				}
			}
			this.fqdn = fqdn;
			this.holder = holder;
			this.pipelineKey = pipelineKey;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			PoolKey poolKey = (PoolKey) o;
			return Objects.equals(fqdn, poolKey.fqdn) &&
						   Objects.equals(holder, poolKey.holder) &&
						   pipelineKey == poolKey.pipelineKey;
		}

		@Override
		public int hashCode() {
			int result = 1;
			result = 31 * result + Objects.hashCode(fqdn);
			result = 31 * result + Objects.hashCode(holder);
			result = 31 * result + pipelineKey;
			return result;
		}
	}
}
