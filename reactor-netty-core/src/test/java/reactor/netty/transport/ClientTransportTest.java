/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.logging.LoggingHandler;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.HostsFileEntriesProvider;
import io.netty.resolver.NoopAddressResolverGroup;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

/**
 * This test class verifies {@link ClientTransport}.
 *
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
		final EventLoopGroup loop2 = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
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
			AddressResolverGroup<?> resolverGroup = config.defaultResolver.get();
			assertThat(resolverGroup).isNotNull();
			resolverGroup.close();
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
			AddressResolverGroup<?> resolverGroup = config.defaultResolver.get();
			assertThat(resolverGroup).isNotNull();
			resolverGroup.close();
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

		TestClientTransportConfig config = transport.configuration();
		Supplier<ProxyProvider> proxyProvider = config.proxyProviderSupplier();
		assertThat(proxyProvider).isNotNull();
		assertThat(proxyProvider.get().getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(config.resolver()).isSameAs(NoopAddressResolverGroup.INSTANCE);
	}

	@Test
	void testCreateClientWithHttpsProxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.HTTPS_PROXY_HOST, "host");

		TestClientTransport transport = createTestTransportForProxy()
				.proxyWithSystemProperties(properties);

		TestClientTransportConfig config = transport.configuration();
		Supplier<ProxyProvider> proxyProvider = config.proxyProviderSupplier();
		assertThat(proxyProvider).isNotNull();
		assertThat(proxyProvider.get().getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
		assertThat(config.resolver()).isSameAs(NoopAddressResolverGroup.INSTANCE);
	}

	@Test
	void testCreateClientWithSock5Proxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");

		TestClientTransport transport = createTestTransportForProxy()
				.proxyWithSystemProperties(properties);

		TestClientTransportConfig config = transport.configuration();
		Supplier<ProxyProvider> proxyProvider = config.proxyProviderSupplier();
		assertThat(proxyProvider).isNotNull();
		assertThat(proxyProvider.get().getType()).isEqualTo(ProxyProvider.Proxy.SOCKS5);
		assertThat(config.resolver()).isSameAs(NoopAddressResolverGroup.INSTANCE);
	}

	@Test
	void testCreateClientWithSock4Proxy() {
		Properties properties = new Properties();
		properties.setProperty(ProxyProvider.SOCKS_PROXY_HOST, "host");
		properties.setProperty(ProxyProvider.SOCKS_VERSION, "4");

		TestClientTransport transport = createTestTransportForProxy()
				.proxyWithSystemProperties(properties);

		TestClientTransportConfig config = transport.configuration();
		Supplier<ProxyProvider> proxyProvider = config.proxyProviderSupplier();
		assertThat(proxyProvider).isNotNull();
		assertThat(proxyProvider.get().getType()).isEqualTo(ProxyProvider.Proxy.SOCKS4);
		assertThat(config.resolver()).isSameAs(NoopAddressResolverGroup.INSTANCE);
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
		TestClientTransportConfig configuration1 = transport.configuration();
		assertThat(configuration1.proxyProvider).isNull();
		assertThat(configuration1.proxyProviderSupplier).isNotNull();
		assertThat(configuration1.resolver()).isSameAs(NoopAddressResolverGroup.INSTANCE);

		transport.proxyWithSystemProperties(new Properties());
		TestClientTransportConfig configuration2 = transport.configuration();
		assertThat(configuration2.proxyProvider).isNull();
		assertThat(configuration2.proxyProviderSupplier).isNull();
		assertThat(configuration2.resolver()).isNull();
	}

	@Test
	void noProxyIfSystemPropertiesHaveNoProxySet() {
		TestClientTransport transport = createTestTransportForProxy();
		TestClientTransportConfig configuration1 = transport.configuration();
		assertThat(configuration1.proxyProvider).isNull();
		assertThat(configuration1.proxyProviderSupplier).isNull();
		assertThat(configuration1.resolver()).isNull();

		transport.proxyWithSystemProperties(new Properties());
		TestClientTransportConfig configuration2 = transport.configuration();
		assertThat(configuration2.proxyProvider).isNull();
		assertThat(configuration2.proxyProviderSupplier).isNull();
		assertThat(configuration2.resolver()).isNull();
	}

	static TestClientTransport createTestTransportForProxy() {
		ConnectionProvider provider = ConnectionProvider.create("test");
		return new TestClientTransport(Mono.empty(),
				new TestClientTransportConfig(provider, Collections.emptyMap(), () -> null)
		);
	}

	/**
	 * On Windows OS it is rare to have hosts file.
	 */
	@Test
	@DisabledOnOs(WINDOWS)
	void testDefaultHostsFileEntriesResolver() throws Exception {
		doTestHostsFileEntriesResolver(false);
	}

	/**
	 * On Windows OS it is rare to have hosts file.
	 */
	@Test
	@DisabledOnOs(WINDOWS)
	void testCustomHostsFileEntriesResolver() throws Exception {
		doTestHostsFileEntriesResolver(true);
	}

	@SuppressWarnings("unchecked")
	private static void doTestHostsFileEntriesResolver(boolean customResolver) throws Exception {
		LoopResources loop1 = LoopResources.create("test", 1, true);
		EventLoopGroup loop2 = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
		ConnectionProvider provider = ConnectionProvider.create("test");
		TestClientTransportConfig config =
				new TestClientTransportConfig(provider, Collections.emptyMap(), () -> null);
		HostsFileEntriesProvider hostsFileEntriesProvider = HostsFileEntriesProvider.parser().parseSilently();
		List<InetAddress> addresses = hostsFileEntriesProvider.ipv4Entries().get("localhost");
		try {
			config.loopResources = loop1;
			if (customResolver) {
				NameResolverProvider nameResolverProvider = NameResolverProvider.builder()
						.hostsFileEntriesResolver((inetHost, resolvedAddressTypes) ->
							// Returns only the first entry from the hosts file
							addresses != null && !addresses.isEmpty() ? addresses.get(0) : null)
						.build();
				config.defaultResolver.set(nameResolverProvider.newNameResolverGroup(config.loopResources, true));
			}

			AtomicReference<List<InetAddress>> resolved = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);
			config.resolverInternal()
			      .getResolver(loop2.next())
			      .resolveAll(InetSocketAddress.createUnresolved("localhost", 443))
			      .addListener(f -> {
			          resolved.set(
			              ((List<InetSocketAddress>) f.getNow())
			                      .stream()
			                      .map(InetSocketAddress::getAddress)
			                      .collect(Collectors.toList()));
			          latch.countDown();
			      });

			assertThat(latch.await(5, TimeUnit.SECONDS)).as("latch await").isTrue();

			List<InetAddress> actual = resolved.get();
			assertThat(actual).isNotNull();
			if (customResolver) {
				assertThat(actual).hasSize(1);
				assertThat(actual.get(0)).isEqualTo(addresses.get(0));
			}
			else {
				assertThat(actual).hasSize(addresses.size());
				assertThat(actual).isEqualTo(addresses);
			}
		}
		finally {
			AddressResolverGroup<?> resolverGroup = config.defaultResolver.get();
			assertThat(resolverGroup).isNotNull();
			resolverGroup.close();
			loop1.disposeLater()
			     .block(Duration.ofSeconds(10));
			provider.disposeLater()
			        .block(Duration.ofSeconds(10));
			loop2.shutdownGracefully()
			     .get(10, TimeUnit.SECONDS);
		}
	}

	static final class TestClientTransport extends ClientTransport<TestClientTransport, TestClientTransportConfig> {

		final Mono<? extends Connection> connect;
		final @Nullable TestClientTransportConfig config;

		TestClientTransport(Mono<? extends Connection> connect) {
			this(connect, null);
		}

		TestClientTransport(Mono<? extends Connection> connect, @Nullable TestClientTransportConfig config) {
			this.connect = connect;
			this.config = config;
		}

		@Override
		@SuppressWarnings("NullAway")
		public TestClientTransportConfig configuration() {
			// Deliberately suppress "NullAway" for testing purposes
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

		final AtomicReference<AddressResolverGroup<?>> defaultResolver = new AtomicReference<>();

		TestClientTransportConfig(ConnectionProvider connectionProvider, Map<ChannelOption<?>, ?> options,
				Supplier<? extends SocketAddress> remoteAddress) {
			super(connectionProvider, options, remoteAddress);
		}

		@Override
		@SuppressWarnings("NullAway")
		protected AddressResolverGroup<?> defaultAddressResolverGroup() {
			// Deliberately suppress "NullAway" for testing purposes
			// loopResources is lazy initialized
			return NameResolverProvider.builder().build().newNameResolverGroup(loopResources, true);
		}

		@Override
		@SuppressWarnings("NullAway")
		protected LoggingHandler defaultLoggingHandler() {
			// Deliberately suppress "NullAway" for testing purposes
			return null;
		}

		@Override
		@SuppressWarnings("NullAway")
		protected LoopResources defaultLoopResources() {
			// Deliberately suppress "NullAway" for testing purposes
			return null;
		}

		@Override
		@SuppressWarnings("NullAway")
		protected ChannelMetricsRecorder defaultMetricsRecorder() {
			// Deliberately suppress "NullAway" for testing purposes
			return null;
		}

		@Override
		@SuppressWarnings("NullAway")
		protected AddressResolverGroup<?> resolverInternal() {
			defaultResolver.compareAndSet(null, defaultAddressResolverGroup());
			// Deliberately suppress "NullAway"
			// defaultResolver is initialized on the previous row
			return defaultResolver.get();
		}
	}
}
