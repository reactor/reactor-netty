/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException;
import reactor.netty.internal.shaded.reactor.pool.PoolBuilder;
import reactor.netty.internal.shaded.reactor.pool.PoolConfig;
import reactor.netty.internal.shaded.reactor.pool.PoolMetricsRecorder;
import reactor.netty.internal.shaded.reactor.pool.PooledRef;
import reactor.test.StepVerifier;
import reactor.util.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class Http2PoolTest {

	@Test
	void acquireInvalidate() {
		EmbeddedChannel channel = new EmbeddedChannel(Http2FrameCodecBuilder.forClient().build(),
				new Http2MultiplexHandler(new ChannelHandlerAdapter() {}));
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();
			http2Pool.acquire().subscribe(acquired::add);
			http2Pool.acquire().subscribe(acquired::add);
			http2Pool.acquire().subscribe(acquired::add);

			channel.runPendingTasks();

			assertThat(acquired).hasSize(3);
			assertThat(http2Pool.activeStreams()).isEqualTo(3);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			for (PooledRef<Connection> slot : acquired) {
				slot.invalidate().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			for (PooledRef<Connection> slot : acquired) {
				// second invalidate() should be ignored and ACQUIRED size should remain the same
				slot.invalidate().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);
		}
		finally {
			channel.finishAndReleaseAll();
			Connection.from(channel).dispose();
		}
	}

	@Test
	void acquireRelease() {
		EmbeddedChannel channel = new EmbeddedChannel(Http2FrameCodecBuilder.forClient().build(),
				new Http2MultiplexHandler(new ChannelHandlerAdapter() {}));
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();
			http2Pool.acquire().subscribe(acquired::add);
			http2Pool.acquire().subscribe(acquired::add);
			http2Pool.acquire().subscribe(acquired::add);

			channel.runPendingTasks();

			assertThat(acquired).hasSize(3);
			assertThat(http2Pool.activeStreams()).isEqualTo(3);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			for (PooledRef<Connection> slot : acquired) {
				slot.release().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			for (PooledRef<Connection> slot : acquired) {
				// second release() should be ignored and ACQUIRED size should remain the same
				slot.release().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);
		}
		finally {
			channel.finishAndReleaseAll();
			Connection.from(channel).dispose();
		}
	}

	@Test
	void evictClosedConnection() throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection = acquired1.poolable();
			ChannelId id1 = connection.channel().id();
			CountDownLatch latch = new CountDownLatch(1);
			((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
			connection.onDispose(latch::countDown);
			connection.dispose();

			assertThat(latch.await(1, TimeUnit.SECONDS)).as("latch await").isTrue();

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			acquired1.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection = acquired2.poolable();
			ChannelId id2 = connection.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection != null) {
				((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
				connection.dispose();
			}
		}
	}

	@Test
	void evictClosedConnectionMaxConnectionsNotReached_1() throws Exception {
		evictClosedConnectionMaxConnectionsNotReached(false);
	}

	@Test
	void evictClosedConnectionMaxConnectionsNotReached_2() throws Exception {
		evictClosedConnectionMaxConnectionsNotReached(true);
	}

	private void evictClosedConnectionMaxConnectionsNotReached(boolean closeSecond) throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build(),
				                   new Http2MultiplexHandler(new ChannelHandlerAdapter() {}));
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 2);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			connection = acquired1.poolable();
			ChannelId id1 = connection.channel().id();
			CountDownLatch latch = new CountDownLatch(1);
			((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
			connection.onDispose(latch::countDown);
			connection.dispose();

			assertThat(latch.await(1, TimeUnit.SECONDS)).as("latch await").isTrue();

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));
			assertThat(acquired2).isNotNull();

			AtomicReference<PooledRef<Connection>> acquired3 = new AtomicReference<>();
			http2Pool.acquire().subscribe(acquired3::set);

			connection = acquired2.poolable();
			((EmbeddedChannel) connection.channel()).runPendingTasks();

			assertThat(http2Pool.activeStreams()).isEqualTo(3);
			assertThat(http2Pool.connections.size()).isEqualTo(2);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(2L * Integer.MAX_VALUE);

			if (closeSecond) {
				latch = new CountDownLatch(1);
				((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
				connection.onDispose(latch::countDown);
				connection.dispose();

				assertThat(latch.await(1, TimeUnit.SECONDS)).as("latch await").isTrue();
			}

			ChannelId id2 = connection.channel().id();
			assertThat(id1).isNotEqualTo(id2);

			acquired1.invalidate().block(Duration.ofSeconds(1));
			acquired2.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			acquired3.get().invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			if (closeSecond) {
				assertThat(http2Pool.connections.size()).isEqualTo(0);
				assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
			}
			else {
				assertThat(http2Pool.connections.size()).isEqualTo(1);
				assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);
			}
		}
		finally {
			if (connection != null) {
				((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
				connection.dispose();
			}
		}
	}

	@Test
	void evictClosedConnectionMaxConnectionsReached() throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection = acquired1.poolable();
			CountDownLatch latch = new CountDownLatch(1);
			((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
			connection.onDispose(latch::countDown);
			connection.dispose();

			assertThat(latch.await(1, TimeUnit.SECONDS)).as("latch await").isTrue();

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			http2Pool.acquire(Duration.ofMillis(10))
			         .as(StepVerifier::create)
			         .expectError(PoolAcquireTimeoutException.class)
			         .verify(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			acquired1.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection != null) {
				((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
				connection.dispose();
			}
		}
	}

	@Test
	void evictInBackgroundClosedConnection() throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1)
				           .evictInBackground(Duration.ofSeconds(5));
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection = acquired1.poolable();
			ChannelId id1 = connection.channel().id();
			CountDownLatch latch = new CountDownLatch(1);
			((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
			connection.onDispose(latch::countDown);
			connection.dispose();

			assertThat(latch.await(1, TimeUnit.SECONDS)).as("latch await").isTrue();

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			acquired1.invalidate().block(Duration.ofSeconds(1));

			http2Pool.evictInBackground();

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection = acquired2.poolable();
			ChannelId id2 = connection.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block(Duration.ofSeconds(1));

			http2Pool.evictInBackground();

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection != null) {
				((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
				connection.dispose();
			}
		}
	}

	@Test
	void evictInBackgroundMaxIdleTime() throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1)
				           .evictInBackground(Duration.ofSeconds(5))
				           .evictionPredicate((conn, meta) -> meta.idleTime() >= 10);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection1 = acquired1.poolable();
			ChannelId id1 = connection1.channel().id();

			acquired1.invalidate().block(Duration.ofSeconds(1));

			Thread.sleep(15);

			http2Pool.evictInBackground();

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection2 = acquired2.poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block(Duration.ofSeconds(1));

			Thread.sleep(15);

			http2Pool.evictInBackground();

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection1 != null) {
				((EmbeddedChannel) connection1.channel()).finishAndReleaseAll();
				connection1.dispose();
			}
			if (connection2 != null) {
				((EmbeddedChannel) connection2.channel()).finishAndReleaseAll();
				connection2.dispose();
			}
		}
	}

	@Test
	void evictInBackgroundMaxLifeTime() throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1)
				           .evictInBackground(Duration.ofSeconds(5))
				           .evictionPredicate((conn, meta) -> meta.lifeTime() >= 10);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection1 = acquired1.poolable();
			ChannelId id1 = connection1.channel().id();

			Thread.sleep(10);

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			acquired1.invalidate().block(Duration.ofSeconds(1));

			http2Pool.evictInBackground();

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection2 = acquired2.poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block(Duration.ofSeconds(1));

			Thread.sleep(10);

			http2Pool.evictInBackground();

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection1 != null) {
				((EmbeddedChannel) connection1.channel()).finishAndReleaseAll();
				connection1.dispose();
			}
			if (connection2 != null) {
				((EmbeddedChannel) connection2.channel()).finishAndReleaseAll();
				connection2.dispose();
			}
		}
	}

	@Test
	void evictInBackgroundEvictionPredicate() {
		final AtomicBoolean shouldEvict = new AtomicBoolean(false);
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
							Channel channel = new EmbeddedChannel(
									new TestChannelId(),
									Http2FrameCodecBuilder.forClient().build());
							return Connection.from(channel);
						}))
						.idleResourceReuseLruOrder()
						.maxPendingAcquireUnbounded()
						.sizeBetween(0, 1)
						.evictInBackground(Duration.ofSeconds(5))
						.evictionPredicate((conn, metadata) -> shouldEvict.get());
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection1 = acquired1.poolable();
			ChannelId id1 = connection1.channel().id();

			shouldEvict.set(true);

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			acquired1.invalidate().block(Duration.ofSeconds(1));

			http2Pool.evictInBackground();

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			shouldEvict.set(false);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection2 = acquired2.poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block(Duration.ofSeconds(1));

			shouldEvict.set(true);

			http2Pool.evictInBackground();

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection1 != null) {
				((EmbeddedChannel) connection1.channel()).finishAndReleaseAll();
				connection1.dispose();
			}
			if (connection2 != null) {
				((EmbeddedChannel) connection2.channel()).finishAndReleaseAll();
				connection2.dispose();
			}
		}
	}

	@Test
	void maxIdleTime() throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1)
				           .evictionPredicate((conn, meta) -> meta.idleTime() >= 10);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection1 = acquired1.poolable();
			ChannelId id1 = connection1.channel().id();

			acquired1.invalidate().block(Duration.ofSeconds(1));

			Thread.sleep(15);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection2 = acquired2.poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection1 != null) {
				((EmbeddedChannel) connection1.channel()).finishAndReleaseAll();
				connection1.dispose();
			}
			if (connection2 != null) {
				((EmbeddedChannel) connection2.channel()).finishAndReleaseAll();
				connection2.dispose();
			}
		}
	}

	@Test
	void maxIdleTimeActiveStreams() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel(new TestChannelId(),
				Http2FrameCodecBuilder.forClient().build(), new Http2MultiplexHandler(new ChannelHandlerAdapter() {}));
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1)
				           .evictionPredicate((conn, meta) -> meta.idleTime() >= 10);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();
			http2Pool.acquire().subscribe(acquired::add);
			http2Pool.acquire().subscribe(acquired::add);

			channel.runPendingTasks();

			assertThat(acquired).hasSize(2);
			assertThat(http2Pool.activeStreams()).isEqualTo(2);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			connection1 = acquired.get(0).poolable();
			ChannelId id1 = connection1.channel().id();

			acquired.get(0).invalidate().block(Duration.ofSeconds(1));

			Thread.sleep(15);

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			connection2 = acquired.get(1).poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isEqualTo(id2);

			acquired.get(1).invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);
		}
		finally {
			if (connection1 != null) {
				((EmbeddedChannel) connection1.channel()).finishAndReleaseAll();
				connection1.dispose();
			}
			if (connection2 != null) {
				((EmbeddedChannel) connection2.channel()).finishAndReleaseAll();
				connection2.dispose();
			}
		}
	}

	@Test
	void maxLifeTime() throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1)
				           .evictionPredicate((conn, meta) -> meta.lifeTime() >= 10);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection1 = acquired1.poolable();
			ChannelId id1 = connection1.channel().id();

			Thread.sleep(10);

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			acquired1.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection2 = acquired2.poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection1 != null) {
				((EmbeddedChannel) connection1.channel()).finishAndReleaseAll();
				connection1.dispose();
			}
			if (connection2 != null) {
				((EmbeddedChannel) connection2.channel()).finishAndReleaseAll();
				connection2.dispose();
			}
		}
	}

	@Test
	void evictionPredicate() {
		final AtomicBoolean shouldEvict = new AtomicBoolean(false);
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
							Channel channel = new EmbeddedChannel(
									new TestChannelId(),
									Http2FrameCodecBuilder.forClient().build());
							return Connection.from(channel);
						}))
						.idleResourceReuseLruOrder()
						.maxPendingAcquireUnbounded()
						.sizeBetween(0, 1)
						.evictionPredicate((conn, metadata) -> shouldEvict.get());
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection1 = acquired1.poolable();
			ChannelId id1 = connection1.channel().id();

			shouldEvict.set(true);

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			acquired1.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			shouldEvict.set(false);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection2 = acquired2.poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection1 != null) {
				((EmbeddedChannel) connection1.channel()).finishAndReleaseAll();
				connection1.dispose();
			}
			if (connection2 != null) {
				((EmbeddedChannel) connection2.channel()).finishAndReleaseAll();
				connection2.dispose();
			}
		}
	}

	@Test
	void maxLifeTimeMaxConnectionsNotReached() throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 2)
				           .evictionPredicate((conn, meta) -> meta.lifeTime() >= 50);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection1 = acquired1.poolable();
			ChannelId id1 = connection1.channel().id();

			Thread.sleep(50);

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(2);
			assertThat(http2Pool.connections.size()).isEqualTo(2);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection2 = acquired2.poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired1.invalidate().block(Duration.ofSeconds(1));
			acquired2.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection1 != null) {
				((EmbeddedChannel) connection1.channel()).finishAndReleaseAll();
				connection1.dispose();
			}
			if (connection2 != null) {
				((EmbeddedChannel) connection2.channel()).finishAndReleaseAll();
				connection2.dispose();
			}
		}
	}

	@Test
	void maxLifeTimeMaxConnectionsReached() throws Exception {
		doMaxLifeTimeMaxConnectionsReached(null);
	}

	@Test
	void maxLifeTimeMaxConnectionsReachedWithCustomTimer() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		BiFunction<Runnable, Duration, Disposable> timer = (r, d) -> {
			Runnable wrapped = () -> {
				r.run();
				latch.countDown();
			};
			return Schedulers.single().schedule(wrapped, d.toNanos(), TimeUnit.NANOSECONDS);
		};
		doMaxLifeTimeMaxConnectionsReached(timer);
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	private void doMaxLifeTimeMaxConnectionsReached(@Nullable BiFunction<Runnable, Duration, Disposable> pendingAcquireTimer)
			throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1)
				           .evictionPredicate((conn, meta) -> meta.lifeTime() >= 10);
		if (pendingAcquireTimer != null) {
			poolBuilder = poolBuilder.pendingAcquireTimer(pendingAcquireTimer);
		}
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		Connection connection = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			connection = acquired1.poolable();

			Thread.sleep(10);

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			http2Pool.acquire(Duration.ofMillis(10))
			         .as(StepVerifier::create)
			         .expectError(PoolAcquireTimeoutException.class)
			         .verify(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			acquired1.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			if (connection != null) {
				((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
				connection.dispose();
			}
		}
	}

	@Test
	void minConnections() {
		EmbeddedChannel channel = new EmbeddedChannel(new TestChannelId(),
				Http2FrameCodecBuilder.forClient().build(), new Http2MultiplexHandler(new ChannelHandlerAdapter() {}));
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(1, 3);
		Http2AllocationStrategy strategy = Http2AllocationStrategy.builder()
				.maxConnections(3)
				.minConnections(1)
				.build();
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, strategy));

		List<PooledRef<Connection>> acquired = new ArrayList<>();
		try {
			Flux.range(0, 3)
			    .flatMap(i -> http2Pool.acquire().doOnNext(acquired::add))
			    .subscribe();

			channel.runPendingTasks();

			assertThat(acquired).hasSize(3);

			assertThat(http2Pool.activeStreams()).isEqualTo(3);
			assertThat(acquired.get(0).poolable()).isSameAs(acquired.get(1).poolable());
			assertThat(acquired.get(0).poolable()).isSameAs(acquired.get(2).poolable());
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);

			for (PooledRef<Connection> slot : acquired) {
				slot.release().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(Integer.MAX_VALUE);
		}
		finally {
			for (PooledRef<Connection> slot : acquired) {
				Connection conn = slot.poolable();
				((EmbeddedChannel) conn.channel()).finishAndReleaseAll();
				conn.dispose();
			}
		}
	}

	@Test
	void minConnectionsMaxStreamsReached() {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(1, 3);
		Http2AllocationStrategy strategy = Http2AllocationStrategy.builder()
				.maxConnections(3)
				.minConnections(1)
				.build();
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, strategy));

		List<PooledRef<Connection>> acquired = new ArrayList<>();
		try {
			Flux.range(0, 3)
			    .flatMap(i -> http2Pool.acquire().doOnNext(acquired::add))
			    .blockLast(Duration.ofSeconds(1));

			assertThat(acquired).hasSize(3);

			for (PooledRef<Connection> pooledRef : acquired) {
				((EmbeddedChannel) pooledRef.poolable().channel()).runPendingTasks();
			}

			assertThat(http2Pool.activeStreams()).isEqualTo(3);
			assertThat(acquired.get(0).poolable()).isNotSameAs(acquired.get(1).poolable());
			assertThat(acquired.get(0).poolable()).isNotSameAs(acquired.get(2).poolable());
			assertThat(acquired.get(1).poolable()).isNotSameAs(acquired.get(2).poolable());
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			for (PooledRef<Connection> slot : acquired) {
				slot.release().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			for (PooledRef<Connection> slot : acquired) {
				Connection conn = slot.poolable();
				((EmbeddedChannel) conn.channel()).finishAndReleaseAll();
				conn.dispose();
			}
		}
	}

	@Test
	void nonHttp2ConnectionEmittedOnce() {
		EmbeddedChannel channel = new EmbeddedChannel();
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		try {
			PooledRef<Connection> acquired = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired).isNotNull();
			assertThat(http2Pool.activeStreams()).isEqualTo(1);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);

			http2Pool.acquire(Duration.ofMillis(10))
			         .as(StepVerifier::create)
			         .expectError(PoolAcquireTimeoutException.class)
			         .verify(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(1);

			acquired.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.activeStreams()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
			assertThat(http2Pool.totalMaxConcurrentStreams).isEqualTo(0);
		}
		finally {
			channel.finishAndReleaseAll();
			Connection.from(channel).dispose();
		}
	}

	@Test
	void pendingTimeout() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel();
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .maxPendingAcquire(10)
				           .sizeBetween(0, 1);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, null));

		CountDownLatch latch = new CountDownLatch(3);
		ExecutorService executorService = Executors.newFixedThreadPool(20);
		try {
			CompletableFuture<?>[] completableFutures = new CompletableFuture<?>[4];
			for (int i = 0; i < completableFutures.length; i++) {
				completableFutures[i] = CompletableFuture.runAsync(
						() -> http2Pool.acquire(Duration.ofMillis(10))
								.doOnEach(sig -> channel.runPendingTasks())
								.doOnError(t -> latch.countDown())
								.onErrorResume(PoolAcquireTimeoutException.class, t -> Mono.empty())
								.block(),
						executorService);
			}

			CompletableFuture.allOf(completableFutures).join();
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			channel.finishAndReleaseAll();
			Connection.from(channel).dispose();
			executorService.shutdown();
		}
	}

	@Test
	void recordsPendingCountAndLatencies() {
		EmbeddedChannel channel = new EmbeddedChannel(new TestChannelId(),
				Http2FrameCodecBuilder.forClient().build(), new Http2MultiplexHandler(new ChannelHandlerAdapter() {}));
		TestPoolMetricsRecorder recorder = new TestPoolMetricsRecorder();
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .metricsRecorder(recorder)
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		Http2AllocationStrategy strategy = Http2AllocationStrategy.builder()
				.maxConnections(1)
				.maxConcurrentStreams(2)
				.build();
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, strategy));

		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();
			//success, acquisition happens immediately
			http2Pool.acquire(Duration.ofMillis(1)).subscribe(acquired::add);
			// success, acquisition happens immediately without timeout
			http2Pool.acquire().subscribe(acquired::add);

			channel.runPendingTasks();

			assertThat(acquired).hasSize(2);

			//success, acquisition happens after pending some time
			http2Pool.acquire(Duration.ofMillis(50)).subscribe();

			// success, acquisition happens after pending some time without timeout
			http2Pool.acquire().subscribe();

			//error, timed out
			http2Pool.acquire(Duration.ofMillis(1))
			         .as(StepVerifier::create)
			         .expectError(PoolAcquireTimeoutException.class)
			         .verify(Duration.ofSeconds(1));

			acquired.get(0).release().block(Duration.ofSeconds(1));
			acquired.get(1).release().block(Duration.ofSeconds(1));

			channel.runPendingTasks();

			assertThat(recorder.pendingSuccessCounter)
					.as("pending success")
					.isEqualTo(2);

			assertThat(recorder.pendingErrorCounter)
					.as("pending errors")
					.isEqualTo(1);

			assertThat(recorder.pendingSuccessLatency)
					.as("pending success latency")
					.isGreaterThanOrEqualTo(1L);

			assertThat(recorder.pendingErrorLatency)
					.as("pending error latency")
					.isGreaterThanOrEqualTo(1L);
		}
		finally {
			channel.finishAndReleaseAll();
			Connection.from(channel).dispose();
		}
	}

	static final class TestChannelId implements ChannelId {

		static final Random rndm = new Random();
		final String id;

		TestChannelId() {
			byte[] array = new byte[8];
			rndm.nextBytes(array);
			this.id = new String(array, StandardCharsets.UTF_8);
		}

		@Override
		public String asShortText() {
			return id;
		}

		@Override
		public String asLongText() {
			return id;
		}

		@Override
		public int compareTo(ChannelId o) {
			if (this == o) {
				return 0;
			}
			return this.asShortText().compareTo(o.asShortText());
		}
	}

	static final class TestPoolMetricsRecorder implements PoolMetricsRecorder {

		int pendingSuccessCounter;
		int pendingErrorCounter;
		long pendingSuccessLatency;
		long pendingErrorLatency;

		@Override
		public void recordAllocationSuccessAndLatency(long latencyMs) {
			//noop
		}

		@Override
		public void recordAllocationFailureAndLatency(long latencyMs) {
			//noop
		}

		@Override
		public void recordResetLatency(long latencyMs) {
			//noop
		}

		@Override
		public void recordDestroyLatency(long latencyMs) {
			//noop
		}

		@Override
		public void recordRecycled() {
			//noop
		}

		@Override
		public void recordLifetimeDuration(long millisecondsSinceAllocation) {
			//noop
		}

		@Override
		public void recordIdleTime(long millisecondsIdle) {
			//noop
		}

		@Override
		public void recordSlowPath() {
			//noop
		}

		@Override
		public void recordFastPath() {
			//noop
		}

		@Override
		public void recordPendingSuccessAndLatency(long latencyMs) {
			this.pendingSuccessCounter++;
			this.pendingSuccessLatency = latencyMs;
		}

		@Override
		public void recordPendingFailureAndLatency(long latencyMs) {
			this.pendingErrorCounter++;
			this.pendingErrorLatency = latencyMs;
		}
	}
}
