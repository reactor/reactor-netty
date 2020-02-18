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

import java.net.SocketAddress;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.pool.SimpleChannelPool;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ReactorNetty;
import reactor.util.annotation.NonNull;

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
		return new PooledConnectionProvider(name,
				(bootstrap, handler, checker) -> new SimpleChannelPool(bootstrap,
						handler,
						checker,
						true,
						false));
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
	 * @param acquireTimeout the maximum time in millis to wait for acquiring
	 *
	 * @return a new {@link ConnectionProvider} to cache and reuse a fixed maximum
	 * number of {@link Connection}
	 */
	static ConnectionProvider fixed(String name, int maxConnections, long acquireTimeout) {
		if (maxConnections == -1) {
			return elastic(name);
		}
		if (maxConnections <= 0) {
			throw new IllegalArgumentException("Max Connections value must be strictly " + "positive");
		}
		if (acquireTimeout != -1L && acquireTimeout < 0) {
			throw new IllegalArgumentException("Acquire Timeout value must " + "be " + "positive");
		}
		return new PooledConnectionProvider(name,
				(bootstrap, handler, checker) -> new FixedChannelPool(bootstrap,
						handler,
						checker,
						FixedChannelPool.AcquireTimeoutAction.FAIL,
						acquireTimeout,
						maxConnections,
						Integer.MAX_VALUE,
						true,
						false),
				maxConnections);
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

	@Override
	default void dispose() {
		//noop default
		disposeLater().subscribe();
	}

	/**
	 * Returns a Mono that triggers the disposal of underlying resources when subscribed to.
	 *
	 * @return a Mono representing the completion of resources disposal.
	 **/
	default Mono<Void> disposeLater() {
		return Mono.empty(); //noop default
	}

	/**
	 * Returns the maximum number of connections before starting pending
	 *
	 * @return the maximum number of connections before starting pending
	 */
	default int maxConnections() {
		return -1;
	}
}
