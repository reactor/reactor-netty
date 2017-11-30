/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.resources;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.pool.SimpleChannelPool;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * A {@link io.netty.channel.pool.ChannelPool} selector with associated factories.
 *
 * @author Stephane Maldini
 * @since 0.6
 */
@FunctionalInterface
public interface PoolResources extends Disposable {

	/**
	 * Default max connection, if -1 will never wait to acquire before opening new
	 * connection in an unbounded fashion. Fallback to
	 * available number of processors.
	 */
	int DEFAULT_POOL_MAX_CONNECTION =
			Integer.parseInt(System.getProperty("reactor.ipc.netty.pool.maxConnections",
			"" + Math.max(Runtime.getRuntime()
			            .availableProcessors(), 8) * 2));

	/**
	 * Default acquisition timeout before error. If -1 will never wait to
	 * acquire before opening new
	 * connection in an unbounded fashion. Fallback to
	 * available number of processors.
	 */
	long DEFAULT_POOL_ACQUIRE_TIMEOUT = Long.parseLong(System.getProperty(
			"reactor.ipc.netty.pool.acquireTimeout",
			"" + 45000));

	/**
	 * Create an uncapped {@link PoolResources} to provide automatically for {@link
	 * ChannelPool}.
	 * <p>An elastic {@link PoolResources} will never wait before opening a new
	 * connection. The reuse window is limited but it cannot starve an undetermined volume
	 * of clients using it.
	 *
	 * @param name the channel pool map name
	 *
	 * @return a new {@link PoolResources} to provide automatically for {@link
	 * ChannelPool}
	 */
	static PoolResources elastic(String name) {
		return new DefaultPoolResources(name, SimpleChannelPool::new);
	}

	/**
	 * Create a capped {@link PoolResources} to provide automatically for {@link
	 * ChannelPool}.
	 * <p>A Fixed {@link PoolResources} will open up to the given max number of
	 * processors observed by this jvm (minimum 4).
	 * Further connections will be pending acquisition indefinitely.
	 *
	 * @param name the channel pool map name
	 *
	 * @return a new {@link PoolResources} to provide automatically for {@link
	 * ChannelPool}
	 */
	static PoolResources fixed(String name) {
		return fixed(name, DEFAULT_POOL_MAX_CONNECTION);
	}

	/**
	 * Create a capped {@link PoolResources} to provide automatically for {@link
	 * ChannelPool}.
	 * <p>A Fixed {@link PoolResources} will open up to the given max connection value.
	 * Further connections will be pending acquisition indefinitely.
	 *
	 * @param name the channel pool map name
	 * @param maxConnections the maximum number of connections before starting pending
	 * acquisition on existing ones
	 *
	 * @return a new {@link PoolResources} to provide automatically for {@link
	 * ChannelPool}
	 */
	static PoolResources fixed(String name, int maxConnections) {
		return fixed(name, maxConnections, DEFAULT_POOL_ACQUIRE_TIMEOUT);
	}

	/**
	 * Create a capped {@link PoolResources} to provide automatically for {@link
	 * ChannelPool}.
	 * <p>A Fixed {@link PoolResources} will open up to the given max connection value.
	 * Further connections will be pending acquisition indefinitely.
	 *
	 * @param name the channel pool map name
	 * @param maxConnections the maximum number of connections before starting pending
	 * @param acquireTimeout the maximum time in millis to wait for aquiring
	 *
	 * @return a new {@link PoolResources} to provide automatically for {@link
	 * ChannelPool}
	 */
	static PoolResources fixed(String name, int maxConnections, long acquireTimeout) {
		if (maxConnections == -1) {
			return elastic(name);
		}
		if (maxConnections <= 0) {
			throw new IllegalArgumentException("Max Connections value must be strictly " + "positive");
		}
		if (acquireTimeout != -1L && acquireTimeout < 0) {
			throw new IllegalArgumentException("Acquire Timeout value must " + "be " + "positive");
		}
		return new DefaultPoolResources(name,
				(bootstrap, handler, checker) -> new FixedChannelPool(bootstrap,
						handler,
						checker,
						FixedChannelPool.AcquireTimeoutAction.FAIL,
						acquireTimeout,
						maxConnections,
						Integer.MAX_VALUE
						));
	}

	/**
	 * Return an existing or new {@link ChannelPool}. The implementation will take care
	 * of pulling {@link Bootstrap} lazily when a {@link ChannelPool} creation is actually
	 * needed.
	 *
	 * @param bootstrap the {@link Bootstrap} if a {@link ChannelPool} must be
	 * created
	 * @return an existing or new {@link ChannelPool}
	 */
	ChannelPool selectOrCreate(Bootstrap bootstrap);

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
}
