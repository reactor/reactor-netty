/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
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
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ReactorNetty;
import reactor.pool.InstrumentedPool;
import reactor.pool.PoolBuilder;
import reactor.pool.PoolConfig;
import reactor.pool.PooledRefMetadata;
import reactor.util.annotation.NonNull;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * A {@link ConnectionProvider} will produce {@link Connection}
 *
 * @author Stephane Maldini
 * @since 0.8
 */
@FunctionalInterface
public interface ConnectionProvider extends Disposable {

	int MAX_CONNECTIONS_ELASTIC = -1;
	int ACQUIRE_TIMEOUT_NEVER_WAIT = 0;

	/**
	 * Default max connections, if -1 will never wait to acquire before opening a new
	 * connection in an unbounded fashion. Fallback to
	 * available number of processors (but with a minimum value of 16)
	 */
	int DEFAULT_POOL_MAX_CONNECTIONS =
			Integer.parseInt(System.getProperty(ReactorNetty.POOL_MAX_CONNECTIONS,
			"" + Math.max(Runtime.getRuntime()
			            .availableProcessors(), 8) * 2));

	/**
	 * Default acquisition timeout (milliseconds) before error. If -1 will never wait to
	 * acquire before opening a new
	 * connection in an unbounded fashion. Fallback 45 seconds
	 */
	long DEFAULT_POOL_ACQUIRE_TIMEOUT = Long.parseLong(System.getProperty(
			ReactorNetty.POOL_ACQUIRE_TIMEOUT,
			"" + 45000));

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
	 * Create a {@link ConnectionProvider} to cache and grow on demand {@link Connection}.
	 * <p>An elastic {@link ConnectionProvider} will never wait before opening a new
	 * connection. The reuse window is limited but it cannot starve an undetermined volume
	 * of clients using it.
	 *
	 * @param name the channel pool map name
	 *
	 * @return a new {@link ConnectionProvider} to cache and grow on demand
	 * {@link Connection}
	 */
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
	 * @param maxLifeTime the {@link Duration} after which the channel will be closed (resolution: ms),
	 *                    if {@code NULL} there is no max life time
	 *
	 * @return a new {@link ConnectionProvider} to cache and grow on demand
	 * {@link Connection}
	 */
	static ConnectionProvider elastic(String name, @Nullable Duration maxIdleTime, @Nullable Duration maxLifeTime) {
		return Builder.newInstance(name)
				.maxConnections(MAX_CONNECTIONS_ELASTIC)
				.acquireTimeout(ACQUIRE_TIMEOUT_NEVER_WAIT)
				.maxIdleTime(maxIdleTime)
				.maxLifeTime(maxLifeTime)
				.build();
	}

