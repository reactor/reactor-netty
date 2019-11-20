/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
			InstrumentedPool<PooledConnectionProvider.PooledConnection> pool) {
		String name = String.format(NAME, poolName);

		Gauge.builder(name + TOTAL_CONNECTIONS, pool, p -> p.metrics().allocatedSize())
		     .description("The number of all connections, active or idle.")
		     .tags(ID, id, REMOTE_ADDRESS, remoteAddress)
		     .register(registry);

		Gauge.builder(name + ACTIVE_CONNECTIONS, pool, p -> p.metrics().acquiredSize())
		     .description("The number of the connections that have been successfully acquired and are in active use")
		     .tags(ID, id, REMOTE_ADDRESS, remoteAddress)
		     .register(registry);

		Gauge.builder(name + IDLE_CONNECTIONS, pool, p -> p.metrics().idleSize())
		     .description("The number of the idle connections")
		     .tags(ID, id, REMOTE_ADDRESS, remoteAddress)
		     .register(registry);

		Gauge.builder(name + PENDING_CONNECTIONS, pool, p -> p.metrics().pendingAcquireSize())
		     .description("The number of the request, that are pending acquire a connection")
		     .tags(ID, id, REMOTE_ADDRESS, remoteAddress)
		     .register(registry);
	}

	static final MeterRegistry registry = Metrics.globalRegistry;

	static final String NAME = "reactor.netty.connection.provider.%s";

	static final String TOTAL_CONNECTIONS = ".total.connections";

	static final String ACTIVE_CONNECTIONS = ".active.connections";

	static final String IDLE_CONNECTIONS = ".idle.connections";

	static final String PENDING_CONNECTIONS = ".pending.connections";

	static final String ID = "id";
}
