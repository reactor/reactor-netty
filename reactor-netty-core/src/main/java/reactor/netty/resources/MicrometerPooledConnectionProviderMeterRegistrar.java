/*
 * Copyright (c) 2019-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import reactor.netty.Metrics;
import reactor.pool.InstrumentedPool;

import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.resources.ConnectionProviderMeters.ACTIVE_CONNECTIONS;
import static reactor.netty.resources.ConnectionProviderMeters.ConnectionProviderMetersTags.ID;
import static reactor.netty.resources.ConnectionProviderMeters.ConnectionProviderMetersTags.NAME;
import static reactor.netty.resources.ConnectionProviderMeters.ConnectionProviderMetersTags.REMOTE_ADDRESS;
import static reactor.netty.resources.ConnectionProviderMeters.IDLE_CONNECTIONS;
import static reactor.netty.resources.ConnectionProviderMeters.MAX_CONNECTIONS;
import static reactor.netty.resources.ConnectionProviderMeters.MAX_PENDING_CONNECTIONS;
import static reactor.netty.resources.ConnectionProviderMeters.PENDING_CONNECTIONS;
import static reactor.netty.resources.ConnectionProviderMeters.TOTAL_CONNECTIONS;

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
		Tags tags = Tags.of(ID.asString(), id, REMOTE_ADDRESS.asString(), addressAsString, NAME.asString(), poolName);
		Gauge.builder(TOTAL_CONNECTIONS.getName(), metrics, InstrumentedPool.PoolMetrics::allocatedSize)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(ACTIVE_CONNECTIONS.getName(), metrics, InstrumentedPool.PoolMetrics::acquiredSize)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(IDLE_CONNECTIONS.getName(), metrics, InstrumentedPool.PoolMetrics::idleSize)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(PENDING_CONNECTIONS.getName(), metrics, InstrumentedPool.PoolMetrics::pendingAcquireSize)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(MAX_CONNECTIONS.getName(), metrics, InstrumentedPool.PoolMetrics::getMaxAllocatedSize)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(MAX_PENDING_CONNECTIONS.getName(), metrics, InstrumentedPool.PoolMetrics::getMaxPendingAcquireSize)
		     .tags(tags)
		     .register(REGISTRY);
	}

	void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
		String addressAsString = Metrics.formatSocketAddress(remoteAddress);
		Tags tags = Tags.of(ID.asString(), id, REMOTE_ADDRESS.asString(), addressAsString, NAME.asString(), poolName);

		REGISTRY.remove(new Meter.Id(TOTAL_CONNECTIONS.getName(), tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(ACTIVE_CONNECTIONS.getName(), tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(IDLE_CONNECTIONS.getName(), tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(PENDING_CONNECTIONS.getName(), tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(MAX_CONNECTIONS.getName(), tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(MAX_PENDING_CONNECTIONS.getName(), tags, null, null, Meter.Type.GAUGE));
	}
}
