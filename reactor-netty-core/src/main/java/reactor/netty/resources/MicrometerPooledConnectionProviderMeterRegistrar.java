/*
 * Copyright (c) 2019-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import java.net.SocketAddress;

import io.micrometer.core.instrument.Gauge;
import reactor.netty.Metrics;
import reactor.pool.InstrumentedPool;

import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.ID;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.MAX_CONNECTIONS;
import static reactor.netty.Metrics.MAX_PENDING_CONNECTIONS;
import static reactor.netty.Metrics.PENDING_CONNECTIONS;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;

/**
 * Default implementation of {@link reactor.netty.resources.ConnectionProvider.MeterRegistrar}.
 *
 * Registers gauges for every metric in {@link ConnectionPoolMetrics}.
 *
 * Every gauge uses id, poolName and remoteAddress as tags.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
final class MicrometerPooledConnectionProviderMeterRegistrar {

	static final MicrometerPooledConnectionProviderMeterRegistrar INSTANCE = new MicrometerPooledConnectionProviderMeterRegistrar();

	private MicrometerPooledConnectionProviderMeterRegistrar() {}

	void registerMetrics(String poolName, String id, SocketAddress remoteAddress, InstrumentedPool.PoolMetrics metrics) {
		String addressAsString = Metrics.formatSocketAddress(remoteAddress);
		String[] tags = new String[] {ID, id, REMOTE_ADDRESS, addressAsString, NAME, poolName};
		Gauge.builder(CONNECTION_PROVIDER_PREFIX + TOTAL_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::allocatedSize)
		     .description("The number of all connections, active or idle.")
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::acquiredSize)
		     .description("The number of the connections that have been successfully acquired and are in active use")
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::idleSize)
		     .description("The number of the idle connections")
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + PENDING_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::pendingAcquireSize)
		     .description("The number of the request, that are pending acquire a connection")
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + MAX_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::getMaxAllocatedSize)
				.description("The maximum number of active connections that are allowed")
				.tags(tags)
				.register(REGISTRY);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + MAX_PENDING_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::getMaxPendingAcquireSize)
				.description("The maximum number of requests that will be queued while waiting for a ready connection")
				.tags(tags)
				.register(REGISTRY);
	}
}
