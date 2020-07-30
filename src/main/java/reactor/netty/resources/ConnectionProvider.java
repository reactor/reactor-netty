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

import io.netty.bootstrap.Bootstrap;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ReactorNetty;
import reactor.pool.InstrumentedPool;
import reactor.pool.Pool;
import reactor.pool.PoolBuilder;
import reactor.util.Metrics;
import reactor.util.annotation.NonNull;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A {@link ConnectionProvider} will produce {@link Connection}
 *
 * @author Stephane Maldini
 * @since 0.8
 */
@FunctionalInterface
public interface ConnectionProvider extends Disposable {

	/**
	 * Default max connections. Fallback to
	 * available number of processors (but with a minimum value of 16)
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
	 * Creates a builder for {@link ConnectionProvider}
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
	 * Create a {@link ConnectionProvider} to cache and grow on demand {@link Connection}.
	 * <p>An elastic {@link ConnectionProvider} will never wait before opening a new
	 * connection. The reuse window is limited but it cannot starve an undetermined volume
	 * of clients using it.
	 *
	 * @param name the channel pool map name
	 *
	 * @return a new {@link ConnectionProvider} to cache and grow on demand {@link Connection}
	 * @deprecated as of 0.9.5. Use {@link #builder(String)}
	 */
	@Deprecated
	static ConnectionProvider elastic(String name) {
		return elastic(name, null, null);
	}

	/**
	 * Create a {@link ConnectionProvider} to cache and grow on demand {@link Connection}.
	 * <p>An elastic {@link ConnectionProvider} will never wait before opening a new
	 * connection. The reuse window is limited but it cannot starve an undetermined volume
	 * of clients using it.
	 *
	 * @param name the channel pool map name
	 * @param maxIdleTime the {@link Duration} after which the channel will be closed when idle (resolution: ms),
	 *                    if {@code NULL} there is no max idle time
	 *
	 * @return a new {@link ConnectionProvider} to cache and grow on demand {@link Connection}
	 * @deprecated as of 0.9.5. Use {@link #builder(String)}
	 */
	@Deprecated
	static ConnectionProvider elastic(String name, @Nullable Duration maxIdleTime) {
		return elastic(name, maxIdleTime, null);
	}

