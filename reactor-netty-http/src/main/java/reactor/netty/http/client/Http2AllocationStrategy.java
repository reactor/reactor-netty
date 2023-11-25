/*
 * Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import reactor.netty.resources.ConnectionProvider;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * HTTP/2 {@link ConnectionProvider.AllocationStrategy}.
 *
 * <p>This class is based on
 * https://github.com/reactor/reactor-pool/blob/d5cb5b72cdbcbbee8d781e06972c4da21766107f/src/main/java/reactor/pool/AllocationStrategies.java#L73
 *
 * @author Violeta Georgieva
 * @since 1.0.20
 */
public final class Http2AllocationStrategy implements ConnectionProvider.AllocationStrategy<Http2AllocationStrategy> {

	public interface Builder {

		/**
		 * Build a new {@link Http2AllocationStrategy}.
		 *
		 * @return a new {@link Http2AllocationStrategy}
		 */
		Http2AllocationStrategy build();

		/**
		 * Configures the maximum number of the concurrent streams that can be opened to the remote peer.
		 * When evaluating how many streams can be opened to the remote peer,
		 * the minimum of this configuration and the remote peer configuration is taken (unless -1 is used).
		 * Default to {@code -1} - use always the remote peer configuration.
		 *
		 * @param maxConcurrentStreams the maximum number of the concurrent streams that can be opened to the remote peer
		 * @return {@code this}
		 */
		Builder maxConcurrentStreams(long maxConcurrentStreams);

		/**
		 * Configures the maximum number of live connections to keep in the pool.
		 * Default to {@link Integer#MAX_VALUE} - no upper limit.
		 *
		 * @param maxConnections the maximum number of live connections to keep in the pool
		 * @return {@code this}
		 */
		Builder maxConnections(int maxConnections);

		/**
		 * Configures the minimum number of live connections to keep in the pool (can be the best effort).
		 * Default to {@code 0}.
		 *
		 * @return {@code this}
		 */
		Builder minConnections(int minConnections);

		/**
		 * Enables or disables work stealing mode for managing HTTP2 Connection Pools.
		 * <p>
		 * By default, a single Connection Pool is used by multiple Netty event loop threads.
		 * When work stealing is enabled, each Netty event loop will maintain its own
		 * HTTP2 Connection Pool, and HTTP2 streams allocation will be distributed over all available
		 * pools using a work stealing strategy. This approach maximizes throughput and
		 * resource utilization in a multithreaded environment.
		 *
		 * @param progressive true if the HTTP2 Connection pools should be enabled gradually (when the nth pool becomes
		 *                    is starting to get some pendingg acquisitions request, then enable one more
		 *                    pool until all available pools are enabled).
		 *
		 * @return {@code this}
		 */
		Builder enableWorkStealing(boolean progressive);
	}

	/**
	 * Creates a builder for {@link Http2AllocationStrategy}.
	 *
	 * @return a new {@link Http2AllocationStrategy.Builder}
	 */
	public static Http2AllocationStrategy.Builder builder() {
		return new Http2AllocationStrategy.Build();
	}

	/**
	 * Creates a builder for {@link Http2AllocationStrategy} and initialize it
	 * with an existing strategy. This method can be used to create a mutated version
	 * of an existing strategy.
	 *
	 * @return a new {@link Http2AllocationStrategy.Builder} initialized with an existing http2
	 * allocation strategy.
	 */
	public static Http2AllocationStrategy.Builder builder(Http2AllocationStrategy existing) {
		return new Http2AllocationStrategy.Build(existing);
	}

	@Override
	public Http2AllocationStrategy copy() {
		return new Http2AllocationStrategy(this);
	}

	@Override
	public int estimatePermitCount() {
		return PERMITS.get(this);
	}

	@Override
	public int getPermits(int desired) {
		if (desired < 0) {
			return 0;
		}

		for (;;) {
			int p = permits;
			int target = Math.min(desired, p);

			if (PERMITS.compareAndSet(this, p, p - target)) {
				return target;
			}
		}
	}

