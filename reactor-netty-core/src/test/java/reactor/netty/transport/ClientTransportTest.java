/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.transport;

import io.netty.channel.ChannelOption;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.logging.LoggingHandler;
import io.netty.resolver.AddressResolverGroup;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.util.annotation.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Violeta Georgieva
 */
class ClientTransportTest {

	@Test
	void testConnectMonoEmpty() {
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> new TestClientTransport(Mono.empty()).connectNow(Duration.ofMillis(Long.MAX_VALUE)));
	}

	@Test
	void testConnectTimeout() {
		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> new TestClientTransport(Mono.never()).connectNow(Duration.ofMillis(1)));
	}

	@Test
	void testConnectTimeoutLongOverflow() {
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> new TestClientTransport(Mono.never()).connectNow(Duration.ofMillis(Long.MAX_VALUE)));
	}

	@Test
	void testDisposeTimeout() {
		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> new TestClientTransport(Mono.just(EmbeddedChannel::new)).connectNow().disposeNow(Duration.ofMillis(1)));
	}

	@Test
	void testDisposeTimeoutLongOverflow() {
		assertThatExceptionOfType(ArithmeticException.class)
				.isThrownBy(() -> new TestClientTransport(Mono.just(EmbeddedChannel::new)).connectNow().disposeNow(Duration.ofMillis(Long.MAX_VALUE)));
	}

	@Test
	void testDefaultResolverWithCustomEventLoop() throws Exception {
		final LoopResources loop1 = LoopResources.create("test", 1, true);
		final NioEventLoopGroup loop2 = new NioEventLoopGroup(1);
		final ConnectionProvider provider = ConnectionProvider.create("test");
		final TestClientTransportConfig config =
				new TestClientTransportConfig(provider, Collections.emptyMap(), () -> null);
		try {
			assertThatExceptionOfType(NullPointerException.class)
					.isThrownBy(config::resolverInternal);

			config.loopResources = loop1;
			config.resolverInternal()
			      .getResolver(loop2.next())
			      .resolve(new InetSocketAddress("example.com", 443))
			      .addListener(f -> assertThat(Thread.currentThread().getName()).startsWith("test-"));
		}
		finally {
			config.defaultResolver.get().close();
			loop1.disposeLater()
			     .block(Duration.ofSeconds(10));
			provider.disposeLater()
			        .block(Duration.ofSeconds(10));
			loop2.shutdownGracefully()
			     .get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	void testClientTransportWarmup() {
		final LoopResources loop = LoopResources.create("testClientTransportWarmup", 1, true);
		final ConnectionProvider provider = ConnectionProvider.create("testClientTransportWarmup");
		TestClientTransportConfig config =
				new TestClientTransportConfig(provider, Collections.emptyMap(), () -> null);
		try {
			config.loopResources = loop;

			TestClientTransport transport =
					new TestClientTransport(Mono.just(EmbeddedChannel::new), config);

			Mono<Void> warmupMono = transport.warmup();

			assertThat(transport.configuration().defaultResolver.get()).isNull();

			warmupMono.block(Duration.ofSeconds(5));

			assertThat(transport.configuration().defaultResolver.get()).isNotNull();
		}
		finally {
			config.defaultResolver.get().close();
			loop.disposeLater()
			    .block(Duration.ofSeconds(5));
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void testCreateClientWithHttpProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTP_PROXY_HOST, "host");

		TestClientTransport transport = createTestTransportForProxy()
				.proxyWithSystemProperties(properties);

		assertThat(transport.configuration().proxyProvider()).isNotNull();
		assertThat(transport.configuration().proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
	}

	@Test
	void testCreateClientWithHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");

		TestClientTransport transport = createTestTransportForProxy()
				.proxyWithSystemProperties(properties);

		assertThat(transport.configuration().proxyProvider()).isNotNull();
		assertThat(transport.configuration().proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
	}

	@Test
	void testCreateClientWithSock5Proxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");

		TestClientTransport transport = createTestTransportForProxy()
				.proxyWithSystemProperties(properties);

		assertThat(transport.configuration().proxyProvider()).isNotNull();
		assertThat(transport.configuration().proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.SOCKS5);
	}

	@Test
	void testCreateClientWithSock4Proxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.SOCKS_VERSION, "4");

		TestClientTransport transport = createTestTransportForProxy()
				.proxyWithSystemProperties(properties);

		assertThat(transport.configuration().proxyProvider()).isNotNull();
		assertThat(transport.configuration().proxyProvider().getType()).isEqualTo(ProxyProvider.Proxy.SOCKS4);
	}

	@Test
	void testCreateClientWithSystemProxyProvider() {
		TestClientTransport transport = createTestTransportForProxy()
				.proxyWithSystemProperties();

		assertThat(transport).isNotNull();
	}

	@Test
	void proxyOverriddenWithNullIfSystemPropertiesHaveNoProxySet() {
		TestClientTransport transport = createTestTransportForProxy();
		transport.proxy(spec -> spec.type(ProxyProvider.Proxy.HTTP).host("proxy").port(8080));
		assertThat(transport.configuration().proxyProvider).isNotNull();

		transport.proxyWithSystemProperties(new Properties());
		assertThat(transport.configuration().proxyProvider).isNull();
	}

	static TestClientTransport createTestTransportForProxy() {
		ConnectionProvider provider = ConnectionProvider.create("test");
		return new TestClientTransport(Mono.empty(),
				new TestClientTransportConfig(provider, Collections.emptyMap(), () -> null)
		);
	}

	static final class TestClientTransport extends ClientTransport<TestClientTransport, TestClientTransportConfig> {

		final Mono<? extends Connection> connect;
		final TestClientTransportConfig config;

		TestClientTransport(Mono<? extends Connection> connect) {
			this(connect, null);
		}

		TestClientTransport(Mono<? extends Connection> connect, @Nullable TestClientTransportConfig config) {
			this.connect = connect;
			this.config = config;
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
			return new TestClientTransport(this.connect, this.config);
		}
	}

	static final class TestClientTransportConfig extends ClientTransportConfig<TestClientTransportConfig> {

		AtomicReference<AddressResolverGroup<?>> defaultResolver = new AtomicReference<>();

		TestClientTransportConfig(ConnectionProvider connectionProvider, Map<ChannelOption<?>, ?> options,
				Supplier<? extends SocketAddress> remoteAddress) {
			super(connectionProvider, options, remoteAddress);
		}

		@Override
		protected AddressResolverGroup<?> defaultAddressResolverGroup() {
			return NameResolverProvider.builder().build().newNameResolverGroup(loopResources, true);
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
