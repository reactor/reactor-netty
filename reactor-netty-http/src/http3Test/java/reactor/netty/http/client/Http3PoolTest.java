/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.MessageSizeEstimator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicChannelConfig;
import io.netty.handler.codec.quic.QuicConnectionAddress;
import io.netty.handler.codec.quic.QuicConnectionPathStats;
import io.netty.handler.codec.quic.QuicConnectionStats;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.codec.quic.QuicTransportParameters;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.internal.shaded.reactor.pool.PoolBuilder;
import reactor.netty.internal.shaded.reactor.pool.PoolConfig;
import reactor.netty.internal.shaded.reactor.pool.PooledRef;

import javax.net.ssl.SSLEngine;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class Http3PoolTest {

	@Test
	void evictClosedConnection() {
		TestQuicChannel channel1 = new TestQuicChannel(5);
		TestQuicChannel channel2 = new TestQuicChannel(5);
		List<TestQuicChannel> channels = Arrays.asList(channel1, channel2);
		int[] idx = {0};

		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> Connection.from(channels.get(idx[0]++))))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 2);
		Http3Pool http3Pool = poolBuilder.build(config -> new Http3Pool(config, null));

		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();
			http3Pool.acquire().subscribe(conn -> {
				acquired.add(conn);
				channel1.decrementPeerAllowedStreams(QuicStreamType.BIDIRECTIONAL);
			});
			channel1.runPendingTasks();

			assertThat(acquired).hasSize(1);
			assertThat(http3Pool.activeStreams()).isEqualTo(1);

			Connection conn1 = acquired.get(0).poolable();

			channel1.finishAndReleaseAll();
			conn1.dispose();

			acquired.get(0).invalidate().block(Duration.ofSeconds(1));

			assertThat(http3Pool.activeStreams()).isEqualTo(0);

			http3Pool.acquire().subscribe(conn -> {
				acquired.add(conn);
				channel2.decrementPeerAllowedStreams(QuicStreamType.BIDIRECTIONAL);
			});
			channel2.runPendingTasks();

			assertThat(acquired).hasSize(2);
			Connection conn2 = acquired.get(1).poolable();
			assertThat(conn1.channel().id()).isNotEqualTo(conn2.channel().id());

			acquired.get(1).invalidate().block(Duration.ofSeconds(1));
		}
		finally {
			for (TestQuicChannel ch : channels) {
				ch.finishAndReleaseAll();
				Connection.from(ch).dispose();
			}
		}
	}

	@Test
	void canOpenStreamWithMaxConcurrentStreamsRespected() {
		canOpenStream(10, 3);
	}

	@Test
	void canOpenStreamWithPeerMaxStreamsNoAllocationStrategy() {
		canOpenStream(3, 0);
	}

	@Test
	void canOpenStreamWithPeerMaxStreamsRespected() {
		canOpenStream(3, 10);
	}

	private static void canOpenStream(int peerMaxStreams, int maxConcurrentStreams) {
		TestQuicChannel channel = new TestQuicChannel(peerMaxStreams);

		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		Http2AllocationStrategy strategy = maxConcurrentStreams == 0 ? null :
				Http2AllocationStrategy.builder()
						.maxConnections(1)
						.maxConcurrentStreams(maxConcurrentStreams)
						.build();
		Http3Pool http3Pool = poolBuilder.build(config -> new Http3Pool(config, strategy));

		int effectiveLimit = maxConcurrentStreams == 0 ? peerMaxStreams : Math.min(peerMaxStreams, maxConcurrentStreams);
		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();

			for (int i = 0; i < 3; i++) {
				http3Pool.acquire().subscribe(conn -> {
					acquired.add(conn);
					channel.decrementPeerAllowedStreams(QuicStreamType.BIDIRECTIONAL);
				});
				channel.runPendingTasks();
			}

			assertThat(acquired).hasSize(effectiveLimit);
			assertThat(http3Pool.activeStreams()).isEqualTo(effectiveLimit);

			Http2Pool.Slot slot = ((Http2Pool.Http2PooledRef) acquired.get(0)).slot;

			assertThat(slot.canOpenStream()).isFalse();

			for (PooledRef<Connection> ref : acquired) {
				ref.invalidate().block(Duration.ofSeconds(1));
			}

			assertThat(http3Pool.activeStreams()).isEqualTo(0);
		}
		finally {
			channel.finishAndReleaseAll();
			Connection.from(channel).dispose();
		}
	}

	@Test
	void maxStreamsCausesNewAllocation() {
		TestQuicChannel channel1 = new TestQuicChannel(2);
		TestQuicChannel channel2 = new TestQuicChannel(2);
		List<TestQuicChannel> channels = Arrays.asList(channel1, channel2);
		int[] idx = {0};

		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.fromSupplier(() -> Connection.from(channels.get(idx[0]++))))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 2);
		Http3Pool http3Pool = poolBuilder.build(config -> new Http3Pool(config, null));

		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();

			for (int i = 0; i < 2; i++) {
				http3Pool.acquire().subscribe(conn -> {
					acquired.add(conn);
					channel1.decrementPeerAllowedStreams(QuicStreamType.BIDIRECTIONAL);
				});
				channel1.runPendingTasks();
			}

			assertThat(acquired).hasSize(2);
			assertThat(http3Pool.activeStreams()).isEqualTo(2);

			Http2Pool.Slot slot1 = ((Http2Pool.Http2PooledRef) acquired.get(0)).slot;
			Http2Pool.Slot slot2 = ((Http2Pool.Http2PooledRef) acquired.get(1)).slot;
			assertThat(slot2).isSameAs(slot1);

			assertThat(slot1.maxConcurrentStreams).isEqualTo(2);
			assertThat(slot1.canOpenStream()).isFalse();

			http3Pool.acquire().subscribe(conn -> {
				acquired.add(conn);
				channel2.decrementPeerAllowedStreams(QuicStreamType.BIDIRECTIONAL);
			});
			channel2.runPendingTasks();

			assertThat(acquired).hasSize(3);
			assertThat(http3Pool.activeStreams()).isEqualTo(3);

			Http2Pool.Slot slot3 = ((Http2Pool.Http2PooledRef) acquired.get(2)).slot;
			assertThat(slot3).isNotSameAs(slot1);

			assertThat(slot3.maxConcurrentStreams).isEqualTo(2);
			assertThat(slot3.canOpenStream()).isTrue();

			for (PooledRef<Connection> ref : acquired) {
				ref.invalidate().block(Duration.ofSeconds(1));
			}
		}
		finally {
			for (TestQuicChannel ch : channels) {
				ch.finishAndReleaseAll();
				Connection.from(ch).dispose();
			}
		}
	}

	@Test
	void serverReplenishesStreams() {
		TestQuicChannel channel = new TestQuicChannel(2);

		PoolBuilder<Connection, PoolConfig<Connection>> poolBuilder =
				PoolBuilder.from(Mono.just(Connection.from(channel)))
				           .idleResourceReuseLruOrder()
				           .maxPendingAcquireUnbounded()
				           .sizeBetween(0, 1);
		Http3Pool http3Pool = poolBuilder.build(config -> new Http3Pool(config, null));

		try {
			List<PooledRef<Connection>> acquired = new ArrayList<>();

			for (int i = 0; i < 2; i++) {
				http3Pool.acquire().subscribe(conn -> {
					acquired.add(conn);
					channel.decrementPeerAllowedStreams(QuicStreamType.BIDIRECTIONAL);
				});
				channel.runPendingTasks();
			}

			assertThat(acquired).hasSize(2);
			assertThat(http3Pool.activeStreams()).isEqualTo(2);

			Http2Pool.Slot slot = ((Http2Pool.Http2PooledRef) acquired.get(0)).slot;
			assertThat(slot.canOpenStream()).isFalse();
			assertThat(slot.maxConcurrentStreams).isEqualTo(2);

			acquired.get(0).invalidate().block(Duration.ofSeconds(1));

			assertThat(http3Pool.activeStreams()).isEqualTo(1);
			// In HTTP/3, releasing a stream does NOT replenish peerAllowedStreams.
			// The peer budget is 0 (both used) and only the server can replenish via MAX_STREAMS.
			assertThat(slot.canOpenStream()).isFalse();

			// A new acquire should not succeed (no peer budget, no new connection possible)
			http3Pool.acquire().subscribe(conn -> {
				acquired.add(conn);
				channel.decrementPeerAllowedStreams(QuicStreamType.BIDIRECTIONAL);
			});
			channel.runPendingTasks();

			assertThat(acquired).hasSize(2);

			// Simulate server replenishing streams via MAX_STREAMS
			channel.setPeerAllowedStreams(QuicStreamType.BIDIRECTIONAL, 1);
			slot.updateMaxConcurrentStreams(0);

			channel.runPendingTasks();

			assertThat(acquired).hasSize(3);
			assertThat(http3Pool.activeStreams()).isEqualTo(2);
			assertThat(slot.maxConcurrentStreams).isEqualTo(1);

			for (PooledRef<Connection> ref : acquired) {
				ref.invalidate().block(Duration.ofSeconds(1));
			}

			assertThat(http3Pool.activeStreams()).isEqualTo(0);
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

	static final class TestQuicChannel extends EmbeddedChannel implements QuicChannel {
		private final Map<QuicStreamType, Long> peerAllowedStreams = new EnumMap<>(QuicStreamType.class);
		private TestQuicChannelConfig config;

		TestQuicChannel(long initialMaxStreamsBidi) {
			super(new TestChannelId());
			this.peerAllowedStreams.put(QuicStreamType.BIDIRECTIONAL, initialMaxStreamsBidi);
			this.peerAllowedStreams.put(QuicStreamType.UNIDIRECTIONAL, 0L);
		}

		void setPeerAllowedStreams(QuicStreamType type, long value) {
			this.peerAllowedStreams.put(type, value);
		}

		void decrementPeerAllowedStreams(QuicStreamType type) {
			this.peerAllowedStreams.compute(type, (k, v) -> v == null ? 0L : Math.max(0, v - 1));
		}

		@Override
		public @Nullable QuicConnectionAddress localAddress() {
			return null;
		}

		@Override
		public @Nullable QuicConnectionAddress remoteAddress() {
			return null;
		}

		@Override
		public @Nullable SocketAddress localSocketAddress() {
			return null;
		}

		@Override
		public @Nullable SocketAddress remoteSocketAddress() {
			return null;
		}

		@Override
		public boolean isTimedOut() {
			return false;
		}

		@Override
		public @Nullable SSLEngine sslEngine() {
			return null;
		}

		@Override
		public QuicChannelConfig config() {
			if (config == null) {
				config = new TestQuicChannelConfig(super.config());
			}
			return config;
		}

		@Override
		public QuicChannel flush() {
			super.flush();
			return this;
		}

		@Override
		public QuicChannel read() {
			super.read();
			return this;
		}

		@Override
		public long peerAllowedStreams(QuicStreamType type) {
			return peerAllowedStreams.getOrDefault(type, 0L);
		}

		@Override
		public Future<QuicStreamChannel> createStream(QuicStreamType type, ChannelHandler handler,
				Promise<QuicStreamChannel> promise) {
			return promise.setFailure(new UnsupportedOperationException("Not implemented for test"));
		}

		@Override
		public ChannelFuture close(boolean applicationClose, int error, ByteBuf reason, ChannelPromise promise) {
			reason.release();
			return close(promise);
		}

		@Override
		public Future<QuicConnectionStats> collectStats(Promise<QuicConnectionStats> promise) {
			return promise.setFailure(new UnsupportedOperationException("Not implemented for test"));
		}

		@Override
		public Future<QuicConnectionPathStats> collectPathStats(int i, Promise<QuicConnectionPathStats> promise) {
			return promise.setFailure(new UnsupportedOperationException("Not implemented for test"));
		}

		@Override
		public @Nullable QuicTransportParameters peerTransportParameters() {
			return null;
		}
	}

	static final class TestQuicChannelConfig implements QuicChannelConfig {

		final ChannelConfig delegate;

		TestQuicChannelConfig(ChannelConfig delegate) {
			this.delegate = delegate;
		}

		@Override
		public Map<ChannelOption<?>, Object> getOptions() {
			return delegate.getOptions();
		}

		@Override
		public boolean setOptions(Map<ChannelOption<?>, ?> map) {
			return delegate.setOptions(map);
		}

		@Override
		public <T> T getOption(ChannelOption<T> option) {
			return delegate.getOption(option);
		}

		@Override
		public <T> boolean setOption(ChannelOption<T> option, T value) {
			return delegate.setOption(option, value);
		}

		@Override
		public int getConnectTimeoutMillis() {
			return delegate.getConnectTimeoutMillis();
		}

		@Override
		public QuicChannelConfig setConnectTimeoutMillis(int ms) {
			delegate.setConnectTimeoutMillis(ms);
			return this;
		}

		@Override
		@Deprecated
		public int getMaxMessagesPerRead() {
			return delegate.getMaxMessagesPerRead();
		}

		@Override
		@Deprecated
		public QuicChannelConfig setMaxMessagesPerRead(int max) {
			delegate.setMaxMessagesPerRead(max);
			return this;
		}

		@Override
		public int getWriteSpinCount() {
			return delegate.getWriteSpinCount();
		}

		@Override
		public QuicChannelConfig setWriteSpinCount(int count) {
			delegate.setWriteSpinCount(count);
			return this;
		}

		@Override
		public ByteBufAllocator getAllocator() {
			return delegate.getAllocator();
		}

		@Override
		public QuicChannelConfig setAllocator(ByteBufAllocator alloc) {
			delegate.setAllocator(alloc);
			return this;
		}

		@Override
		public <T extends RecvByteBufAllocator> T getRecvByteBufAllocator() {
			return delegate.getRecvByteBufAllocator();
		}

		@Override
		public QuicChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator alloc) {
			delegate.setRecvByteBufAllocator(alloc);
			return this;
		}

		@Override
		public boolean isAutoRead() {
			return delegate.isAutoRead();
		}

		@Override
		public QuicChannelConfig setAutoRead(boolean autoRead) {
			delegate.setAutoRead(autoRead);
			return this;
		}

		@Override
		public boolean isAutoClose() {
			return delegate.isAutoClose();
		}

		@Override
		public QuicChannelConfig setAutoClose(boolean autoClose) {
			delegate.setAutoClose(autoClose);
			return this;
		}

		@Override
		public int getWriteBufferHighWaterMark() {
			return delegate.getWriteBufferHighWaterMark();
		}

		@Override
		public QuicChannelConfig setWriteBufferHighWaterMark(int mark) {
			delegate.setWriteBufferHighWaterMark(mark);
			return this;
		}

		@Override
		public int getWriteBufferLowWaterMark() {
			return delegate.getWriteBufferLowWaterMark();
		}

		@Override
		public QuicChannelConfig setWriteBufferLowWaterMark(int mark) {
			delegate.setWriteBufferLowWaterMark(mark);
			return this;
		}

		@Override
		public MessageSizeEstimator getMessageSizeEstimator() {
			return delegate.getMessageSizeEstimator();
		}

		@Override
		public QuicChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) {
			delegate.setMessageSizeEstimator(estimator);
			return this;
		}

		@Override
		public WriteBufferWaterMark getWriteBufferWaterMark() {
			return delegate.getWriteBufferWaterMark();
		}

		@Override
		public QuicChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark mark) {
			delegate.setWriteBufferWaterMark(mark);
			return this;
		}
	}
}
