/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import reactor.ipc.netty.options.ChannelResources;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Hold the default Http event loops
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public final class HttpResources {

	/**
	 * Return the global HTTP event loop selector
	 *
	 * @return the global HTTP event loop selector
	 */
	public static ChannelResources defaultHttpLoops() {
		return DEFAULT_HTTP_LOOPS;
	}

	/**
	 * Return the global HTTP client pool selector
	 *
	 * @return the global HTTP client pool selector
	 */
	public static BiFunction<? super InetSocketAddress, Supplier<? extends Bootstrap>, ? extends ChannelPool> defaultPool() {
		return DEFAULT_POOL_SELECTOR;
	}

	static final ConcurrentMap<InetSocketAddress, ChannelPool> channelPools;
	static final ChannelResources                              DEFAULT_HTTP_LOOPS;
	static final BiFunction<? super InetSocketAddress, Supplier<? extends Bootstrap>, ? extends ChannelPool>
	                                                           DEFAULT_POOL_SELECTOR;

	static {
		DEFAULT_POOL_SELECTOR = HttpResources::pool;
		channelPools = new ConcurrentHashMap<>(8);
		DEFAULT_HTTP_LOOPS = ChannelResources.create("http");
	}

	static ChannelPool pool(InetSocketAddress remote,
			Supplier<? extends Bootstrap> bootstrap) {
		for (; ; ) {
			ChannelPool pool = channelPools.get(remote);
			if (pool != null) {
				return pool;
			}
			if (log.isDebugEnabled()) {
				log.debug("new http client pool for {}", remote);
			}
			//pool = new SimpleChannelPool(bootstrap);
			Bootstrap b = bootstrap.get();
			b.remoteAddress(remote);
			pool = new FixedChannelPool(b,
					new ChannelPoolHandler() {
						@Override
						public void channelReleased(Channel ch) throws Exception {
							log.debug("released {}", ch.toString());
						}

						@Override
						public void channelAcquired(Channel ch) throws Exception {
							log.debug("acquired {}", ch.toString());
						}

						@Override
						public void channelCreated(Channel ch) throws Exception {
							log.debug("created {}", ch.toString());
						}
					},1);
			if (channelPools.putIfAbsent(remote, pool) == null) {
				return pool;
			}
			pool.close();
		}
	}

	static final Logger log = Loggers.getLogger(HttpResources.class);

	HttpResources() {
	}
}