	/**
	 * Returns the configured maximum number of the concurrent streams that can be opened to the remote peer.
	 *
	 * @return the configured maximum number of the concurrent streams that can be opened to the remote peer
	 */
	public long maxConcurrentStreams() {
		return maxConcurrentStreams;
	}

	@Override
	public int permitGranted() {
		return maxConnections - PERMITS.get(this);
	}

	@Override
	public int permitMinimum() {
		return minConnections;
	}

	@Override
	public int permitMaximum() {
		return maxConnections;
	}

	@Override
	public void returnPermits(int returned) {
		for (;;) {
			int p = PERMITS.get(this);
			if (p + returned > maxConnections) {
				throw new IllegalArgumentException("Too many permits returned: returned=" + returned +
						", would bring to " + (p + returned) + "/" + maxConnections);
			}
			if (PERMITS.compareAndSet(this, p, p + returned)) {
				return;
			}
		}
	}

	public boolean enableWorkStealing() {
		return enableWorkStealing;
	}

	final long maxConcurrentStreams;
	final int maxConnections;
	final int minConnections;
	final boolean enableWorkStealing;

	volatile int permits;
	static final AtomicIntegerFieldUpdater<Http2AllocationStrategy> PERMITS = AtomicIntegerFieldUpdater.newUpdater(Http2AllocationStrategy.class, "permits");

	Http2AllocationStrategy(Build build) {
		this.maxConcurrentStreams = build.maxConcurrentStreams;
		this.maxConnections = build.maxConnections;
		this.minConnections = build.minConnections;
		this.enableWorkStealing = build.enableWorkStealing;
		PERMITS.lazySet(this, this.maxConnections);
	}

	Http2AllocationStrategy(Http2AllocationStrategy copy) {
		this.maxConcurrentStreams = copy.maxConcurrentStreams;
		this.maxConnections = copy.maxConnections;
		this.minConnections = copy.minConnections;
		this.enableWorkStealing = copy.enableWorkStealing;
		PERMITS.lazySet(this, this.maxConnections);
	}

	static final class Build implements Builder {
		static final long DEFAULT_MAX_CONCURRENT_STREAMS = -1;
		static final int DEFAULT_MAX_CONNECTIONS = Integer.MAX_VALUE;
		static final int DEFAULT_MIN_CONNECTIONS = 0;

		long maxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
		int maxConnections = DEFAULT_MAX_CONNECTIONS;
		int minConnections = DEFAULT_MIN_CONNECTIONS;
		boolean enableWorkStealing = Boolean.getBoolean("reactor.netty.pool.h2.enableworkstealing");

		Build() {
		}

		Build(Http2AllocationStrategy existing) {
			this.maxConcurrentStreams = existing.maxConcurrentStreams;
			this.minConnections = existing.minConnections;
			this.maxConnections = existing.maxConnections;
			this.enableWorkStealing = existing.enableWorkStealing;
		}

		@Override
		public Http2AllocationStrategy build() {
			if (minConnections > maxConnections) {
				throw new IllegalArgumentException("minConnections (" + minConnections + ")" +
						" must be less than or equal to maxConnections (" + maxConnections + ")");
			}
			return new Http2AllocationStrategy(this);
		}

		@Override
		public Builder maxConcurrentStreams(long maxConcurrentStreams) {
			if (maxConcurrentStreams < -1) {
				throw new IllegalArgumentException("maxConcurrentStreams must be greater than or equal to -1");
			}
			this.maxConcurrentStreams = maxConcurrentStreams;
			return this;
		}

		@Override
		public Builder maxConnections(int maxConnections) {
			if (maxConnections < 1) {
				throw new IllegalArgumentException("maxConnections must be strictly positive");
			}
			this.maxConnections = maxConnections;
			return this;
		}

		@Override
		public Builder minConnections(int minConnections) {
			if (minConnections < 0) {
				throw new IllegalArgumentException("minConnections must be positive or zero");
			}
			this.minConnections = minConnections;
			return this;
		}

		@Override
		public Builder enableWorkStealing(boolean progressive) {
			this.enableWorkStealing = true;
			return this;
		}
	}
}
