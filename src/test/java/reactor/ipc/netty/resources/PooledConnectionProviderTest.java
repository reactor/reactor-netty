/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.resources;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

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
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.SocketUtils;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.resources.PooledConnectionProvider.PooledConnection;
import reactor.ipc.netty.tcp.TcpClientTests;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

public class PooledConnectionProviderTest {

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
		PooledConnectionProvider.Pool pool = new PooledConnectionProvider.Pool(
				new Bootstrap().group(new DefaultEventLoopGroup()),
				(b, handler, checker) -> channelPool, ChannelOperations.OnSetup.empty());

		PooledConnectionProvider poolResources = new PooledConnectionProvider("test",
				(b, handler, checker) -> channelPool);
		//"register" our fake Pool
		poolResources.channelPools.put(
				new PooledConnectionProvider.PoolKey(
						InetSocketAddress.createUnresolved("localhost", 80), ChannelOperations.OnSetup.empty()),
				pool);

		Mono<Void> disposer = poolResources.disposeLater();
		assertThat(closed.get()).as("pool closed by disposeLater()").isEqualTo(0);

		disposer.subscribe();
		assertThat(closed.get()).as("pool closed by disposer subscribe()").isEqualTo(1);
	}

	@Test
	public void disposeOnlyOnce() {
		PooledConnectionProvider.Pool pool = new PooledConnectionProvider.Pool(
				new Bootstrap().group(new DefaultEventLoopGroup()),
				(b, handler, checker) -> channelPool, ChannelOperations.OnSetup.empty()
				);

		PooledConnectionProvider poolResources = new PooledConnectionProvider("test",
				(b, handler, checker) -> channelPool);
		//"register" our fake Pool
		poolResources.channelPools.put(
				new PooledConnectionProvider.PoolKey(
						InetSocketAddress.createUnresolved("localhost", 80), ChannelOperations.OnSetup.empty()),
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
			throws InterruptedException, IOException {
		final ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
		int echoServerPort = SocketUtils.findAvailableTcpPort();
		TcpClientTests.EchoServer echoServer = new TcpClientTests.EchoServer(echoServerPort);

		try {
			final InetSocketAddress address = InetSocketAddress.createUnresolved("localhost", echoServerPort);
			ConnectionProvider pool = ConnectionProvider.fixed("fixedPoolTwoAcquire",
					2);

			Bootstrap bootstrap = new Bootstrap().remoteAddress(address)
			                                     .channelFactory(NioSocketChannel::new)
			                                     .group(new NioEventLoopGroup(2));

			//fail a couple
			StepVerifier.create(pool.acquire(bootstrap))
			            .verifyErrorMatches(msg -> msg.getMessage().contains("Connection refused"));
			StepVerifier.create(pool.acquire(bootstrap))
			            .verifyErrorMatches(msg -> msg.getMessage().contains("Connection refused"));

			//start the echo server
			service.submit(echoServer);
			Thread.sleep(100);

			//acquire 2
			final PooledConnection c1 = (PooledConnection) pool.acquire(bootstrap)
			                                                   .block();
			final PooledConnection c2 = (PooledConnection) pool.acquire(bootstrap)
			                                                   .block();

			//make room for 1 more
			c2.disposeNow();


			final PooledConnection c3 = (PooledConnection) pool.acquire(bootstrap)
			                                                   .block();

			//next one will block until a previous one is released
			long start = System.currentTimeMillis();
			service.schedule(() -> c1.onStateChange(c1, ConnectionObserver.State.DISCONNECTING), 500, TimeUnit
					.MILLISECONDS);


			final PooledConnection c4 = (PooledConnection) pool.acquire(bootstrap)
			                                                   .block();

			long end = System.currentTimeMillis();

			assertThat(end - start)
					.as("channel4 acquire blocked until channel1 released")
					.isGreaterThanOrEqualTo(500);

			c3.onStateChange(c3, ConnectionObserver.State.DISCONNECTING);

			c4.onStateChange(c4, ConnectionObserver.State.DISCONNECTING);

			assertThat(c1).isEqualTo(c4);

			assertThat(c1.pool).isEqualTo(c2.pool)
			                   .isEqualTo(c3.pool)
			                   .isEqualTo(c4.pool);

			PooledConnectionProvider.Pool defaultPool = c1.pool;

			CountDownLatch latch = new CountDownLatch(1);
			service.submit(() -> {
				while(defaultPool.activeConnections.get() > 0) {
					LockSupport.parkNanos(100);
				}
				latch.countDown();
			});


			assertThat(latch.await(5, TimeUnit.SECONDS))
					.as("activeConnections fully released")
					.isTrue();
		}
		finally {
			service.shutdownNow();
			echoServer.close();
		}
	}

}