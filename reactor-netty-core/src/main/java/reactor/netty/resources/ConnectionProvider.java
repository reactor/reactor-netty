/*
 * Copyright (c) 2018-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.resolver.AddressResolverGroup;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.ReactorNetty;
import reactor.netty.internal.util.Metrics;
import reactor.netty.transport.TransportConfig;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A {@link ConnectionProvider} will produce {@link Connection}.
 *
 * @author Stephane Maldini
 * @since 0.8
 */
@FunctionalInterface
public interface ConnectionProvider extends Disposable {

	/**
	 * Default max connections. Fallback to
	 * 2 * available number of processors (but with a minimum value of 16)
	 */
	int DEFAULT_POOL_MAX_CONNECTIONS =
			Integer.parseInt(System.getProperty(ReactorNetty.POOL_MAX_CONNECTIONS,
			"" + Math.max(Runtime.getRuntime().availableProcessors(), 8) * 2));

	/**
	 * Default acquisition timeout (milliseconds) before error. If -1 will never wait to
	 * acquire before opening a new
	 * connection in an unbounded fashion. Fallback 45 seconds
	 */
	long DEFAULT_POOL_ACQUIRE_TIMEOUT = Long.parseLong(System.getProperty(
			ReactorNetty.POOL_ACQUIRE_TIMEOUT,
			"" + 45000));

	/**
	 * Default max idle time, fallback - max idle time is not specified.
	 */
	long DEFAULT_POOL_MAX_IDLE_TIME = Long.parseLong(System.getProperty(
			ReactorNetty.POOL_MAX_IDLE_TIME,
			"-1"));

	/**
	 * Default max life time, fallback - max life time is not specified.
	 */
	long DEFAULT_POOL_MAX_LIFE_TIME = Long.parseLong(System.getProperty(
			ReactorNetty.POOL_MAX_LIFE_TIME,
			"-1"));

	/**
	 * The connection selection is first in, first out.
	 */
	String LEASING_STRATEGY_FIFO = "fifo";

	/**
	 * The connection selection is last in, first out.
	 */
	String LEASING_STRATEGY_LIFO = "lifo";

	/**
	 * Default leasing strategy (fifo, lifo), fallback to fifo.
	 * <ul>
	 *     <li>fifo - The connection selection is first in, first out</li>
	 *     <li>lifo - The connection selection is last in, first out</li>
	 * </ul>
	 */
	String DEFAULT_POOL_LEASING_STRATEGY = System.getProperty(ReactorNetty.POOL_LEASING_STRATEGY, LEASING_STRATEGY_FIFO)
					.toLowerCase(Locale.ENGLISH);

	/**
	 * Creates a builder for {@link ConnectionProvider}.
	 *
	 * @param name {@link ConnectionProvider} name
	 * @return a new ConnectionProvider builder
	 * @since 0.9.5
	 */
	static Builder builder(String name) {
		return new Builder(name);
	}

