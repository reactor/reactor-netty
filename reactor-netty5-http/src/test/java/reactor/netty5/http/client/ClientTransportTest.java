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
package reactor.netty5.http.client;

import io.netty5.channel.ChannelOption;
import io.netty5.handler.logging.LoggingHandler;
import io.netty5.resolver.AddressResolverGroup;
import io.netty5.resolver.NoopAddressResolverGroup;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
import reactor.netty5.channel.ChannelMetricsRecorder;
import reactor.netty5.resources.ConnectionProvider;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.transport.ClientTransport;
import reactor.netty5.transport.ClientTransportConfig;
import reactor.netty5.transport.NameResolverProvider;
import reactor.netty5.transport.ProxyProvider;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Violeta Georgieva
 */
class ClientTransportTest {

	@Test
	void testCreateClientWithHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty("http.proxyHost", "host");

		TestClientTransport transport = createTestTransportForProxy(properties)
				.proxyWithSystemProperties();

		TestClientTransportConfig config = transport.configuration();
		assertThat(config.proxyProvider()).isNotNull();
		assertThat(config.proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(config.resolver()).isSameAs(NoopAddressResolverGroup.INSTANCE);
	}

	@Test
	void testCreateClientWithHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty("https.proxyHost", "host");

		TestClientTransport transport = createTestTransportForProxy(properties)
				.proxyWithSystemProperties();

		TestClientTransportConfig config = transport.configuration();
		assertThat(config.proxyProvider()).isNotNull();
		assertThat(config.proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(config.resolver()).isSameAs(NoopAddressResolverGroup.INSTANCE);
	}

	static TestClientTransport createTestTransportForProxy(Properties properties) {
		ConnectionProvider provider = ConnectionProvider.create("test");
		return new TestClientTransport(Mono.empty(),
				new TestClientTransportConfig(provider, Collections.emptyMap(), () -> null),
				properties
		);
	}

	static final class TestClientTransport extends ClientTransport<TestClientTransport, TestClientTransportConfig> {

		final Mono<? extends Connection> connect;
		final TestClientTransportConfig config;
		final Properties properties;

		TestClientTransport(Mono<? extends Connection> connect, TestClientTransportConfig config, Properties properties) {
			this.connect = connect;
			this.config = config;
			this.properties = properties;
		}

		@Override
		public TestClientTransportConfig configuration() {
			return config;
		}

		@Override
		protected Mono<? extends Connection> connect() {
			return connect;
		}

		@Override
		protected TestClientTransport duplicate() {
			return new TestClientTransport(this.connect, this.config, this.properties);
		}

		@Override
		protected ProxyProvider proxyProviderFrom(Properties systemProperties) {
			return HttpClientProxyProvider.createFrom(properties);
		}
	}

	static final class TestClientTransportConfig extends ClientTransportConfig<TestClientTransportConfig> {

		final AtomicReference<AddressResolverGroup<?>> defaultResolver = new AtomicReference<>();

		TestClientTransportConfig(ConnectionProvider connectionProvider, Map<ChannelOption<?>, ?> options,
		                          Supplier<? extends SocketAddress> remoteAddress) {
			super(connectionProvider, options, remoteAddress);
		}

		@Override
		protected AddressResolverGroup<?> defaultAddressResolverGroup() {
			return NameResolverProvider.builder().build().newNameResolverGroup(loopResources(), true);
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
		protected AddressResolverGroup<?> resolverInternal() {
			defaultResolver.compareAndSet(null, defaultAddressResolverGroup());
			return defaultResolver.get();
		}
	}
}
