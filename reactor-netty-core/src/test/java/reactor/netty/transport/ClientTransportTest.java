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
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.LoopResources;

import java.time.Duration;

/**
 * @author Violeta Georgieva
 */
public class ClientTransportTest {

	@Test(expected = NullPointerException.class)
	public void testConnectMonoEmpty() {
		new TestClientTransport(Mono.empty()).connectNow(Duration.ofMillis(Long.MAX_VALUE));
	}

	@Test(expected = IllegalStateException.class)
	public void testConnectTimeout() {
		new TestClientTransport(Mono.never()).connectNow(Duration.ofMillis(1));
	}

	@Test(expected = ArithmeticException.class)
	public void testConnectTimeoutLongOverflow() {
		new TestClientTransport(Mono.never()).connectNow(Duration.ofMillis(Long.MAX_VALUE));
	}

	@Test(expected = IllegalStateException.class)
	public void testDisposeTimeout() {
		new TestClientTransport(Mono.just(EmbeddedChannel::new)).connectNow().disposeNow(Duration.ofMillis(1));
	}

	@Test(expected = ArithmeticException.class)
	public void testDisposeTimeoutLongOverflow() {
		new TestClientTransport(Mono.just(EmbeddedChannel::new)).connectNow().disposeNow(Duration.ofMillis(Long.MAX_VALUE));
	}

	static final class TestClientTransport extends ClientTransport<TestClientTransport, TestClientTransportConfig> {

		final Mono<? extends Connection> connect;

		TestClientTransport(Mono<? extends Connection> connect) {
			this.connect = connect;
		}

		@Override
		public TestClientTransportConfig configuration() {
			return null;
		}

		@Override
		protected Mono<? extends Connection> connect() {
			return connect;
		}

		@Override
		protected TestClientTransport duplicate() {
			return null;
		}
	}

	static final class TestClientTransportConfig extends ClientTransportConfig<TestClientTransportConfig> {

		TestClientTransportConfig(ClientTransportConfig<TestClientTransportConfig> parent) {
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

		@Override
		protected AddressResolverGroup<?> defaultResolver() {
			return DefaultAddressResolverGroup.INSTANCE;
		}
	}
}
