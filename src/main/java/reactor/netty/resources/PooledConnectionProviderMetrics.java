/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import reactor.pool.InstrumentedPool;

import static reactor.netty.Metrics.REMOTE_ADDRESS;

/**
 * @author Violeta Georgieva
 */
final class PooledConnectionProviderMetrics {

	static void registerMetrics(String poolName, String id, String remoteAddress,
			InstrumentedPool.PoolMetrics metrics) {

		Gauge.builder(TOTAL_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::allocatedSize)
		     .description("The number of all connections, active or idle.")
		     .tags(ID, id, REMOTE_ADDRESS, remoteAddress, POOL_NAME, poolName)
		     .register(registry);

		Gauge.builder(ACTIVE_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::acquiredSize)
		     .description("The number of the connections that have been successfully acquired and are in active use")
		     .tags(ID, id, REMOTE_ADDRESS, remoteAddress, POOL_NAME, poolName)
		     .register(registry);

		Gauge.builder(IDLE_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::idleSize)
		     .description("The number of the idle connections")
		     .tags(ID, id, REMOTE_ADDRESS, remoteAddress, POOL_NAME, poolName)
		     .register(registry);

		Gauge.builder(PENDING_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::pendingAcquireSize)
		     .description("The number of the request, that are pending acquire a connection")
		     .tags(ID, id, REMOTE_ADDRESS, remoteAddress, POOL_NAME, poolName)
		     .register(registry);
	}

	static final MeterRegistry registry = Metrics.globalRegistry;

	static final String NAME = "reactor.netty.connection.provider";

	static final String TOTAL_CONNECTIONS = NAME + ".total.connections";

	static final String ACTIVE_CONNECTIONS = NAME + ".active.connections";

	static final String IDLE_CONNECTIONS = NAME + ".idle.connections";

	static final String PENDING_CONNECTIONS = NAME + ".pending.connections";

	static final String ID = "id";

	static final String POOL_NAME = "pool.name";
}