	/**
	 * Return a {@link ConnectionProvider} that will always create a new
	 * {@link Connection}.
	 *
	 * @return a {@link ConnectionProvider} that will always create a new
	 * {@link Connection}.
	 */
	static ConnectionProvider newConnection() {
		return NewConnectionProvider.INSTANCE;
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max number of
	 * processors observed by this jvm (minimum 4).
	 * Further connections will be pending acquisition until {@link #DEFAULT_POOL_ACQUIRE_TIMEOUT}
	 * and the default pending acquisition max count will be 500.
	 *
	 * @param name the connection pool name
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 * @since 0.9.5
	 */
	static ConnectionProvider create(String name) {
		return builder(name).maxConnections(DEFAULT_POOL_MAX_CONNECTIONS)
		                    .pendingAcquireMaxCount(500)
		                    .pendingAcquireTimeout(Duration.ofMillis(DEFAULT_POOL_ACQUIRE_TIMEOUT))
		                    .build();
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max connection value.
	 * Further connections will be pending acquisition until {@link #DEFAULT_POOL_ACQUIRE_TIMEOUT}
	 * and the default pending acquisition max count will be 2 * max connections value.
	 *
	 * @param name the connection pool name
	 * @param maxConnections the maximum number of connections before starting pending
	 * acquisition on existing ones
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 * @since 0.9.5
	 */
	static ConnectionProvider create(String name, int maxConnections) {
		return builder(name).maxConnections(maxConnections)
		                    .pendingAcquireTimeout(Duration.ofMillis(DEFAULT_POOL_ACQUIRE_TIMEOUT))
		                    .build();
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max connection value.
	 * Further connections will be pending acquisition until {@link #DEFAULT_POOL_ACQUIRE_TIMEOUT}
	 * and the default pending acquisition max count will be 2 * max connections value.
	 *
	 * @param name the connection pool name
	 * @param maxConnections the maximum number of connections before starting pending
	 * acquisition on existing ones
	 * @param metricsEnabled true enables metrics collection; false disables it
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 * @since 1.0.21
	 */
	static ConnectionProvider create(String name, int maxConnections, boolean metricsEnabled) {
		return builder(name).maxConnections(maxConnections)
		                    .pendingAcquireTimeout(Duration.ofMillis(DEFAULT_POOL_ACQUIRE_TIMEOUT))
		                    .metrics(metricsEnabled)
		                    .build();
	}

	/**
	 * Return an existing or new {@link Connection} on subscribe.
	 *
	 * @param config the transport configuration
	 * @param connectionObserver the {@link ConnectionObserver}
	 * @param remoteAddress the {@link SocketAddress} to connect to
	 * @param resolverGroup the resolver which will resolve the address of the unresolved named address
	 * @return an existing or new {@link Mono} of {@link Connection}
	 */
	Mono<? extends Connection> acquire(TransportConfig config,
			ConnectionObserver connectionObserver,
			@Nullable Supplier<? extends SocketAddress> remoteAddress,
			@Nullable AddressResolverGroup<?> resolverGroup);


	/**
	 * Dispose all connection pools for the specified remote address.
	 * <p>
	 * This method has {@code NOOP} default implementation.
	 * {@link ConnectionProvider} implementations may decide to provide more specific implementation.
	 *
	 * @param remoteAddress the remote address
	 */
	default void disposeWhen(SocketAddress remoteAddress) {
	}

	/**
	 * Dispose this ConnectionProvider.
	 * This method is NOT blocking. It is implemented as fire-and-forget.
	 * Use {@link #disposeLater()} when you need to observe the final
	 * status of the operation, combined with {@link Mono#block()}
	 * if you need to synchronously wait for the underlying resources to be disposed.
	 */
	@Override
	@SuppressWarnings("FunctionalInterfaceMethodChanged")
	default void dispose() {
		//noop default
		disposeLater().subscribe();
	}

	/**
	 * Returns a Mono that triggers the disposal of the ConnectionProvider when subscribed to.
	 * <p>
	 * This method has {@code NOOP} default implementation.
	 * {@link ConnectionProvider} implementations may decide to provide more specific implementation.
	 *
	 * @return a Mono representing the completion of the ConnectionProvider disposal.
	 **/
	default Mono<Void> disposeLater() {
		//noop default
		return Mono.empty();
	}

	/**
	 * Returns the maximum number of connections before starting pending.
	 *
	 * @return the maximum number of connections before starting pending
	 */
	default int maxConnections() {
		return -1;
	}

	/**
	 * Returns the maximum number of connections per host before starting pending.
	 *
	 * @return the maximum number of connections per host before starting pending
	 */
	@Nullable
	default Map<SocketAddress, Integer> maxConnectionsPerHost() {
		return null;
	}

	/**
	 * Returns a builder to mutate properties of this {@link ConnectionProvider}.
	 *
	 * @return a builder to mutate properties of this {@link ConnectionProvider}
	 * @since 1.0.14
	 */
	@Nullable
	default Builder mutate() {
		return null;
	}

	/**
	 * Returns {@link ConnectionProvider} name used for metrics.
	 *
	 * @return {@link ConnectionProvider} name used for metrics
	 * @since 1.0.14
	 */
	@Nullable
	default String name() {
		return null;
	}

	interface ConnectionMetadata {

		/**
		 * Returns the number of times the connection has been acquired from the pool. Returns 1 the first time the
		 * connection is allocated.
		 *
		 * @return the number of times the connection has been acquired
		 */
		int acquireCount();

		/**
		 * Returns the time in ms that the connection has been idle.
		 *
		 * @return connection idle time in ms
		 */
		long idleTime();

		/**
		 * Returns the age of the connection in ms.
		 *
		 * @return connection age in ms
		 */
		long lifeTime();

		/**
		 * Returns a timestamp that denotes the order in which the connection was last released, to millisecond
		 * precision.
		 *
		 * @return the connection last release timestamp, or zero if currently acquired
		 */
		long releaseTimestamp();

		/**
		 * Returns a timestamp that denotes the order in which the connection was created/allocated, to millisecond
		 * precision.
		 *
		 * @return the connection creation timestamp
		 */
		long allocationTimestamp();
	}

	interface AllocationStrategy<A extends AllocationStrategy<A>> {

		/**
		 * Returns a deep copy of this instance.
		 *
		 * @return a deep copy of this instance
		 */
		A copy();

		/**
		 * Best-effort peek at the state of the strategy which indicates roughly how many more connections can currently be
		 * allocated. Should be paired with {@link #getPermits(int)} for an atomic permission.
		 *
		 * @return an ESTIMATED count of how many more connections can currently be allocated
		 */
		int estimatePermitCount();

		/**
		 * Try to get the permission to allocate a {@code desired} positive number of new connections. Returns the permissible
		 * number of connections which MUST be created (otherwise the internal live counter of the strategy might be off).
		 * This permissible number might be zero, and it can also be a greater number than {@code desired}.
		 * Once a connection is discarded from the pool, it must update the strategy using {@link #returnPermits(int)}
		 * (which can happen in batches or with value {@literal 1}).
		 *
		 * @param desired the desired number of new connections
		 * @return the actual number of new connections that MUST be created, can be 0 and can be more than {@code desired}
		 */
		int getPermits(int desired);

		/**
		 * Returns the best estimate of the number of permits currently granted, between 0 and {@link Integer#MAX_VALUE}.
		 *
		 * @return the best estimate of the number of permits currently granted, between 0 and {@link Integer#MAX_VALUE}
		 */
		int permitGranted();

		/**
		 * Return the minimum number of permits this strategy tries to maintain granted
		 * (reflecting a minimal size for the pool), or {@code 0} for scale-to-zero.
		 *
		 * @return the minimum number of permits this strategy tries to maintain, or {@code 0}
		 */
		int permitMinimum();

		/**
		 * Returns the maximum number of permits this strategy can grant in total, or {@link Integer#MAX_VALUE} for unbounded.
		 *
		 * @return the maximum number of permits this strategy can grant in total, or {@link Integer#MAX_VALUE} for unbounded
		 */
		int permitMaximum();

		/**
		 * Update the strategy to indicate that N connections were discarded, potentially leaving space
		 * for N new ones to be allocated. Users MUST ensure that this method isn't called with a value greater than the
		 * number of held permits it has.
		 * <p>
		 * Some strategy MIGHT throw an {@link IllegalArgumentException} if it can be determined the number of returned permits
		 * is not consistent with the strategy's limits and delivered permits.
		 */
		void returnPermits(int returned);
	}

	/**
	 * Build a {@link ConnectionProvider} to cache and reuse a fixed maximum number of
	 * {@link Connection}. Further connections will be pending acquisition depending on
	 * pendingAcquireTime. The maximum number of connections is for the connections in a single
	 * connection pool, where a connection pool corresponds to a concrete remote host.
	 * The configuration can be either global for all connection pools or
	 * be tuned for each individual connection pool, per remote host.
	 * @since 0.9.5
	 */
	final class Builder extends ConnectionPoolSpec<Builder> {

		static final Duration DISPOSE_INACTIVE_POOLS_IN_BACKGROUND_DISABLED = Duration.ZERO;

		String name;
		Duration inactivePoolDisposeInterval = DISPOSE_INACTIVE_POOLS_IN_BACKGROUND_DISABLED;
		Duration poolInactivity;
		Duration disposeTimeout;
		final Map<SocketAddress, ConnectionPoolSpec<?>> confPerRemoteHost = new HashMap<>();

		/**
		 * Returns {@link Builder} new instance with name and default properties for all connection pools.
		 *
		 * @param name {@link ConnectionProvider} name
		 */
		private Builder(String name) {
			super();
			name(name);
		}

		Builder(Builder copy) {
			super(copy);
			this.name = copy.name;
			this.inactivePoolDisposeInterval = copy.inactivePoolDisposeInterval;
			this.poolInactivity = copy.poolInactivity;
			this.disposeTimeout = copy.disposeTimeout;
			copy.confPerRemoteHost.forEach((address, spec) -> this.confPerRemoteHost.put(address, new ConnectionPoolSpec<>(spec)));
		}

		/**
		 * {@link ConnectionProvider} name is used for metrics.
		 *
		 * @param name {@link ConnectionProvider} name
		 * @return {@literal this}
		 * @throws NullPointerException if name is null
		 */
		public final Builder name(String name) {
			this.name = Objects.requireNonNull(name, "name");
			return this;
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} background disposal for inactive connection pools.
		 * When this option is enabled, the connection pools are regularly checked whether they are <strong>empty</strong> and inactive
		 * for a specified time, thus applicable for disposal. Connection pool is considered
		 * <strong>empty</strong> when there are no active connections, idle connections and pending acquisitions.
		 * Default to {@link #DISPOSE_INACTIVE_POOLS_IN_BACKGROUND_DISABLED} - the background disposal is disabled.
		 * Providing a {@code disposeInterval} of {@link Duration#ZERO zero} means the background disposal is disabled.
		 *
		 * @param disposeInterval specifies the interval to be used for checking the connection pool inactivity, (resolution: ms)
		 * @param poolInactivity specifies the duration after which an empty pool with
		 * no recorded interactions is considered inactive (resolution: seconds)
		 * @return {@literal this}
		 * @since 1.0.7
		 */
		public final Builder disposeInactivePoolsInBackground(Duration disposeInterval, Duration poolInactivity) {
			this.inactivePoolDisposeInterval = Objects.requireNonNull(disposeInterval, "disposeInterval");
			this.poolInactivity = Objects.requireNonNull(poolInactivity, "poolInactivity");
			return get();
		}

		/**
		 * When {@link ConnectionProvider#dispose()} or {@link ConnectionProvider#disposeLater()} is called,
		 * trigger a {@code graceful shutdown} for the connection pools, with this grace period timeout.
		 * From there on, all calls for acquiring a connection will fail fast with an exception.
		 * However, for the provided {@link Duration}, pending acquires will get a chance to be served.
		 * <p><strong>Note:</strong> The rejection of new acquires and the grace timer start immediately,
		 * irrespective of subscription to the {@link Mono} returned by {@link ConnectionProvider#disposeLater()}.
		 * Subsequent calls return the same {@link Mono}, effectively getting notifications from the first graceful
		 * shutdown call and ignoring subsequently provided timeouts.
		 *
		 * @param timeout the maximum {@link Duration} for graceful shutdown before full shutdown is forced (resolution: ms)
		 * @return {@literal this}
		 * @since 1.0.12
		 */
		public final Builder disposeTimeout(Duration timeout) {
			this.disposeTimeout = Objects.requireNonNull(timeout, "disposeTimeout");
			return get();
		}

		/**
		 * Connection pool configuration for a specific remote host.
		 *
		 * @param remoteHost the remote host
		 * @param spec connection pool configuration for this remote host
		 * @return {@literal this}
		 * @throws NullPointerException if remoteHost or/and spec are null
		 */
		public final Builder forRemoteHost(SocketAddress remoteHost, Consumer<HostSpecificSpec> spec) {
			Objects.requireNonNull(remoteHost, "remoteHost");
			Objects.requireNonNull(spec, "spec");
			HostSpecificSpec builder = new HostSpecificSpec();
			spec.accept(builder);
			this.confPerRemoteHost.put(remoteHost, builder);
			return this;
		}

		/**
		 * Builds new ConnectionProvider.
		 *
		 * @return builds new ConnectionProvider
		 */
		public ConnectionProvider build() {
			return new DefaultPooledConnectionProvider(this);
		}
	}

	/**
	 * Configuration for a connection pool.
	 *
	 * @since 0.9.5
	 */
	class ConnectionPoolSpec<SPEC extends ConnectionPoolSpec<SPEC>> implements Supplier<SPEC> {

		static final Duration EVICT_IN_BACKGROUND_DISABLED       = Duration.ZERO;
		static final int PENDING_ACQUIRE_MAX_COUNT_NOT_SPECIFIED = -2;

		Duration evictionInterval       = EVICT_IN_BACKGROUND_DISABLED;
		int      maxConnections         = DEFAULT_POOL_MAX_CONNECTIONS;
		int      pendingAcquireMaxCount = PENDING_ACQUIRE_MAX_COUNT_NOT_SPECIFIED;
		Duration pendingAcquireTimeout  = Duration.ofMillis(DEFAULT_POOL_ACQUIRE_TIMEOUT);
		Duration maxIdleTime;
		Duration maxLifeTime;
		boolean  metricsEnabled;
		String   leasingStrategy        = DEFAULT_POOL_LEASING_STRATEGY;
		Supplier<? extends ConnectionProvider.MeterRegistrar> registrar;
		BiFunction<Runnable, Duration, Disposable> pendingAcquireTimer;
		AllocationStrategy<?> allocationStrategy;
		BiPredicate<Connection, ConnectionMetadata> evictionPredicate;

		/**
		 * Returns {@link ConnectionPoolSpec} new instance with default properties.
		 */
		private ConnectionPoolSpec() {
			if (DEFAULT_POOL_MAX_IDLE_TIME > -1) {
				maxIdleTime(Duration.ofMillis(DEFAULT_POOL_MAX_IDLE_TIME));
			}
			if (DEFAULT_POOL_MAX_LIFE_TIME > -1) {
				maxLifeTime(Duration.ofMillis(DEFAULT_POOL_MAX_LIFE_TIME));
			}
		}

		ConnectionPoolSpec(ConnectionPoolSpec<SPEC> copy) {
			this.evictionInterval = copy.evictionInterval;
			this.maxConnections = copy.maxConnections;
			this.pendingAcquireMaxCount = copy.pendingAcquireMaxCount;
			this.pendingAcquireTimeout = copy.pendingAcquireTimeout;
			this.maxIdleTime = copy.maxIdleTime;
			this.maxLifeTime = copy.maxLifeTime;
			this.metricsEnabled = copy.metricsEnabled;
			this.leasingStrategy = copy.leasingStrategy;
			this.registrar = copy.registrar;
			this.pendingAcquireTimer = copy.pendingAcquireTimer;
			this.allocationStrategy = copy.allocationStrategy;
			this.evictionPredicate = copy.evictionPredicate;
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} acquire timeout (resolution: ms).
		 * Default to {@link #DEFAULT_POOL_ACQUIRE_TIMEOUT}.
		 *
		 * @param pendingAcquireTimeout the maximum time after which a pending acquire
		 * must complete or the {@link TimeoutException} will be thrown (resolution: ms)
		 * @return {@literal this}
		 * @throws NullPointerException if pendingAcquireTimeout is null
		 */
		public final SPEC pendingAcquireTimeout(Duration pendingAcquireTimeout) {
			this.pendingAcquireTimeout = Objects.requireNonNull(pendingAcquireTimeout, "pendingAcquireTimeout");
			return get();
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} maximum connections per connection pool.
		 * This is a pre-made allocation strategy where only max connections is specified.
		 * Custom allocation strategies can be provided via {@link #allocationStrategy(AllocationStrategy)}.
		 * Default to {@link #DEFAULT_POOL_MAX_CONNECTIONS}.
		 *
		 * @param maxConnections the maximum number of connections (per connection pool) before start pending
		 * @return {@literal this}
		 * @throws IllegalArgumentException if maxConnections is negative
		 * @see #allocationStrategy(AllocationStrategy)
		 */
		public final SPEC maxConnections(int maxConnections) {
			if (maxConnections <= 0) {
				throw new IllegalArgumentException("Max Connections value must be strictly positive");
			}
			this.maxConnections = maxConnections;
			this.allocationStrategy = null;
			return get();
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} the maximum number of registered
		 * requests for acquire to keep in a pending queue
		 * When invoked with -1 the pending queue will not have upper limit.
		 * Default to {@code 2 * max connections}.
		 *
		 * @param pendingAcquireMaxCount the maximum number of registered requests for acquire to keep
		 * in a pending queue
		 * @return {@literal this}
		 * @throws IllegalArgumentException if pendingAcquireMaxCount is negative
		 */
		public final SPEC pendingAcquireMaxCount(int pendingAcquireMaxCount) {
			if (pendingAcquireMaxCount != -1 && pendingAcquireMaxCount <= 0) {
				throw new IllegalArgumentException("Pending acquire max count must be strictly positive");
			}
			this.pendingAcquireMaxCount = pendingAcquireMaxCount;
			return get();
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} max idle time (resolution: ms).
		 * Default to {@link #DEFAULT_POOL_MAX_IDLE_TIME} if specified otherwise - no max idle time.
		 * <p><strong>Note:</strong> This configuration is not applicable for {@link reactor.netty.tcp.TcpClient}.
		 * A TCP connection is always closed and never returned to the pool.
		 *
		 * @param maxIdleTime the {@link Duration} after which the channel will be closed when idle (resolution: ms)
		 * @return {@literal this}
		 * @throws NullPointerException if maxIdleTime is null
		 */
		public final SPEC maxIdleTime(Duration maxIdleTime) {
			this.maxIdleTime = Objects.requireNonNull(maxIdleTime);
			return get();
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} max life time (resolution: ms).
		 * Default to {@link #DEFAULT_POOL_MAX_LIFE_TIME} if specified otherwise - no max life time.
		 * <p><strong>Note:</strong> This configuration is not applicable for {@link reactor.netty.tcp.TcpClient}.
		 * A TCP connection is always closed and never returned to the pool.
		 *
		 * @param maxLifeTime the {@link Duration} after which the channel will be closed (resolution: ms)
		 * @return {@literal this}
		 * @throws NullPointerException if maxLifeTime is null
		 */
		public final SPEC maxLifeTime(Duration maxLifeTime) {
			this.maxLifeTime = Objects.requireNonNull(maxLifeTime);
			return get();
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} custom eviction predicate.
		 * <p>Unless a custom eviction predicate is specified, the connection is evicted when not active or not persistent,
		 * If {@link #maxLifeTime(Duration)} and/or {@link #maxIdleTime(Duration)} settings are configured,
		 * they are also taken into account.
		 * <p>Otherwise only the custom eviction predicate is invoked.
		 * <p><strong>Note:</strong> This configuration is not applicable for {@link reactor.netty.tcp.TcpClient}.
		 * A TCP connection is always closed and never returned to the pool.
		 *
		 * @param evictionPredicate The predicate function that evaluates whether a connection should be evicted
		 * @return {@literal this}
		 * @throws NullPointerException if evictionPredicate is null
		 */
		public final SPEC evictionPredicate(BiPredicate<Connection, ConnectionMetadata> evictionPredicate) {
			this.evictionPredicate = Objects.requireNonNull(evictionPredicate);
			return get();
		}

		/**
		 * Whether to enable metrics to be collected and registered in Micrometer's
		 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}
		 * under the name {@link reactor.netty.Metrics#CONNECTION_PROVIDER_PREFIX}.
		 * Applications can separately register their own
		 * {@link io.micrometer.core.instrument.config.MeterFilter filters} associated with this name.
		 * For example, to put an upper bound on the number of tags produced:
		 * <pre class="code">
		 * MeterFilter filter = ... ;
		 * Metrics.globalRegistry.config().meterFilter(MeterFilter.maximumAllowableTags(CONNECTION_PROVIDER_PREFIX, 100, filter));
		 * </pre>
		 * <p>By default this is not enabled.
		 *
		 * @param metricsEnabled true enables metrics collection; false disables it
		 * @return {@literal this}
		 */
		public final SPEC metrics(boolean metricsEnabled) {
			if (metricsEnabled) {
				if (!Metrics.isMicrometerAvailable()) {
					throw new UnsupportedOperationException(
							"To enable metrics, you must add the dependency `io.micrometer:micrometer-core`" +
									" to the class path first");
				}
			}
			this.metricsEnabled = metricsEnabled;
			return get();
		}

		/**
		 * Specifies whether the metrics are enabled on the {@link ConnectionProvider}.
		 * All generated metrics are provided to the specified registrar
		 * which is only instantiated if metrics are being enabled.
		 *
		 * @param metricsEnabled true enables metrics collection; false disables it
		 * @param registrar a supplier for the {@link MeterRegistrar}
		 * @return {@literal this}
		 * @since 0.9.11
		 */
		public final SPEC metrics(boolean metricsEnabled, Supplier<? extends ConnectionProvider.MeterRegistrar> registrar) {
			this.metricsEnabled = metricsEnabled;
			this.registrar = Objects.requireNonNull(registrar);
			return get();
		}

		/**
		 * Configure the pool so that if there are idle connections (i.e. pool is under-utilized),
		 * the next acquire operation will get the <b>Most Recently Used</b> connection
		 * (MRU, i.e. the connection that was released last among the current idle connections).
		 * <p><strong>Note:</strong> This configuration is not applicable for {@link reactor.netty.tcp.TcpClient}.
		 * A TCP connection is always closed and never returned to the pool.
		 *
		 * @return {@literal this}
		 */
		public final SPEC lifo() {
			this.leasingStrategy = LEASING_STRATEGY_LIFO;
			return get();
		}

		/**
		 * Configure the pool so that if there are idle connections (i.e. pool is under-utilized),
		 * the next acquire operation will get the <b>Least Recently Used</b> connection
		 * (LRU, i.e. the connection that was released first among the current idle connections).
		 * <p><strong>Note:</strong> This configuration is not applicable for {@link reactor.netty.tcp.TcpClient}.
		 * A TCP connection is always closed and never returned to the pool.
		 *
		 * @return {@literal this}
		 */
		public final SPEC fifo() {
			this.leasingStrategy = LEASING_STRATEGY_FIFO;
			return get();
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} background eviction.
		 * When a background eviction is enabled, the connection pool is regularly checked for connections,
		 * that are applicable for removal.
		 * Default to {@link #EVICT_IN_BACKGROUND_DISABLED} - the background eviction is disabled.
		 * Providing an {@code evictionInterval} of {@link Duration#ZERO zero} means the background eviction is disabled.
		 * <p><strong>Note:</strong> This configuration is not applicable for {@link reactor.netty.tcp.TcpClient}.
		 * A TCP connection is always closed and never returned to the pool.
		 *
		 * @param evictionInterval specifies the interval to be used for checking the connection pool, (resolution: ns)
		 * @return {@literal this}
		 */
		public final SPEC evictInBackground(Duration evictionInterval) {
			this.evictionInterval = Objects.requireNonNull(evictionInterval, "evictionInterval");
			return get();
		}

		/**
		 * Set the option to use for configuring {@link ConnectionProvider} pending acquire timer.
		 * The pending acquire timer must be specified as a function which is used to schedule a pending acquire timeout
		 * when there is no idle connection and no new connection can be created currently.
		 * The function takes as argument a {@link Duration} which is the one configured by {@link #pendingAcquireTimeout(Duration)}.
		 * <p>
		 * Use this function if you want to specify your own implementation for scheduling pending acquire timers.
		 *
		 * <p> Default to {@link Schedulers#parallel()}.
		 *
		 * <p>Examples using Netty HashedWheelTimer implementation:</p>
		 * <pre>
		 * {@code
		 * final static HashedWheelTimer wheel = new HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024);
		 *
		 * HttpClient client = HttpClient.create(
		 *     ConnectionProvider.builder("myprovider")
		 *         .pendingAcquireTimeout(Duration.ofMillis(10000))
		 *         .pendingAcquireTimer((r, d) -> {
		 *             Timeout t = wheel.newTimeout(timeout -> r.run(), d.toMillis(), TimeUnit.MILLISECONDS);
		 *             return () -> t.cancel();
		 *         })
		 *         .build());
		 * }
		 * </pre>
		 *
		 * @param pendingAcquireTimer the function to apply when scheduling pending acquire timers
		 * @return {@literal this}
		 * @throws NullPointerException if pendingAcquireTimer is null
		 * @since 1.0.20
		 * @see #pendingAcquireTimeout(Duration)
		 */
		public final SPEC pendingAcquireTimer(BiFunction<Runnable, Duration, Disposable> pendingAcquireTimer) {
			this.pendingAcquireTimer = Objects.requireNonNull(pendingAcquireTimer, "pendingAcquireTimer");
			return get();
		}

		/**
		 * Limits in how many connections can be allocated and managed by the pool are driven by the
		 * provided {@link AllocationStrategy}. This is a customization escape hatch that replaces the last
		 * configured strategy, but most cases should be covered by the {@link #maxConnections()}
		 * pre-made allocation strategy.
		 *
		 * @param allocationStrategy the {@link AllocationStrategy} to use
		 * @return {@literal this}
		 * @since 1.0.20
		 * @see #maxConnections()
		 */
		public final SPEC allocationStrategy(AllocationStrategy<?> allocationStrategy) {
			this.allocationStrategy = Objects.requireNonNull(allocationStrategy, "allocationStrategy");
			return get();
		}

		@Override
		@SuppressWarnings("unchecked")
		public SPEC get() {
			return (SPEC) this;
		}
	}

	/**
	 * Configuration for a connection pool per remote host.
	 *
	 * @since 0.9.5
	 */
	final class HostSpecificSpec extends ConnectionPoolSpec<HostSpecificSpec> {
	}


	/**
	 * A strategy to register which metrics are collected in a particular connection pool.
	 */
	interface MeterRegistrar {

		/**
		 * Invoked when a connection pool is created.
		 *
		 * @param poolName the pool name
		 * @param id the pool id
		 * @param remoteAddress the remote address
		 * @param metrics the pool metrics
		 */
		void registerMetrics(String poolName, String id, SocketAddress remoteAddress, ConnectionPoolMetrics metrics);

		/**
		 * Invoked when a connection pool is disposed.
		 *
		 * @param poolName the pool name
		 * @param id the pool id
		 * @param remoteAddress the remote address
		 * @since 1.0.26
		 */
		default void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
		}
	}
}
