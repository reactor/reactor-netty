/*
 * Copyright (c) 2017-2024 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.resources;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.logging.LoggingHandler;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.NetUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.scheduler.Scheduler;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableChannel;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.DefaultPooledConnectionProvider.PooledConnection;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpClientTests;
import reactor.netty.tcp.TcpResources;
import reactor.netty.tcp.TcpServer;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.ClientTransportConfig;
import reactor.netty.transport.NameResolverProvider;
import reactor.pool.AllocationStrategy;
import reactor.pool.InstrumentedPool;
import reactor.pool.PoolAcquirePendingLimitException;
import reactor.pool.PoolConfig;
import reactor.pool.PoolMetricsRecorder;
import reactor.pool.PooledRef;
import reactor.pool.PooledRefMetadata;
import reactor.scheduler.clock.SchedulerClock;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DefaultPooledConnectionProviderTest {

	private InstrumentedPool<PooledConnection> channelPool;

	@BeforeEach
	void before() {
		channelPool = new PoolImpl();
	}

	@Test
	void disposeLaterDefers() throws Exception {
		ConnectionProvider.Builder connectionProviderBuilder =
				ConnectionProvider.builder("disposeLaterDefers")
				                  .maxConnections(Integer.MAX_VALUE);
		DefaultPooledConnectionProvider poolResources = new DefaultPooledConnectionProvider(connectionProviderBuilder);
		//"register" our fake Pool
		poolResources.channelPools.put(
				new PooledConnectionProvider.PoolKey(
						InetSocketAddress.createUnresolved("localhost", 80), -1),
				channelPool);

		Mono<Void> disposer = poolResources.disposeLater();
		assertThat(((AtomicInteger) channelPool).get()).as("pool closed by disposeLater()").isEqualTo(0);

		CountDownLatch latch = new CountDownLatch(1);
		disposer.subscribe(null, null, latch::countDown);

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(((AtomicInteger) channelPool).get()).as("pool closed by disposer subscribe()").isEqualTo(1);
	}

	@Test
	void disposeOnlyOnce() throws Exception {
		ConnectionProvider.Builder connectionProviderBuilder =
				ConnectionProvider.builder("disposeOnlyOnce")
				                  .maxConnections(Integer.MAX_VALUE);
		DefaultPooledConnectionProvider poolResources = new DefaultPooledConnectionProvider(connectionProviderBuilder);
		//"register" our fake Pool
		poolResources.channelPools.put(
				new PooledConnectionProvider.PoolKey(
						InetSocketAddress.createUnresolved("localhost", 80), -1),
				channelPool);

		CountDownLatch latch1 = new CountDownLatch(1);
		poolResources.disposeLater().subscribe(null, null, latch1::countDown);

		assertThat(latch1.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(((AtomicInteger) channelPool).get()).as("pool closed by disposeLater()").isEqualTo(1);

		CountDownLatch latch2 = new CountDownLatch(2);
		poolResources.disposeLater().subscribe(null, null, latch2::countDown);
		poolResources.disposeLater().subscribe(null, null, latch2::countDown);

		assertThat(latch2.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		assertThat(((AtomicInteger) channelPool).get()).as("pool closed only once").isEqualTo(1);
	}

	@Test
	void fixedPoolTwoAcquire() throws Exception {
		final ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
		int echoServerPort = SocketUtils.findAvailableTcpPort();
		TcpClientTests.EchoServer echoServer = new TcpClientTests.EchoServer(echoServerPort);
		EventLoopGroup group = new NioEventLoopGroup(2);

		java.util.concurrent.Future<?> f1 = null;
		java.util.concurrent.Future<?> f2 = null;
		Future<?> sf = null;
		try (AddressResolverGroup<?> resolver =
				NameResolverProvider.builder().build().newNameResolverGroup(TcpResources.get(), true)) {
			final InetSocketAddress address = InetSocketAddress.createUnresolved("localhost", echoServerPort);
			ConnectionProvider pool = ConnectionProvider.create("fixedPoolTwoAcquire", 2);

			Supplier<? extends SocketAddress> remoteAddress = () -> address;
			ConnectionObserver observer = ConnectionObserver.emptyListener();
			ClientTransportConfigImpl config =
					new ClientTransportConfigImpl(group, pool, Collections.emptyMap(), remoteAddress, resolver);

			//fail a couple
			StepVerifier.create(pool.acquire(config, observer, remoteAddress, config.resolverInternal()))
			            .verifyErrorMatches(msg -> msg.getCause() instanceof ConnectException);
			StepVerifier.create(pool.acquire(config, observer, remoteAddress, config.resolverInternal()))
			            .verifyErrorMatches(msg -> msg.getCause() instanceof ConnectException);

			//start the echo server
			f1 = service.submit(echoServer);
			Thread.sleep(100);

			//acquire 2
			final PooledConnection c1 = (PooledConnection) pool.acquire(config, observer, remoteAddress, config.resolverInternal())
			                                                   .block(Duration.ofSeconds(30));
			assertThat(c1).isNotNull();
			final PooledConnection c2 = (PooledConnection) pool.acquire(config, observer, remoteAddress, config.resolverInternal())
			                                                   .block(Duration.ofSeconds(30));
			assertThat(c2).isNotNull();

			//make room for 1 more
			c2.disposeNow();


			final PooledConnection c3 = (PooledConnection) pool.acquire(config, observer, remoteAddress, config.resolverInternal())
			                                                   .block(Duration.ofSeconds(30));
			assertThat(c3).isNotNull();

			//next one will block until a previous one is released
			long start = System.currentTimeMillis();
			sf = service.schedule(() -> c1.onStateChange(c1, ConnectionObserver.State.DISCONNECTING), 500, TimeUnit.MILLISECONDS);


			final PooledConnection c4 = (PooledConnection) pool.acquire(config, observer, remoteAddress, config.resolverInternal())
			                                                   .block(Duration.ofSeconds(30));
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

			InstrumentedPool<PooledConnection> defaultPool = c1.pool;

			CountDownLatch latch = new CountDownLatch(1);
			f2 = service.submit(() -> {
				while (defaultPool.metrics().acquiredSize() > 0) {
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
			group.shutdownGracefully()
			     .get(5, TimeUnit.SECONDS);

			assertThat(f1).isNotNull();
			assertThat(f1.get()).isNull();
			assertThat(f2).isNotNull();
			assertThat(f2.get()).isNull();
			assertThat(sf).isNotNull();
			assertThat(sf.get()).isNull();
		}
	}

	@Test
	void testIssue673_TimeoutException() throws InterruptedException {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .handle((in, out) -> out.sendString(Mono.just("test")
				                                                 .delayElement(Duration.ofMillis(100))))
				         .wiretap(true)
				         .bindNow();
		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.builder("testIssue673_TimeoutException")
				                                             .maxConnections(1)
				                                             .pendingAcquireMaxCount(4)
				                                             .pendingAcquireTimeout(Duration.ofMillis(10))
				                                             .build();
		CountDownLatch latch = new CountDownLatch(2);

		try {
			AtomicReference<InstrumentedPool<PooledConnection>> pool = new AtomicReference<>();
			List<? extends Signal<? extends Connection>> list =
					Flux.range(0, 5)
					    .flatMapDelayError(i ->
					        TcpClient.create(provider)
					                 .port(server.port())
					                 .doOnConnected(conn -> {
					                     ConcurrentMap<PooledConnectionProvider.PoolKey, InstrumentedPool<PooledConnection>> pools =
					                         provider.channelPools;
					                     pool.set(pools.get(pools.keySet().toArray()[0]));
					                 })
					                 .doOnDisconnected(conn -> latch.countDown())
					                 .handle((in, out) -> in.receive().then())
					                 .wiretap(true)
					                 .connect()
					                 .materialize(),
					    256, 32)
					    .collectList()
					    .doFinally(fin -> latch.countDown())
					    .block(Duration.ofSeconds(30));

			assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch 30s").isTrue();

			assertThat(list).isNotNull()
					.hasSize(5);

			int onNext = 0;
			int onError = 0;
			String msg = "Pool#acquire(Duration) has been pending for more than the configured timeout of 10ms";
			for (int i = 0; i < 5; i++) {
				Signal<? extends Connection> signal = list.get(i);
				if (signal.isOnNext()) {
					onNext++;
				}
				else if (signal.getThrowable() instanceof TimeoutException &&
								 msg.equals(signal.getThrowable().getMessage())) {
					onError++;
				}
			}
			assertThat(onNext).isEqualTo(1);
			assertThat(onError).isEqualTo(4);

			assertThat(pool.get().metrics().acquiredSize()).as("currently acquired").isEqualTo(0);
			assertThat(pool.get().metrics().idleSize()).as("currently idle").isEqualTo(0);
		}
		finally {
			server.disposeNow();
			provider.dispose();
		}
	}

	@Test
	void testIssue951_MaxPendingAcquire() throws InterruptedException {
		DisposableServer server =
				TcpServer.create()
				         .port(0)
				         .handle((in, out) -> out.sendString(Mono.just("test")
				                                                 .delayElement(Duration.ofMillis(100))))
				         .wiretap(true)
				         .bindNow();
		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.builder("testIssue951_MaxPendingAcquire")
				                                             .maxConnections(1)
				                                             .pendingAcquireTimeout(Duration.ofMillis(30))
				                                             .pendingAcquireMaxCount(1)
				                                             .build();
		CountDownLatch latch = new CountDownLatch(2);

		try {
			AtomicReference<InstrumentedPool<PooledConnection>> pool = new AtomicReference<>();
			List<? extends Signal<? extends Connection>> list =
					Flux.range(0, 3)
					    .flatMapDelayError(i ->
					        TcpClient.create(provider)
					                 .port(server.port())
					                 .doOnConnected(conn -> {
					                     ConcurrentMap<PooledConnectionProvider.PoolKey, InstrumentedPool<PooledConnection>> pools =
					                         provider.channelPools;
					                     pool.set(pools.get(pools.keySet().toArray()[0]));
					                 })
					                 .doOnDisconnected(conn -> latch.countDown())
					                 .handle((in, out) -> in.receive().then())
					                 .wiretap(true)
					                 .connect()
					                 .materialize(),
					    256, 32)
					    .collectList()
					    .doFinally(fin -> latch.countDown())
					    .block(Duration.ofSeconds(30));

			assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch 30s").isTrue();

			assertThat(list).isNotNull()
					.hasSize(3);

			int onNext = 0;
			int onErrorTimeout = 0;
			int onErrorPendingAcquire = 0;
			String msg1 = "Pool#acquire(Duration) has been pending for more than the configured timeout of 30ms";
			String msg2 = "Pending acquire queue has reached its maximum size of 1";
			for (int i = 0; i < 3; i++) {
				Signal<? extends Connection> signal = list.get(i);
				if (signal.isOnNext()) {
					onNext++;
				}
				else if (signal.getThrowable() instanceof TimeoutException &&
								 msg1.equals(signal.getThrowable().getMessage())) {
					onErrorTimeout++;
				}
				else if (signal.getThrowable() instanceof PoolAcquirePendingLimitException &&
								 msg2.equals(signal.getThrowable().getMessage())) {
					onErrorPendingAcquire++;
				}
			}
			assertThat(onNext).isEqualTo(1);
			assertThat(onErrorTimeout).isEqualTo(1);
			assertThat(onErrorPendingAcquire).isEqualTo(1);

			assertThat(pool.get().metrics().acquiredSize()).as("currently acquired").isEqualTo(0);
			assertThat(pool.get().metrics().idleSize()).as("currently idle").isEqualTo(0);
		}
		finally {
			server.disposeNow();
			provider.dispose();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void testRetryConnect() throws Exception {
		EventLoopGroup group = new NioEventLoopGroup(1);
		InetSocketAddress address = AddressUtils.createUnresolved("localhost", 12122);

		AddressResolverGroup<SocketAddress> resolverGroup = Mockito.mock(AddressResolverGroup.class);
		AddressResolver<SocketAddress> resolver = Mockito.mock(AddressResolver.class);
		io.netty.util.concurrent.Future<List<SocketAddress>> resolveFuture =
				Mockito.mock(io.netty.util.concurrent.Future.class);
		List<SocketAddress> resolveAllResult = Arrays.asList(
				new InetSocketAddress(NetUtil.LOCALHOST4, 12122), // connection refused
				new InetSocketAddress(NetUtil.LOCALHOST6, 12122), // connection refused
				new InetSocketAddress("example.com", 80) // connection established
		);
		Mockito.when(resolverGroup.getResolver(group.next())).thenReturn(resolver);
		Mockito.when(resolver.isSupported(address)).thenReturn(true);
		Mockito.when(resolver.isResolved(address)).thenReturn(false);
		Mockito.when(resolver.resolveAll(address)).thenReturn(resolveFuture);
		Mockito.when(resolveFuture.isDone()).thenReturn(true);
		Mockito.when(resolveFuture.cause()).thenReturn(null);
		Mockito.when(resolveFuture.getNow()).thenReturn(resolveAllResult);

		ConnectionProvider pool = ConnectionProvider.create("testRetryConnect", 1);
		Supplier<? extends SocketAddress> remoteAddress = () -> address;
		ConnectionObserver observer = ConnectionObserver.emptyListener();
		ClientTransportConfigImpl config =
				new ClientTransportConfigImpl(group, pool, Collections.emptyMap(), remoteAddress, resolverGroup);
		Connection conn = null;
		try {
			conn = pool.acquire(config, observer, remoteAddress, config.resolverInternal())
			           .block(Duration.ofSeconds(5));
			assertThat(((InetSocketAddress) conn.address()).getHostString()).isEqualTo("example.com");
		}
		finally {
			if (conn != null) {
				conn.disposeNow(Duration.ofSeconds(5));
			}
			pool.disposeLater()
			    .block(Duration.ofSeconds(5));
			group.shutdownGracefully()
			     .get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void testDisposeInactivePoolsInBackground() throws Exception {
		EventLoopGroup group = new NioEventLoopGroup(1);
		InetSocketAddress address = AddressUtils.createUnresolved("example.com", 80);
		ConnectionProvider.Builder builder =
				ConnectionProvider.builder("testDisposeInactivePoolsInBackground")
				                  .maxConnections(1)
				                  .disposeInactivePoolsInBackground(Duration.ofMillis(100), Duration.ofSeconds(1));
		VirtualTimeScheduler vts = VirtualTimeScheduler.create();
		DefaultPooledConnectionProvider pool = new DefaultPooledConnectionProvider(builder, SchedulerClock.of(vts));
		Supplier<? extends SocketAddress> remoteAddress = () -> address;
		ConnectionObserver observer = ConnectionObserver.emptyListener();
		ClientTransportConfigImpl config = new ClientTransportConfigImpl(group, pool, Collections.emptyMap(),
				remoteAddress, DefaultAddressResolverGroup.INSTANCE);
		Connection conn = null;
		try {
			conn = pool.acquire(config, observer, remoteAddress, config.resolverInternal())
			           .block(Duration.ofSeconds(5));
			assertThat(((InetSocketAddress) conn.address()).getHostString()).isEqualTo("example.com");
		}
		finally {
			if (conn != null) {
				CountDownLatch latch = new CountDownLatch(1);
				conn.channel()
				    .close()
				    .addListener(f -> latch.countDown());
				assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
			}

			assertThat(pool.channelPools.size()).isEqualTo(1);

			vts.advanceTimeBy(Duration.ofSeconds(5));

			await().atMost(500, TimeUnit.MILLISECONDS)
			       .with()
			       .pollInterval(10, TimeUnit.MILLISECONDS)
			       .untilAsserted(() -> assertThat(pool.channelPools.size()).isEqualTo(0));

			pool.disposeLater()
			    .block(Duration.ofSeconds(5));
			group.shutdownGracefully()
			     .get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void testIssue1790FIFOPool() {
		doTestIssue1790(true);
	}

	@Test
	void testIssue1790LIFOPool() {
		doTestIssue1790(false);
	}

	private void doTestIssue1790(boolean fifoPool) {
		DefaultPooledConnectionProvider provider;
		if (fifoPool) {
			provider =
			        (DefaultPooledConnectionProvider) ConnectionProvider.builder("testIssue1790")
			                                                            .maxConnections(1)
			                                                            .fifo()
			                                                            .build();
		}
		else {
			provider =
			        (DefaultPooledConnectionProvider) ConnectionProvider.builder("testIssue1790")
			                                                            .maxConnections(1)
			                                                            .lifo()
			                                                            .build();
		}

		DisposableServer disposableServer =
				TcpServer.create()
				         .port(0)
				         .wiretap(true)
				         .bindNow();

		Connection connection = null;
		try {
			connection =
					TcpClient.create(provider)
					         .port(disposableServer.port())
					         .wiretap(true)
					         .connectNow();

			assertThat(provider.channelPools).hasSize(1);

			@SuppressWarnings({"unchecked", "rawtypes"})
			InstrumentedPool<DefaultPooledConnectionProvider.PooledConnection> channelPool =
					provider.channelPools.values().toArray(new InstrumentedPool[0])[0];
			assertThat(channelPool.metrics())
				.withFailMessage("Reactor-netty relies on Reactor-pool instrumented pool.metrics()" +
								" to be the pool instance itself, got <%s> and <%s>",
				channelPool.metrics(), channelPool)
				.isSameAs(channelPool);
		}
		finally {
			if (connection != null) {
				connection.disposeNow();
			}
			disposableServer.disposeNow();
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testIssue3316() throws ExecutionException, InterruptedException {
		DisposableServer disposableServer =
				TcpServer.create()
				         .port(0)
				         .handle((in, out) -> out.send(in.receive().retain()))
				         .bindNow();

		DefaultPooledConnectionProvider provider =
				(DefaultPooledConnectionProvider) ConnectionProvider.create("testIssue3316", 400);
		EventLoopGroup group = new NioEventLoopGroup();
		try {
			Flux.range(0, 400)
			    .flatMap(i ->
			        TcpClient.create(provider)
			                 .port(disposableServer.port())
			                 .runOn(group)
			                 .connect())
			    .doOnNext(DisposableChannel::dispose)
			    .blockLast(Duration.ofSeconds(5));

			assertThat(provider.channelPools.size()).isEqualTo(1);
		}
		finally {
			disposableServer.disposeNow();
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
			group.shutdownGracefully()
			     .get();
		}
	}

	static final class PoolImpl extends AtomicInteger implements InstrumentedPool<PooledConnection> {

		@Override
		public Mono<Integer> warmup() {
			return null;
		}

		@Override
		public Mono<PooledRef<PooledConnection>> acquire() {
			return Mono.empty();
		}

		@Override
		public Mono<PooledRef<PooledConnection>> acquire(Duration timeout) {
			return null;
		}

		@Override
		public PoolConfig<PooledConnection> config() {
			return new PoolConfigImpl();
		}

		@Override
		public void dispose() {
			incrementAndGet();
		}

		@Override
		public Mono<Void> disposeLater() {
			return Mono.fromRunnable(this::incrementAndGet);
		}

		@Override
		public PoolMetrics metrics() {
			return null;
		}
	}

	static final class PoolConfigImpl implements PoolConfig<PooledConnection> {

		@Override
		public Mono<PooledConnection> allocator() {
			return null;
		}

		@Override
		public AllocationStrategy allocationStrategy() {
			return null;
		}

		@Override
		public int maxPending() {
			return 0;
		}

		@Override
		public Function<PooledConnection, ? extends Publisher<Void>> releaseHandler() {
			return null;
		}

		@Override
		public Function<PooledConnection, ? extends Publisher<Void>> destroyHandler() {
			return null;
		}

		@Override
		public BiPredicate<PooledConnection, PooledRefMetadata> evictionPredicate() {
			return null;
		}

		@Override
		public Scheduler acquisitionScheduler() {
			return null;
		}

		@Override
		public PoolMetricsRecorder metricsRecorder() {
			return null;
		}

		@Override
		public Clock clock() {
			return null;
		}

		@Override
		public boolean reuseIdleResourcesInLruOrder() {
			return false;
		}
	}

	static final class ClientTransportConfigImpl extends ClientTransportConfig<ClientTransportConfigImpl> {

		final EventLoopGroup group;
		final AddressResolverGroup<?> resolver;

		ClientTransportConfigImpl(EventLoopGroup group, ConnectionProvider connectionProvider,
				Map<ChannelOption<?>, ?> options, Supplier<? extends SocketAddress> remoteAddress,
				AddressResolverGroup<?> resolver) {
			super(connectionProvider, options, remoteAddress);
			this.group = group;
			this.resolver = resolver;
		}

		@Override
		protected LoggingHandler defaultLoggingHandler() {
			return null;
		}

		@Override
		protected LoopResources defaultLoopResources() {
			return preferNative -> group;
		}

		@Override
		protected ChannelMetricsRecorder defaultMetricsRecorder() {
			return null;
		}

		@Override
		protected AddressResolverGroup<?> defaultAddressResolverGroup() {
			return resolver;
		}

		@Override
		protected EventLoopGroup eventLoopGroup() {
			return group;
		}

		@Override
		protected AddressResolverGroup<?> resolverInternal() {
			return super.resolverInternal();
		}
	}
}