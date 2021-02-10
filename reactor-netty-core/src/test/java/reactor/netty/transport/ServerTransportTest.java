/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.transport;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.logging.LoggingHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Violeta Georgieva
 */
class ServerTransportTest {

	@Test
	void testBindMonoEmpty() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> new TestServerTransport(Mono.empty()).bindNow(Duration.ofMillis(Long.MAX_VALUE)));
	}

	@Test
	void testBindTimeout() {
		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> new TestServerTransport(Mono.never()).bindNow(Duration.ofMillis(1)));
	}

	@Test
	void testBindTimeoutLongOverflow() {
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> new TestServerTransport(Mono.never()).bindNow(Duration.ofMillis(Long.MAX_VALUE)));
	}

	@Test
	void testDisposeTimeout() {
		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> new TestServerTransport(Mono.just(EmbeddedChannel::new)).bindNow().disposeNow(Duration.ofMillis(1)));
	}

	@Test
	void testDisposeTimeoutLongOverflow() {
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> new TestServerTransport(Mono.just(EmbeddedChannel::new)).bindNow().disposeNow(Duration.ofMillis(Long.MAX_VALUE)));
	}

	static final class TestServerTransport extends ServerTransport<TestServerTransport, TestServerTransportConfig> {

		final Mono<? extends DisposableServer> bind;

		TestServerTransport(Mono<? extends DisposableServer> bind) {
			this.bind = bind;
		}

		@Override
		public Mono<? extends DisposableServer> bind() {
			return bind;
		}

		@Override
		public TestServerTransportConfig configuration() {
			return null;
		}

		@Override
		protected TestServerTransport duplicate() {
			return null;
		}
	}

	static final class TestServerTransportConfig extends ServerTransportConfig<TestServerTransportConfig> {

		TestServerTransportConfig(ServerTransportConfig<TestServerTransportConfig> parent) {
			super(parent);
		}

		@Override
		protected LoggingHandler defaultLoggingHandler() {
			return null;
		}

		@Override
		protected LoopResources defaultLoopResources() {
			return null;
		}

		@Override
		protected ChannelMetricsRecorder defaultMetricsRecorder() {
			return null;
		}
	}
}