	/**
	 * Create a {@link ConnectionProvider} to cache and grow on demand {@link Connection}.
	 * <p>An elastic {@link ConnectionProvider} will never wait before opening a new
	 * connection. The reuse window is limited but it cannot starve an undetermined volume
	 * of clients using it.
	 *
	 * @param name the channel pool map name
	 * @param maxIdleTime the {@link Duration} after which the channel will be closed when idle (resolution: ms),
	 *                    if {@code NULL} there is no max idle time
	 * @param maxLifeTime the {@link Duration} after which the channel will be closed (resolution: ms),
	 *                    if {@code NULL} there is no max life time
	 *
	 * @return a new {@link ConnectionProvider} to cache and grow on demand {@link Connection}
	 * @deprecated as of 0.9.5. Use {@link #builder(String)}
	 */
	@Deprecated
	static ConnectionProvider elastic(String name, @Nullable Duration maxIdleTime, @Nullable Duration maxLifeTime) {
		return builder(name).maxConnections(Integer.MAX_VALUE)
		                    .pendingAcquireTimeout(Duration.ofMillis(0))
		                    .pendingAcquireMaxCount(-1)
		                    .maxIdleTime(maxIdleTime)
		                    .maxLifeTime(maxLifeTime)
		                    .build();
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max number of
	 * processors observed by this jvm (minimum 4).
	 * Further connections will be pending acquisition until {@link #DEFAULT_POOL_ACQUIRE_TIMEOUT}
	 * and the default pending acquisition max count will be unbounded.
	 *
	 * @param name the connection pool name
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 * @deprecated as of 0.9.5. Use {@link #create(String)}
	 */
	@Deprecated
	static ConnectionProvider fixed(String name) {
		return fixed(name, DEFAULT_POOL_MAX_CONNECTIONS);
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max connection value.
	 * Further connections will be pending acquisition until {@link #DEFAULT_POOL_ACQUIRE_TIMEOUT}
	 * and the default pending acquisition max count will be unbounded.
	 *
	 * @param name the connection pool name
	 * @param maxConnections the maximum number of connections before starting pending
	 * acquisition on existing ones
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 * @deprecated as of 0.9.5. Use {@link #create(String, int)}
	 */
	@Deprecated
	static ConnectionProvider fixed(String name, int maxConnections) {
		return fixed(name, maxConnections, DEFAULT_POOL_ACQUIRE_TIMEOUT);
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max connection value.
	 * Further connections will be pending acquisition until acquireTimeout
	 * and the default pending acquisition max count will be unbounded.
	 *
	 * @param name the connection pool name
	 * @param maxConnections the maximum number of connections before starting pending
	 * @param acquireTimeout the maximum time in millis after which a pending acquire
	 *                          must complete or the {@link TimeoutException} will be thrown.
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 * @deprecated as of 0.9.5. Use {@link #builder(String)}
	 */
	@Deprecated
	static ConnectionProvider fixed(String name, int maxConnections, long acquireTimeout) {
		return fixed(name, maxConnections, acquireTimeout, null, null);
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max connection value.
	 * Further connections will be pending acquisition until acquireTimeout
	 * and the default pending acquisition max count will be unbounded.
	 *
	 * @param name the connection pool name
	 * @param maxConnections the maximum number of connections before starting pending
	 * @param acquireTimeout the maximum time in millis after which a pending acquire
	 *                          must complete or the {@link TimeoutException} will be thrown.
	 * @param maxIdleTime the {@link Duration} after which the channel will be closed when idle (resolution: ms),
	 *                    if {@code NULL} there is no max idle time
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 * @deprecated as of 0.9.5. Use {@link #builder(String)}
	 */
	@Deprecated
	static ConnectionProvider fixed(String name, int maxConnections, long acquireTimeout, @Nullable Duration maxIdleTime) {
		return fixed(name, maxConnections, acquireTimeout, maxIdleTime, null);
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max connection value.
	 * Further connections will be pending acquisition until acquireTimeout
	 * and the default pending acquisition max count will be unbounded.
	 *
	 * @param name the connection pool name
	 * @param maxConnections the maximum number of connections before starting pending
	 * @param acquireTimeout the maximum time in millis after which a pending acquire
	 *                          must complete or the {@link TimeoutException} will be thrown.
	 * @param maxIdleTime the {@link Duration} after which the channel will be closed when idle (resolution: ms),
	 *                    if {@code NULL} there is no max idle time
	 * @param maxLifeTime the {@link Duration} after which the channel will be closed (resolution: ms),
	 *                    if {@code NULL} there is no max life time
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 * @deprecated as of 0.9.5. Use {@link #builder(String)}
	 */
	@Deprecated
	static ConnectionProvider fixed(String name, int maxConnections, long acquireTimeout, @Nullable Duration maxIdleTime, @Nullable Duration maxLifeTime) {
		if (maxConnections == -1) {
			return elastic(name, maxIdleTime, maxLifeTime);
		}
		if (acquireTimeout < 0) {
			throw new IllegalArgumentException("Acquire Timeout value must be positive");
		}
		return builder(name).maxConnections(maxConnections)
		                    .pendingAcquireMaxCount(-1) // keep the backwards compatibility
		                    .pendingAcquireTimeout(Duration.ofMillis(acquireTimeout))
		                    .maxIdleTime(maxIdleTime)
		                    .maxLifeTime(maxLifeTime)
		                    .build();
	}

	/**
	 * Return an existing or new {@link Connection} on subscribe.
	 *
	 * @param bootstrap the client connection {@link Bootstrap}
	 *
	 * @return an existing or new {@link Mono} of {@link Connection}
	 */
	Mono<? extends Connection> acquire(Bootstrap bootstrap);


	default void disposeWhen(@NonNull SocketAddress address) {
	}

	/**
	 * Dispose this ConnectionProvider.
	 * This method is NOT blocking. It is implemented as fire-and-forget.
	 * Use {@link #disposeLater()} when you need to observe the final
	 * status of the operation, combined with {@link Mono#block()}
	 * if you need to synchronously wait for the underlying resources to be disposed.
	 */
	@Override
	default void dispose() {
		//noop default
		disposeLater().subscribe();
	}

	/**
	 * Returns a Mono that triggers the disposal of the ConnectionProvider when subscribed to.
	 *
	 * @return a Mono representing the completion of the ConnectionProvider disposal.
	 **/
	default Mono<Void> disposeLater() {
		//noop default
		return Mono.empty();
	}

	/**
	 * Returns the maximum number of connections before starting pending
	 *
	 * @return the maximum number of connections before starting pending
	 * @deprecated as of 0.9.5.
	 */
	@Deprecated
	default int maxConnections() {
		return -1;
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

		String name;
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

		/**
		 * {@link ConnectionProvider} name is used for metrics
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
		 * Connection pool configuration for a specific remote host.
		 *
		 * @param remoteHost the remote host
		 * @param spec connection pool configuration for a this remote host
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
		 * Builds new ConnectionProvider
		 *
		 * @return builds new ConnectionProvider
		 */
		public ConnectionProvider build() {
			return new PooledConnectionProvider(this);
		}
	}

	/**
	 * Configuration for a connection pool.
	 *
	 * @since 0.9.5
	 */
	class ConnectionPoolSpec<SPEC extends ConnectionPoolSpec<SPEC>> implements Supplier<SPEC> {

		static final int PENDING_ACQUIRE_MAX_COUNT_NOT_SPECIFIED = -2;

		int      maxConnections         = DEFAULT_POOL_MAX_CONNECTIONS;
		int      pendingAcquireMaxCount = PENDING_ACQUIRE_MAX_COUNT_NOT_SPECIFIED;
		Duration pendingAcquireTimeout  = Duration.ofMillis(DEFAULT_POOL_ACQUIRE_TIMEOUT);
		Duration maxIdleTime;
		Duration maxLifeTime;
		boolean  metricsEnabled;
		Function<PoolBuilder<PooledConnectionProvider.PooledConnection, ?>,
				InstrumentedPool<PooledConnectionProvider.PooledConnection>> leasingStrategy;
		Supplier<? extends ConnectionProvider.MeterRegistrar> registrar;

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
			if(LEASING_STRATEGY_LIFO.equals(DEFAULT_POOL_LEASING_STRATEGY)) {
				lifo();
			}
			else {
				fifo();
			}
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
		 * Default to {@link #DEFAULT_POOL_MAX_CONNECTIONS}.
		 *
		 * @param maxConnections the maximum number of connections (per connection pool) before start pending
		 * @return {@literal this}
		 * @throws IllegalArgumentException if maxConnections is negative
		 */
		public final SPEC maxConnections(int maxConnections) {
			if (maxConnections <= 0) {
				throw new IllegalArgumentException("Max Connections value must be strictly positive");
			}
			this.maxConnections = maxConnections;
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
		 *
		 * @param maxIdleTime the {@link Duration} after which the channel will be closed when idle (resolution: ms)
		 * @return {@literal this}
		 */
		public final SPEC maxIdleTime(Duration maxIdleTime) {
			this.maxIdleTime = maxIdleTime;
			return get();
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} max life time (resolution: ms).
		 * Default to {@link #DEFAULT_POOL_MAX_LIFE_TIME} if specified otherwise - no max life time.
		 *
		 * @param maxLifeTime the {@link Duration} after which the channel will be closed (resolution: ms)
		 * @return {@literal this}
		 */
		public final SPEC maxLifeTime(Duration maxLifeTime) {
			this.maxLifeTime = maxLifeTime;
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
				if (!Metrics.isInstrumentationAvailable()) {
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
		 * Build a LIFO flavor of {@link Pool}, that is to say a flavor where the last
		 * {@link Pool#acquire()} {@link Mono Mono} that was pending is served first
		 * whenever a resource becomes available.
		 *
		 * @return a builder of {@link Pool} with LIFO pending acquire ordering
		 */
		public final SPEC lifo() {
			this.leasingStrategy = PoolBuilder::lifo;
			return get();
		}

		/**
		 * Build a FIFO flavor of {@link Pool}, that is to say a flavor where the first
		 * {@link Pool#acquire()} {@link Mono Mono} that was pending is served first
		 * whenever a resource becomes available.
		 *
		 * @return a builder of {@link Pool} with FIFO pending acquire ordering
		 */
		public final SPEC fifo() {
			this.leasingStrategy = PoolBuilder::fifo;
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
	 *
	 * Default implementation of this interface is {@link MicrometerPooledConnectionProviderMeterRegistrar}
	 */
	interface MeterRegistrar {

		void registerMetrics(String poolName, String id, SocketAddress remoteAddress, ConnectionPoolMetrics metrics);

	}
}
