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

package reactor.ipc.netty.tcp;

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
 * @author Stephane Maldini
 */
final class TcpResources {

	static final ChannelResources                              DEFAULT_TCP_LOOPS;
	static final ConcurrentMap<InetSocketAddress, ChannelPool> channelPools;
	static final BiFunction<? super InetSocketAddress, Supplier<? extends Bootstrap>, ? extends ChannelPool>
	                                                           DEFAULT_POOL;

	static {
		DEFAULT_TCP_LOOPS = ChannelResources.create("tcp");
		channelPools = new ConcurrentHashMap<>(8);
		DEFAULT_POOL = TcpResources::pool;
	}

	static ChannelPool pool(InetSocketAddress remote,
			Supplier<? extends Bootstrap> bootstrap) {
		for (; ; ) {
			ChannelPool pool = channelPools.get(remote);
			if (pool != null) {
				return pool;
			}
			if (log.isDebugEnabled()) {
				log.debug("New TCP client pool for {}", remote);
			}
			//pool = new SimpleChannelPool(bootstrap);
			pool = new FixedChannelPool(bootstrap.get(),
					new ChannelPoolHandler() {
						@Override
						public void channelReleased(Channel ch) throws Exception {
							log.debug("Released {}", ch.toString());
						}

						@Override
						public void channelAcquired(Channel ch) throws Exception {
							log.debug("Acquired {}", ch.toString());
						}

						@Override
						public void channelCreated(Channel ch) throws Exception {
							log.debug("Created {}", ch.toString());
						}
					},
					Runtime.getRuntime()
					       .availableProcessors());
			if (channelPools.putIfAbsent(remote, pool) == null) {
				return pool;
			}
			pool.close();
		}
	}

	static final Logger log = Loggers.getLogger(TcpResources.class);
}
