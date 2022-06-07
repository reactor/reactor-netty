/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static reactor.netty.http.client.Http2AllocationStrategy.Build.DEFAULT_MAX_CONCURRENT_STREAMS;
import static reactor.netty.http.client.Http2AllocationStrategy.Build.DEFAULT_MAX_CONNECTIONS;
import static reactor.netty.http.client.Http2AllocationStrategy.Build.DEFAULT_MIN_CONNECTIONS;

class Http2AllocationStrategyTest {
	private Http2AllocationStrategy.Builder builder;

	@BeforeEach
	void setUp() {
		builder = Http2AllocationStrategy.builder();
	}

	@Test
	void build() {
		builder.maxConcurrentStreams(2).maxConnections(2).minConnections(1);
		Http2AllocationStrategy strategy = builder.build();
		assertThat(strategy.maxConcurrentStreams()).isEqualTo(2);
		assertThat(strategy.permitMaximum()).isEqualTo(2);
		assertThat(strategy.permitMinimum()).isEqualTo(1);
	}

	@Test
	void buildBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxConnections(1).minConnections(2).build())
				.withMessage("minConnections (2) must be less than or equal to maxConnections (1)");
	}

	@Test
	void copy() {
		builder.maxConcurrentStreams(2).maxConnections(2).minConnections(1);
		Http2AllocationStrategy strategy = builder.build();
		Http2AllocationStrategy copy = strategy.copy();
		assertThat(copy.maxConcurrentStreams()).isEqualTo(strategy.maxConcurrentStreams());
		assertThat(copy.permitMaximum()).isEqualTo(strategy.permitMaximum());
		assertThat(copy.permitMinimum()).isEqualTo(strategy.permitMinimum());
	}

	@Test
	void maxConcurrentStreams() {
		builder.maxConcurrentStreams(2);
		Http2AllocationStrategy strategy = builder.build();
		assertThat(strategy.maxConcurrentStreams()).isEqualTo(2);
		assertThat(strategy.permitMaximum()).isEqualTo(DEFAULT_MAX_CONNECTIONS);
		assertThat(strategy.permitMinimum()).isEqualTo(DEFAULT_MIN_CONNECTIONS);
	}

	@Test
	void maxConcurrentStreamsBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxConcurrentStreams(-2))
				.withMessage("maxConcurrentStreams must be greater than or equal to -1");
	}

	@Test
	void permitMaximum() {
		builder.maxConnections(2);
		Http2AllocationStrategy strategy = builder.build();
		assertThat(strategy.maxConcurrentStreams()).isEqualTo(DEFAULT_MAX_CONCURRENT_STREAMS);
		assertThat(strategy.permitMaximum()).isEqualTo(2);
		assertThat(strategy.permitMinimum()).isEqualTo(DEFAULT_MIN_CONNECTIONS);
	}

	@Test
	void permitMaximumBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.maxConnections(0))
				.withMessage("maxConnections must be strictly positive");
	}

	@Test
	void permitMinimum() {
		builder.minConnections(2);
		Http2AllocationStrategy strategy = builder.build();
		assertThat(strategy.maxConcurrentStreams()).isEqualTo(DEFAULT_MAX_CONCURRENT_STREAMS);
		assertThat(strategy.permitMaximum()).isEqualTo(DEFAULT_MAX_CONNECTIONS);
		assertThat(strategy.permitMinimum()).isEqualTo(2);
	}

	@Test
	void permitMinimumBadValues() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.minConnections(-1))
				.withMessage("minConnections must be positive or zero");
	}
}
