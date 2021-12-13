/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.ChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException;
import reactor.netty.internal.shaded.reactor.pool.PoolBuilder;
import reactor.netty.internal.shaded.reactor.pool.PoolConfig;
import reactor.netty.internal.shaded.reactor.pool.PooledRef;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class Http2PoolTest {

	@Test
	void acquireInvalidate() {
		EmbeddedChannel channel = new EmbeddedChannel(Http2FrameCodecBuilder.forClient().build());
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		InstrumentedPool<Connection> http2Pool = poolBuilder.build(config -> new Http2Pool(config, -1));

		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();
			http2Pool.acquire().subscribe(acquired::add);
			http2Pool.acquire().subscribe(acquired::add);
			http2Pool.acquire().subscribe(acquired::add);

			assertThat(acquired).hasSize(3);
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(3);

			for (PooledRef<Connection> slot : acquired) {
				slot.invalidate().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);

			for (PooledRef<Connection> slot : acquired) {
				// second invalidate() should be ignored and ACQUIRED size should remain the same
				slot.invalidate().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
		}
		finally {
			channel.finishAndReleaseAll();
			Connection.from(channel).dispose();
		}
	}

	@Test
	void acquireRelease() {
		EmbeddedChannel channel = new EmbeddedChannel(Http2FrameCodecBuilder.forClient().build());
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		InstrumentedPool<Connection> http2Pool = poolBuilder.build(config -> new Http2Pool(config, -1));

		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();
			http2Pool.acquire().subscribe(acquired::add);
			http2Pool.acquire().subscribe(acquired::add);
			http2Pool.acquire().subscribe(acquired::add);

			assertThat(acquired).hasSize(3);
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(3);

			for (PooledRef<Connection> slot : acquired) {
				slot.release().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);

			for (PooledRef<Connection> slot : acquired) {
				// second release() should be ignored and ACQUIRED size should remain the same
				slot.release().block(Duration.ofSeconds(1));
			}

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
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
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, -1));

		Connection connection = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block();

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			connection = acquired1.poolable();
			ChannelId id1 = connection.channel().id();
			CountDownLatch latch = new CountDownLatch(1);
			((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
			connection.onDispose(latch::countDown);
			connection.dispose();

			assertThat(latch.await(1, TimeUnit.SECONDS)).as("latch await").isTrue();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			acquired1.invalidate().block();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block();

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			connection = acquired2.poolable();
			ChannelId id2 = connection.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
		}
		finally {
			if (connection != null) {
				((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
				connection.dispose();
			}
		}
	}

	@Test
	void evictClosedConnectionMaxConnectionsNotReached() throws Exception {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> {
				               Channel channel = new EmbeddedChannel(
				                   new TestChannelId(),
				                   Http2FrameCodecBuilder.forClient().build());
				               return Connection.from(channel);
				           }))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 2);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, -1));

		Connection connection = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block();

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			connection = acquired1.poolable();
			ChannelId id1 = connection.channel().id();
			CountDownLatch latch = new CountDownLatch(1);
			((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
			connection.onDispose(latch::countDown);
			connection.dispose();

			assertThat(latch.await(1, TimeUnit.SECONDS)).as("latch await").isTrue();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block();

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(2);
			assertThat(http2Pool.connections.size()).isEqualTo(2);

			connection = acquired2.poolable();
			ChannelId id2 = connection.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired1.invalidate().block();
			acquired2.invalidate().block();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
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
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, -1));

		Connection connection = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block();

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			connection = acquired1.poolable();
			CountDownLatch latch = new CountDownLatch(1);
			((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
			connection.onDispose(latch::countDown);
			connection.dispose();

			assertThat(latch.await(1, TimeUnit.SECONDS)).as("latch await").isTrue();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			http2Pool.acquire(Duration.ofMillis(10))
			         .as(StepVerifier::create)
			         .expectError(PoolAcquireTimeoutException.class)
			         .verify(Duration.ofSeconds(1));

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			acquired1.invalidate().block();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
		}
		finally {
			if (connection != null) {
				((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
				connection.dispose();
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
				           .sizeBetween(0, 1);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, 10));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block();

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			connection1 = acquired1.poolable();
			ChannelId id1 = connection1.channel().id();

			Thread.sleep(10);

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			acquired1.invalidate().block();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block();

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			connection2 = acquired2.poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired2.invalidate().block();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
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
				           .sizeBetween(0, 2);
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, 10));

		Connection connection1 = null;
		Connection connection2 = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block();

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			connection1 = acquired1.poolable();
			ChannelId id1 = connection1.channel().id();

			Thread.sleep(10);

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			PooledRef<Connection> acquired2 = http2Pool.acquire().block();

			assertThat(acquired2).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(2);
			assertThat(http2Pool.connections.size()).isEqualTo(2);

			connection2 = acquired2.poolable();
			ChannelId id2 = connection2.channel().id();

			assertThat(id1).isNotEqualTo(id2);

			acquired1.invalidate().block();
			acquired2.invalidate().block();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
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
		Http2Pool http2Pool = poolBuilder.build(config -> new Http2Pool(config, 10));

		Connection connection = null;
		try {
			PooledRef<Connection> acquired1 = http2Pool.acquire().block();

			assertThat(acquired1).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			connection = acquired1.poolable();

			Thread.sleep(10);

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			http2Pool.acquire(Duration.ofMillis(10))
			         .as(StepVerifier::create)
			         .expectError(PoolAcquireTimeoutException.class)
			         .verify(Duration.ofSeconds(1));

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);
			assertThat(http2Pool.connections.size()).isEqualTo(1);

			acquired1.invalidate().block();

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
			assertThat(http2Pool.connections.size()).isEqualTo(0);
		}
		finally {
			if (connection != null) {
				((EmbeddedChannel) connection.channel()).finishAndReleaseAll();
				connection.dispose();
			}
		}
	}

	@Test
	void minConnectionsConfigNotSupported() {
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.<Connection>empty()).sizeBetween(1, 2);
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> poolBuilder.build(config -> new Http2Pool(config, -1)));
	}

	@Test
	void nonHttp2ConnectionEmittedOnce() {
		EmbeddedChannel channel = new EmbeddedChannel();
		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		InstrumentedPool<Connection> http2Pool = poolBuilder.build(config -> new Http2Pool(config, -1));

		try {
			PooledRef<Connection> acquired = http2Pool.acquire().block(Duration.ofSeconds(1));

			assertThat(acquired).isNotNull();
			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);

			http2Pool.acquire(Duration.ofMillis(10))
			         .as(StepVerifier::create)
			         .expectError(PoolAcquireTimeoutException.class)
			         .verify(Duration.ofSeconds(1));

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(1);

			acquired.invalidate().block(Duration.ofSeconds(1));

			assertThat(http2Pool.metrics().acquiredSize()).isEqualTo(0);
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
}
