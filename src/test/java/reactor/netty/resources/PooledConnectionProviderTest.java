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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.resources.PooledConnectionProvider.PooledConnection;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpClientTests;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;

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
	public void disposeLaterDefers() throws Exception {
		PooledConnectionProvider.Pool pool = new PooledConnectionProvider.Pool(
				new Bootstrap().group(new DefaultEventLoopGroup()),
				(b, handler, checker) -> channelPool, ChannelOperations.OnSetup.empty());

		PooledConnectionProvider poolResources = new PooledConnectionProvider("test",
				(b, handler, checker) -> channelPool);
		//"register" our fake Pool
		poolResources.channelPools.put(
				new PooledConnectionProvider.PoolKey(
						InetSocketAddress.createUnresolved("localhost", 80), -1),
				pool);

		Mono<Void> disposer = poolResources.disposeLater();
		assertThat(closed.get()).as("pool closed by disposeLater()").isEqualTo(0);

		CountDownLatch latch = new CountDownLatch(1);
		disposer.subscribe(null, null, latch::countDown);

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(closed.get()).as("pool closed by disposer subscribe()").isEqualTo(1);
	}

	@Test
	public void disposeOnlyOnce() throws Exception {
		PooledConnectionProvider.Pool pool = new PooledConnectionProvider.Pool(
				new Bootstrap().group(new DefaultEventLoopGroup()),
				(b, handler, checker) -> channelPool, ChannelOperations.OnSetup.empty()
				);

		PooledConnectionProvider poolResources = new PooledConnectionProvider("test",
				(b, handler, checker) -> channelPool);
		//"register" our fake Pool
		poolResources.channelPools.put(
				new PooledConnectionProvider.PoolKey(
						InetSocketAddress.createUnresolved("localhost", 80), -1),
				pool);

		CountDownLatch latch1 = new CountDownLatch(1);
		poolResources.disposeLater().subscribe(null, null, latch1::countDown);

		assertThat(latch1.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(closed.get()).as("pool closed by disposeLater()").isEqualTo(1);

		CountDownLatch latch2 = new CountDownLatch(2);
		poolResources.disposeLater().subscribe(null, null, latch2::countDown);
		poolResources.disposeLater().subscribe(null, null, latch2::countDown);

		assertThat(latch2.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(closed.get()).as("pool closed only once").isEqualTo(1);
	}

	@Test
	public void fixedPoolTwoAcquire()
			throws ExecutionException, InterruptedException, IOException {
		final ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
		int echoServerPort = SocketUtils.findAvailableTcpPort();
		TcpClientTests.EchoServer echoServer = new TcpClientTests.EchoServer(echoServerPort);

		java.util.concurrent.Future<?> f1 = null;
		java.util.concurrent.Future<?> f2 = null;
		ScheduledFuture<?> sf = null;
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
			f1 = service.submit(echoServer);
			Thread.sleep(100);

			//acquire 2
			final PooledConnection c1 = (PooledConnection) pool.acquire(bootstrap)
			                                                   .block();
			assertThat(c1).isNotNull();
			final PooledConnection c2 = (PooledConnection) pool.acquire(bootstrap)
			                                                   .block();
			assertThat(c2).isNotNull();

			//make room for 1 more
			c2.disposeNow();


			final PooledConnection c3 = (PooledConnection) pool.acquire(bootstrap)
			                                                   .block();
			assertThat(c3).isNotNull();

			//next one will block until a previous one is released
			long start = System.currentTimeMillis();
			sf = service.schedule(() -> c1.onStateChange(c1, ConnectionObserver.State.DISCONNECTING), 500, TimeUnit
					.MILLISECONDS);


			final PooledConnection c4 = (PooledConnection) pool.acquire(bootstrap)
			                                                   .block();
			assertThat(c4).isNotNull();

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
			f2 = service.submit(() -> {
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
			assertThat(f1).isNotNull();
			assertThat(f1.get()).isNull();
			assertThat(f2).isNotNull();
			assertThat(f2.get()).isNull();
			assertNotNull(sf);
			assertThat(sf.get()).isNull();
		}
	}

	@Test
	public void testIssue673_TimeoutException() throws InterruptedException {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .handle((in, out) -> out.sendString(Mono.just("test")
				                                                 .delayElement(Duration.ofMillis(100))))
				         .wiretap(true)
				         .bindNow();

		CountDownLatch latch = new CountDownLatch(1);
		PooledConnectionProvider provider = (PooledConnectionProvider) ConnectionProvider.fixed("test", 1, 20);
		AtomicReference<PooledConnectionProvider.Pool> pool = new AtomicReference<>();
		Flux.range(0, 5)
		    .flatMap(i ->
		        TcpClient.create(provider)
		                 .port(server.port())
		                 .doOnConnected(conn -> {
		                     ConcurrentMap<PooledConnectionProvider.PoolKey, PooledConnectionProvider.Pool> pools = provider.channelPools;
		                     pool.set(pools.get(pools.keySet().toArray()[0]));
		                     conn.channel()
		                         .closeFuture()
		                         .addListener(future -> latch.countDown());
		                 })
		                 .handle((in, out) -> in.receive().then())
		                 .wiretap(true)
		                 .connect())
		    .subscribe(null, t -> assertThat(t instanceof TimeoutException).isTrue());

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		assertThat(pool.get().activeConnections.get()).isEqualTo(0);
		assertThat(pool.get().inactiveConnections.get()).isEqualTo(0);

		server.disposeNow();
		provider.dispose();
	}

	@Test
	public void testIssue673_IllegalStateException() throws InterruptedException {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .handle((in, out) -> out.sendString(Mono.just("test")))
				         .wiretap(true)
				         .bindNow();

		CountDownLatch latch = new CountDownLatch(2);
		PooledConnectionProvider provider = (PooledConnectionProvider) ConnectionProvider.fixed("test", 1);
		AtomicReference<PooledConnectionProvider.Pool> pool = new AtomicReference<>();
		Flux.range(0, 2)
		    .flatMap(i ->
		        TcpClient.create(provider)
		                 .port(server.port())
		                 .doOnConnected(conn -> {
		                     ConcurrentMap<PooledConnectionProvider.PoolKey, PooledConnectionProvider.Pool> pools = provider.channelPools;
		                     pool.set(pools.get(pools.keySet().toArray()[0]));
		                     provider.disposeLater()
		                             .subscribe(null, null, latch::countDown);
		                     conn.channel()
		                         .closeFuture()
		                         .addListener(future -> latch.countDown());
		                 })
		                 .handle((in, out) -> in.receive().then())
		                 .wiretap(true)
		                 .connect())
		    .subscribe(null, t -> assertThat(t instanceof IllegalStateException).isTrue());

		assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

		Thread.sleep(1000);
		assertThat(pool.get().activeConnections.get()).isEqualTo(0);
		assertThat(pool.get().inactiveConnections.get()).isEqualTo(0);

		server.disposeNow();
	}
}