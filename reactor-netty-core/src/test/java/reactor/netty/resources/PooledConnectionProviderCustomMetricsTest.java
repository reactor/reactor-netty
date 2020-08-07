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
package reactor.netty.resources;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.logging.LoggingHandler;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.transport.ClientTransportConfig;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PooledConnectionProviderCustomMetricsTest {

	private Supplier<? extends SocketAddress> remoteAddress;

	private EventLoopGroup group;

	private ConnectionProvider pool;

	@Before
	public void setUp() {
		remoteAddress = () -> InetSocketAddress.createUnresolved("localhost", 0);
		group = new NioEventLoopGroup(2);
	}

	@After
	public void tearDown() throws Exception {
		group.shutdownGracefully()
		     .get(10L, TimeUnit.SECONDS);
		pool.dispose();
	}


	@Test
	public void customRegistrarIsUsed() {
		AtomicBoolean used = new AtomicBoolean();

		triggerAcquisition(true, () -> (a, b, c, d) -> used.set(true));
		assertTrue(used.get());
	}

	@Test
	public void customRegistrarSupplierNotInvokedWhenMetricsDisabled() {
		AtomicBoolean used = new AtomicBoolean();

		triggerAcquisition(false, () -> {used.set(true); return null;});
		assertFalse(used.get());
	}

	private void triggerAcquisition(boolean metricsEnabled, Supplier<ConnectionProvider.MeterRegistrar> registrarSupplier) {
		pool = ConnectionProvider.builder("test")
		                         .metrics(metricsEnabled, registrarSupplier)
		                         .build();

		ClientTransportConfig<?> config =
				new ClientTransportConfigImpl(group, pool, Collections.emptyMap(), remoteAddress);

		try {
			pool.acquire(config, ConnectionObserver.emptyListener(), remoteAddress, config.resolver())
			    .block(Duration.ofSeconds(10L));
			fail("Exception is expected");
		}
		catch (Exception expected) {
			// ignore
		}
	}

	static final class ClientTransportConfigImpl extends ClientTransportConfig<ClientTransportConfigImpl> {

		final EventLoopGroup group;

		ClientTransportConfigImpl(EventLoopGroup group, ConnectionProvider connectionProvider,
				Map<ChannelOption<?>, ?> options, Supplier<? extends SocketAddress> remoteAddress) {
			super(connectionProvider, options, remoteAddress);
			this.group = group;
		}

		@Override
		protected LoggingHandler defaultLoggingHandler() {
			return null;
		}

		@Override
		protected LoopResources defaultLoopResources() {
			return preferNative -> group;
		}

		@Override
		protected ChannelMetricsRecorder defaultMetricsRecorder() {
			return null;
		}

		@Override
		protected AddressResolverGroup<?> defaultResolver() {
			return DefaultAddressResolverGroup.INSTANCE;
		}

		@Override
		protected EventLoopGroup eventLoopGroup() {
			return group;
		}
	}
}
