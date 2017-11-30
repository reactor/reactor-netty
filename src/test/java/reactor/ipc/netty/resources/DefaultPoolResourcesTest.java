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
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

public class DefaultPoolResourcesTest {

	private AtomicInteger closed;
	private ChannelPool channelPool;

	@Before
	public void before() {
		closed = new AtomicInteger();
		channelPool = new ChannelPool() {
			@Override
			public Future<Channel> acquire() {
				return null;
			}

			@Override
			public Future<Channel> acquire(Promise<Channel> promise) {
				return null;
			}

			@Override
			public Future<Void> release(Channel channel) {
				return null;
			}

			@Override
			public Future<Void> release(Channel channel, Promise<Void> promise) {
				return null;
			}

			@Override
			public void close() {
				closed.incrementAndGet();
			}
		};
	}

	@Test
	public void disposeLaterDefers() {
		DefaultPoolResources.Pool pool = new DefaultPoolResources.Pool(
				new Bootstrap(),
				(b, handler, checker) -> channelPool, new DefaultEventLoopGroup());

		DefaultPoolResources poolResources = new DefaultPoolResources("test",
				(b, handler, checker) -> channelPool);
		//"register" our fake Pool
		poolResources.channelPools.put(
				InetSocketAddress.createUnresolved("localhost", 80),
				pool);

		Mono<Void> disposer = poolResources.disposeLater();
		assertThat(closed.get()).as("pool closed by disposeLater()").isEqualTo(0);

		disposer.subscribe();
		assertThat(closed.get()).as("pool closed by disposer subscribe()").isEqualTo(1);
	}

	@Test
	public void disposeOnlyOnce() {
		DefaultPoolResources.Pool pool = new DefaultPoolResources.Pool(
				new Bootstrap(),
				(b, handler, checker) -> channelPool,
				new DefaultEventLoopGroup());

		DefaultPoolResources poolResources = new DefaultPoolResources("test",
				(b, handler, checker) -> channelPool);
		//"register" our fake Pool
		poolResources.channelPools.put(
				InetSocketAddress.createUnresolved("localhost", 80),
				pool);

		poolResources.dispose();
		assertThat(closed.get()).as("pool closed by dispose()").isEqualTo(1);

		Mono<Void> disposer = poolResources.disposeLater();
		disposer.subscribe();
		poolResources.disposeLater().subscribe();
		poolResources.dispose();

		assertThat(closed.get()).as("pool closed only once").isEqualTo(1);
	}

}