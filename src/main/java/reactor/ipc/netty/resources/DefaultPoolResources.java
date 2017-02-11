/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class DefaultPoolResources implements PoolResources {

	final ConcurrentMap<SocketAddress, Pool>                     channelPools;
	final String                                                 name;
	final BiFunction<Bootstrap, ChannelPoolHandler, ChannelPool> provider;

	DefaultPoolResources(String name,
			BiFunction<Bootstrap, ChannelPoolHandler, ChannelPool> provider) {
		this.name = name;
		this.provider = provider;
		this.channelPools = PlatformDependent.newConcurrentHashMap();
	}

	@Override
	public ChannelPool selectOrCreate(SocketAddress remote,
			Supplier<? extends Bootstrap> bootstrap,
			Consumer<? super Channel> onChannelCreate) {
		SocketAddress address = remote;
		for (; ; ) {
			Pool pool = channelPools.get(remote);
			if (pool != null) {
				return pool;
			}
			//pool = new SimpleChannelPool(bootstrap);
			Bootstrap b = bootstrap.get();
			if (remote != null) {
				b = b.remoteAddress(remote);
			}
			else {
				address = b.config()
				          .remoteAddress();
			}
			if (log.isDebugEnabled()) {
				log.debug("New {} client pool for {}", name, address);
			}
			pool = new Pool(b, provider, onChannelCreate);
			if (channelPools.putIfAbsent(address, pool) == null) {
				return pool;
			}
			pool.close();
		}
	}

	final static class Pool extends AtomicBoolean
			implements ChannelPoolHandler, ChannelPool {

		final ChannelPool               pool;
		final Consumer<? super Channel> onChannelCreate;

		int activeConnections;

		@SuppressWarnings("unchecked")
		Pool(Bootstrap bootstrap, BiFunction<Bootstrap, ChannelPoolHandler,
				ChannelPool> provider, Consumer<? super Channel> onChannelCreate) {
			this.pool = provider.apply(bootstrap, this);
			this.onChannelCreate = onChannelCreate;
		}

		@Override
		public Future<Channel> acquire() {
			return pool.acquire();
		}

		@Override
		public Future<Channel> acquire(Promise<Channel> promise) {
			return pool.acquire(promise);
		}

		@Override
		public Future<Void> release(Channel channel) {
			return pool.release(channel);
		}

		@Override
		public Future<Void> release(Channel channel, Promise<Void> promise) {
			return pool.release(channel, promise);
		}

		@Override
		public void close() {
			if(compareAndSet(false, true)) {
				pool.close();
			}
		}

		@Override
		public void channelReleased(Channel ch) throws Exception {
			activeConnections--;
			if (log.isDebugEnabled()) {
				log.debug("Released {}, now {} active connections",
						ch.toString(),
						activeConnections);
			}
		}

		@Override
		public void channelAcquired(Channel ch) throws Exception {
			activeConnections++;
			if (log.isDebugEnabled()) {
				log.debug("Acquired {}, now {} active connections",
						ch.toString(),
						activeConnections);
			}
		}

		@Override
		public void channelCreated(Channel ch) throws Exception {
			activeConnections++;
			if (log.isDebugEnabled()) {
				log.debug("Created {}, now {} active connections",
						ch.toString(),
						activeConnections);
			}
			if (onChannelCreate != null) {
				onChannelCreate.accept(ch);
			}
		}

		@Override
		public String toString() {
			return pool.getClass()
			           .getSimpleName() + "{" + "activeConnections=" + activeConnections + '}';
		}
	}

	@Override
	public void dispose() {
		Pool pool;
		for (SocketAddress key: channelPools.keySet()) {
			pool = channelPools.remove(key);
			if(pool != null){
				pool.close();
			}
		}
	}

	static final Logger log = Loggers.getLogger(DefaultPoolResources.class);

}
