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
package reactor.netty.resources;

import io.netty.resolver.AddressResolverGroup;
import io.netty.util.internal.PlatformDependent;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.ReactorNetty;
import reactor.netty.transport.TransportConfig;
import reactor.pool.AllocationStrategy;
import reactor.pool.InstrumentedPool;
import reactor.pool.Pool;
import reactor.pool.PoolBuilder;
import reactor.pool.PoolConfig;
import reactor.pool.PooledRef;
import reactor.pool.PooledRefMetadata;
import reactor.pool.introspection.SamplingAllocationStrategy;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static reactor.netty.resources.ConnectionProvider.ConnectionPoolSpec.PENDING_ACQUIRE_MAX_COUNT_NOT_SPECIFIED;

/**
 * Base {@link ConnectionProvider} implementation.
 *
 * @param <T> the poolable resource
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public abstract class PooledConnectionProvider<T extends Connection> implements ConnectionProvider {
	final PoolFactory<T> defaultPoolFactory;

	final ConcurrentMap<PoolKey, InstrumentedPool<T>> channelPools = PlatformDependent.newConcurrentHashMap();
	/**
	 * This map keeps a weakref to the {@link InstrumentedPool#metrics() metrics} of created pools through the same PoolKey that is used in
	 * {@link #channelPools}. This is so that metrics providing objects don't get garbage collected too early,
	 * as metrics-related frameworks (such as micrometer) don't hold strong references to metrics providing objects
	 * (rightfully so). When the PoolKey is garbage collected, the metrics will become garbage collectable again.
	 *
	 * @see #acquire(TransportConfig, ConnectionObserver, Supplier, AddressResolverGroup)
	 * @see #disposeLater()
	 */
	private final Map<PoolKey, ConnectionPoolMetrics> poolMetrics = new WeakHashMap<>();

	final String name;

	protected PooledConnectionProvider(Builder builder) {
		this.name = builder.name;
		this.defaultPoolFactory = new PoolFactory<>(builder);
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
		Objects.requireNonNull(resolverGroup, "resolverGroup");
		return Mono.create(sink -> {
			SocketAddress remoteAddress = Objects.requireNonNull(remote.get(), "Remote Address supplier returned null");
			PoolKey holder = new PoolKey(remoteAddress, config.channelHash());
			PoolFactory<T> poolFactory = poolFactory(remoteAddress);
			InstrumentedPool<T> pool = channelPools.computeIfAbsent(holder, poolKey -> {
				if (log.isDebugEnabled()) {
					log.debug("Creating a new [{}] client pool [{}] for [{}]", name, poolFactory, remoteAddress);
				}

				InstrumentedPool<T> newPool = createPool(config, poolFactory, remoteAddress, resolverGroup);

				if (poolFactory.metricsEnabled || config.metricsRecorder() != null) {
					// registrar is null when metrics are enabled on HttpClient level or
					// with the `metrics(boolean metricsEnabled)` method on ConnectionProvider
					MeterRegistrar registrar = poolFactory.registrar != null ?
							poolFactory.registrar.get() : MicrometerPooledConnectionProviderMeterRegistrar.INSTANCE;

					DelegatingConnectionPoolMetrics metrics = new DelegatingConnectionPoolMetrics(newPool.metrics());
					poolMetrics.put(poolKey, metrics);
					registrar.registerMetrics(name, poolKey.hashCode() + "", remoteAddress, metrics);
				}
				return newPool;
			});

			pool.acquire(Duration.ofMillis(poolFactory.pendingAcquireTimeout))
			    .subscribe(createDisposableAcquire(config, connectionObserver,
			            poolFactory.pendingAcquireTimeout, pool, sink));
		});
	}

	@Override
	public final Mono<Void> disposeLater() {
		return Mono.defer(() -> {
			List<Mono<Void>> pools;
			pools = channelPools.values()
			                    .stream()
			                    .map(Pool::disposeLater)
			                    .collect(Collectors.toList());
			if (pools.isEmpty()) {
				return Mono.empty();
			}
			channelPools.clear();
			return Mono.when(pools);
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
					log.debug("ConnectionProvider[name={}]: Disposing pool for [{}]", name, e.getKey().fqdn);
				}
				e.getValue().dispose();
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

	protected abstract CoreSubscriber<PooledRef<T>> createDisposableAcquire(
			TransportConfig config,
			ConnectionObserver connectionObserver,
			long pendingAcquireTimeout,
			InstrumentedPool<T> pool,
			MonoSink<Connection> sink);

	protected abstract InstrumentedPool<T> createPool(
			TransportConfig config,
			PoolFactory<T> poolFactory,
			SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup);

	protected PoolFactory<T> poolFactory(SocketAddress remoteAddress) {
		return this.defaultPoolFactory;
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

	static final Logger log = Loggers.getLogger(PooledConnectionProvider.class);

	protected static final class PoolFactory<T extends Connection> {
		static final double DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE;
		static {
			double getPermitsSamplingRate =
					Double.parseDouble(System.getProperty(ReactorNetty.POOL_GET_PERMITS_SAMPLING_RATE, "0"));
			if (getPermitsSamplingRate > 1d) {
				DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE = 0;
				log.warn("Invalid configuration [" + ReactorNetty.POOL_GET_PERMITS_SAMPLING_RATE + "=" + getPermitsSamplingRate +
						"], the value must be between 0d and 1d (percentage). SamplingAllocationStrategy in not enabled.");
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
				log.warn("Invalid configuration [" + ReactorNetty.POOL_RETURN_PERMITS_SAMPLING_RATE + "=" + returnPermitsSamplingRate +
						"], the value must be between 0d and 1d (percentage). SamplingAllocationStrategy is enabled.");
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

		PoolFactory(ConnectionPoolSpec<?> conf) {
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
		}

		public InstrumentedPool<T> newPool(
				Publisher<T> allocator,
				@Nullable AllocationStrategy allocationStrategy,
				Function<T, Publisher<Void>> destroyHandler,
				BiPredicate<T, PooledRefMetadata> evictionPredicate) {
			PoolBuilder<T, PoolConfig<T>> poolBuilder =
					PoolBuilder.from(allocator)
					           .destroyHandler(destroyHandler)
					           .evictionPredicate(evictionPredicate
					                   .or((poolable, meta) -> (maxIdleTime != -1 && meta.idleTime() >= maxIdleTime)
					                           || (maxLifeTime != -1 && meta.lifeTime() >= maxLifeTime)))
					           .maxPendingAcquire(pendingAcquireMaxCount)
					           .evictInBackground(evictionInterval);

			if (DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE > 0d && DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE <= 1d
					&& DEFAULT_POOL_RETURN_PERMITS_SAMPLING_RATE > 0d && DEFAULT_POOL_RETURN_PERMITS_SAMPLING_RATE <= 1d) {
				poolBuilder = poolBuilder.allocationStrategy(SamplingAllocationStrategy.sizeBetweenWithSampling(
						0,
						maxConnections,
						DEFAULT_POOL_GET_PERMITS_SAMPLING_RATE,
						DEFAULT_POOL_RETURN_PERMITS_SAMPLING_RATE));
			}
			else {
				poolBuilder = poolBuilder.sizeBetween(0, maxConnections);
			}

			if (LEASING_STRATEGY_FIFO.equals(leasingStrategy)) {
				return poolBuilder.idleResourceReuseLruOrder()
				                  .buildPool();
			}

			return poolBuilder.idleResourceReuseMruOrder()
			                  .buildPool();
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
	}

	static final class PoolKey {
		final String fqdn;
		final SocketAddress holder;
		final int pipelineKey;

		PoolKey(SocketAddress holder, int pipelineKey) {
			this.fqdn = holder.toString();
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
			return Objects.hash(fqdn, holder, pipelineKey);
		}
	}
}
