/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.SocketUtils;
import reactor.ipc.netty.tcp.TcpClientTests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
				(b, handler, checker) -> channelPool,
				null, new DefaultEventLoopGroup());

		DefaultPoolResources poolResources = new DefaultPoolResources("test",
				(b, handler, checker) -> channelPool);
		//"register" our fake Pool
		poolResources.channelPools.put(
				new DefaultPoolResources.SocketAddressHolder(
						InetSocketAddress.createUnresolved("localhost", 80)),
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
				null, new DefaultEventLoopGroup());

		DefaultPoolResources poolResources = new DefaultPoolResources("test",
				(b, handler, checker) -> channelPool);
		//"register" our fake Pool
		poolResources.channelPools.put(
				new DefaultPoolResources.SocketAddressHolder(
						InetSocketAddress.createUnresolved("localhost", 80)),
				pool);

		poolResources.dispose();
		assertThat(closed.get()).as("pool closed by dispose()").isEqualTo(1);

		Mono<Void> disposer = poolResources.disposeLater();
		disposer.subscribe();
		poolResources.disposeLater().subscribe();
		poolResources.dispose();

		assertThat(closed.get()).as("pool closed only once").isEqualTo(1);
	}

	@Test
	public void fixedPoolTwoAcquire()
			throws ExecutionException, InterruptedException, IOException {
		final ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
		int echoServerPort = SocketUtils.findAvailableTcpPort();
		TcpClientTests.EchoServer echoServer = new TcpClientTests.EchoServer(echoServerPort);

		List<Channel> createdChannels = new ArrayList<>();
		java.util.concurrent.Future<?> f = null;
		ScheduledFuture<Future<Void>> sf = null;
		try {
			final InetSocketAddress address = InetSocketAddress.createUnresolved("localhost", echoServerPort);
			ChannelPool pool = PoolResources.fixed("fixedPoolTwoAcquire", 2)
			                                .selectOrCreate(address,
					                                () -> new Bootstrap()
							                                .remoteAddress(address)
							                                .channelFactory(NioSocketChannel::new)
							                                .group(new NioEventLoopGroup(2)),
					                                createdChannels::add,
					                                new NioEventLoopGroup(2));

			//fail a couple
			assertThatExceptionOfType(Throwable.class)
					.isThrownBy(pool.acquire()::get)
					.withMessageContaining("Connection refused");
			assertThatExceptionOfType(Throwable.class)
					.isThrownBy(pool.acquire()::get)
					.withMessageContaining("Connection refused");

			//start the echo server
			f = service.submit(echoServer);
			Thread.sleep(100);

			//acquire 2
			final Channel channel1 = pool.acquire().get();
			final Channel channel2 = pool.acquire().get();

			//make room for 1 more
			channel2.close().get();
			final Channel channel3 = pool.acquire().get();

			//next one will block until a previous one is released
			long start = System.currentTimeMillis();
			sf = service.schedule(() -> pool.release(channel1), 500, TimeUnit.MILLISECONDS);
			final Channel channel4 = pool.acquire().get();
			long end = System.currentTimeMillis();

			assertThat(end - start)
					.as("channel4 acquire blocked until channel1 released")
					.isGreaterThanOrEqualTo(500);

			pool.release(channel3).get();
			pool.release(channel4).get();

			assertThat(pool).isInstanceOf(DefaultPoolResources.Pool.class);
			DefaultPoolResources.Pool defaultPool = (DefaultPoolResources.Pool) pool;
			assertThat(defaultPool.activeConnections.get())
					.as("activeConnections fully released")
					.isZero();
		}
		finally {
			echoServer.close();
			assertThat(f).isNotNull();
			assertThat(f.get()).isNull();
			assertThat(sf).isNotNull();
			assertThat(sf.get().isSuccess()).isTrue();
		}
	}

}