	/**
	 * a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max number of
	 * processors observed by this jvm (minimum 4).
	 * Further connections will be pending acquisition indefinitely.
	 *
	 * @param name the connection pool name
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 */
	static ConnectionProvider fixed(String name) {
		return fixed(name, DEFAULT_POOL_MAX_CONNECTIONS);
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max connection value.
	 * Further connections will be pending acquisition indefinitely.
	 *
	 * @param name the connection pool name
	 * @param maxConnections the maximum number of connections before starting pending
	 * acquisition on existing ones
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 */
	static ConnectionProvider fixed(String name, int maxConnections) {
		return fixed(name, maxConnections, DEFAULT_POOL_ACQUIRE_TIMEOUT);
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max connection value.
	 * Further connections will be pending acquisition indefinitely.
	 *
	 * @param name the connection pool name
	 * @param maxConnections the maximum number of connections before starting pending
	 * @param acquireTimeout the maximum time in millis after which a pending acquire
	 *                          must complete or the {@link TimeoutException} will be thrown.
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 */
	static ConnectionProvider fixed(String name, int maxConnections, long acquireTimeout) {
		return fixed(name, maxConnections, acquireTimeout, null, null);
	}

	/**
	 * Create a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}.
	 * <p>A Fixed {@link ConnectionProvider} will open up to the given max connection value.
	 * Further connections will be pending acquisition indefinitely.
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
	 */
	static ConnectionProvider fixed(String name, int maxConnections, long acquireTimeout, @Nullable Duration maxIdleTime, @Nullable Duration maxLifeTime) {
		return Builder.newInstance(name)
				.maxConnections(maxConnections)
				.acquireTimeout(acquireTimeout)
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
	 */
	default int maxConnections() {
		return -1;
	}

	final class Builder {
		String									name;
		PooledConnectionProvider.PoolFactory	poolFactory;
		long									acquireTimeout = ConnectionProvider.DEFAULT_POOL_ACQUIRE_TIMEOUT;
		int										maxConnections = ConnectionProvider.DEFAULT_POOL_MAX_CONNECTIONS;
		Duration								maxIdleTime;
		Duration								maxLifeTime;
		boolean									lifo;

		private Builder() {
		}

		/**
		 * Returns {@link Builder} new instance with name and default properties.
		 *
		 * @param name {@link ConnectionProvider} name
		 * @return {@link Builder}
		 */
		public static Builder newInstance(String name) {
			return new Builder().name(name);
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
		 * Set the options to use for configuring {@link ConnectionProvider} acquire timeout.
		 * Default to DEFAULT_POOL_ACQUIRE_TIMEOUT.
		 *
		 * @param acquireTimeout value must be a positive.
		 * @return {@literal this}
		 * @throws IllegalArgumentException if acquireTimeout is negative
		 */
		public final Builder acquireTimeout(long acquireTimeout) {
			if (acquireTimeout < 0) {
				throw new IllegalArgumentException("Acquire Timeout value must be positive");
			}
			this.acquireTimeout = acquireTimeout;
			return this;
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} maximum connections.
		 * Default to DEFAULT_POOL_MAX_CONNECTIONS.
		 *
		 * @param maxConnections the count of connections
		 * @return {@literal this}
		 * @throws IllegalArgumentException if maxConnections is negative
		 */
		public final Builder maxConnections(int maxConnections) {
			if (maxConnections != MAX_CONNECTIONS_ELASTIC && maxConnections <= 0) {
				throw new IllegalArgumentException("Max Connections value must be strictly positive");
			}
			this.maxConnections = maxConnections;
			return this;
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} max idle time.
		 *
		 * @param maxIdleTime The timeout {@link Duration}
		 * @return {@literal this}
		 */
		public final Builder maxIdleTime(Duration maxIdleTime) {
			this.maxIdleTime = maxIdleTime;
			return this;
		}

		/**
		 * Set the options to use for configuring {@link ConnectionProvider} max life time.
		 *
		 * @param maxLifeTime The timeout {@link Duration}
		 * @return {@literal this}
		 */
		public final Builder maxLifeTime(Duration maxLifeTime) {
			this.maxLifeTime = maxLifeTime;
			return this;
		}

		/**
		 * Switch order for the channels in the pool to LIFO.
		 * <br>
		 * Default FIFO.
		 *
		 * @param lifo flag for switching order for the channel to LIFO
		 * @return {@literal this}
		 */
		public final Builder lifo(boolean lifo) {
			this.lifo = lifo;
			return this;
		}

		/**
		 * Builds new ConnectionProvider
		 *
		 * @return builds new ConnectionProvider
		 */
		public final ConnectionProvider build() {
			this.poolFactory = this::configureDefaultPoolFactory;
			return new PooledConnectionProvider(this);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			Builder builder = (Builder) o;
			return acquireTimeout == builder.acquireTimeout &&
					maxConnections == builder.maxConnections &&
					lifo == builder.lifo &&
					name.equals(builder.name) &&
					Objects.equals(poolFactory, builder.poolFactory) &&
					Objects.equals(maxIdleTime, builder.maxIdleTime) &&
					Objects.equals(maxLifeTime, builder.maxLifeTime);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, poolFactory, acquireTimeout, maxConnections, maxIdleTime, maxLifeTime, lifo);
		}

		private InstrumentedPool<PooledConnectionProvider.PooledConnection> configureDefaultPoolFactory(
				Publisher<PooledConnectionProvider.PooledConnection> allocator, Function<PooledConnectionProvider.PooledConnection,
				Publisher<Void>> destroyHandler,
				BiPredicate<PooledConnectionProvider.PooledConnection, PooledRefMetadata> evictionPredicate
		) {
			PoolBuilder<PooledConnectionProvider.PooledConnection, PoolConfig<PooledConnectionProvider.PooledConnection>> pb = PoolBuilder.from(allocator)
					.destroyHandler(destroyHandler)
					.evictionPredicate(evictionPredicate
							.or((poolable, meta) -> (maxIdleTime != null && meta.idleTime() >= maxIdleTime.toMillis())
									|| (maxLifeTime != null && meta.lifeTime() >= maxLifeTime.toMillis())));
			if (maxConnections != MAX_CONNECTIONS_ELASTIC) {
				pb = pb.sizeBetween(0, maxConnections).maxPendingAcquireUnbounded();
			}
			return lifo ? pb.lifo() : pb.fifo();
		}
	}
}
