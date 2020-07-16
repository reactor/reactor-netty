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

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PooledConnectionProviderCustomMetricsTest {

	private Bootstrap bootstrap;

	private ConnectionProvider pool;

	@Before
	public void setUp() {
		bootstrap = new Bootstrap().remoteAddress("localhost", 0)
				.channelFactory(NioSocketChannel::new)
				.group(new NioEventLoopGroup(2));
	}

	@After
	public void tearDown() throws Exception {
		bootstrap.config().group().shutdownGracefully().get(10L, TimeUnit.SECONDS);
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

		try {
			pool.acquire(bootstrap).block(Duration.ofSeconds(10L));
		}
		catch (Exception expected) {
		}
	}

}
