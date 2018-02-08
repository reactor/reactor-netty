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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class DefaultPoolResources implements PoolResources {

	interface PoolFactory {

		ChannelPool newPool(Bootstrap b,
				ChannelPoolHandler handler,
				ChannelHealthChecker checker);
	}

	final ConcurrentMap<SocketAddressHolder, Pool> channelPools;
	final String                             name;
	final PoolFactory                        provider;

	DefaultPoolResources(String name, PoolFactory provider) {
		this.name = name;
		this.provider = provider;
		this.channelPools = PlatformDependent.newConcurrentHashMap();
	}

	@Override
	public ChannelPool selectOrCreate(SocketAddress remote,
			Supplier<? extends Bootstrap> bootstrap,
			Consumer<? super Channel> onChannelCreate,
			EventLoopGroup group) {
		SocketAddressHolder holder = new SocketAddressHolder(remote);
		for (; ; ) {
			Pool pool = channelPools.get(holder);
			if (pool != null) {
				return pool;
			}
			if (log.isDebugEnabled()) {
				log.debug("New {} client pool for {}", name, remote);
			}
			pool = new Pool(bootstrap.get().remoteAddress(remote), provider, onChannelCreate, group);
			if (channelPools.putIfAbsent(holder, pool) == null) {
				return pool;
			}
			pool.close();
		}
	}

	final static class Pool extends AtomicBoolean
			implements ChannelPoolHandler, ChannelPool, ChannelHealthChecker {

		final ChannelPool               pool;
		final Consumer<? super Channel> onChannelCreate;
		final EventLoopGroup            defaultGroup;

		final AtomicInteger activeConnections = new AtomicInteger();

		final Future<Boolean> HEALTHY;
		final Future<Boolean> UNHEALTHY;

		@SuppressWarnings("unchecked")
		Pool(Bootstrap bootstrap,
				PoolFactory provider,
				Consumer<? super Channel> onChannelCreate,
				EventLoopGroup group) {
			this.pool = provider.newPool(bootstrap, this, this);
			this.onChannelCreate = onChannelCreate;
			this.defaultGroup = group;
			HEALTHY = group.next()
			               .newSucceededFuture(true);
			UNHEALTHY = group.next()
			                 .newSucceededFuture(false);
		}

		@Override
		public Future<Boolean> isHealthy(Channel channel) {
			return channel.isActive() ? HEALTHY : UNHEALTHY;
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
			activeConnections.decrementAndGet();
			if (log.isDebugEnabled()) {
				log.debug("Released {}, now {} active connections",
						ch.toString(),
						activeConnections);
			}
		}

		@Override
		public void channelAcquired(Channel ch) throws Exception {
			activeConnections.incrementAndGet();
			if (log.isDebugEnabled()) {
				log.debug("Acquired {}, now {} active connections",
						ch.toString(),
						activeConnections);
			}
		}

		@Override
		public void channelCreated(Channel ch) throws Exception {
			activeConnections.incrementAndGet();
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
		disposeLater().subscribe();
	}

	@Override
	public Mono<Void> disposeLater() {
		return Mono.fromRunnable(() -> {
			Pool pool;
			for (SocketAddressHolder key: channelPools.keySet()) {
				pool = channelPools.remove(key);
				if(pool != null){
					pool.close();
				}
			}
		});
	}

	@Override
	public boolean isDisposed() {
		return channelPools.isEmpty() || channelPools.values()
		                                             .stream()
		                                             .allMatch(AtomicBoolean::get);
	}

	static final Logger log = Loggers.getLogger(DefaultPoolResources.class);


	final static class SocketAddressHolder {
		final SocketAddress holder;
		final String fqdn;

		SocketAddressHolder(SocketAddress holder) {
			this.holder = holder;
			this.fqdn = holder instanceof InetSocketAddress ? holder.toString() : null;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			SocketAddressHolder that = (SocketAddressHolder) o;

			return holder.equals(that.holder) &&
			       (fqdn != null ? fqdn.equals(that.fqdn) : that.fqdn == null);
		}

		@Override
		public int hashCode() {
			int result = holder.hashCode();
			result = 31 * result + (fqdn != null ? fqdn.hashCode() : 0);
			return result;
		}
	}
}
