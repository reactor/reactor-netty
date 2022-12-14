/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import reactor.netty.Metrics;
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;

import java.net.SocketAddress;

import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.ACTIVE_STREAMS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.ID;
import static reactor.netty.Metrics.IDLE_CONNECTIONS;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.PENDING_STREAMS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;

final class MicrometerHttp2ConnectionProviderMeterRegistrar {
	static final String ACTIVE_CONNECTIONS_DESCRIPTION =
			"The number of the connections that have been successfully acquired and are in active use";
	static final String ACTIVE_STREAMS_DESCRIPTION = "The number of the active HTTP/2 streams";
	static final String IDLE_CONNECTIONS_DESCRIPTION = "The number of the idle connections";
	static final String PENDING_STREAMS_DESCRIPTION =
			"The number of requests that are waiting for opening HTTP/2 stream";

	static final MicrometerHttp2ConnectionProviderMeterRegistrar INSTANCE =
			new MicrometerHttp2ConnectionProviderMeterRegistrar();

	private MicrometerHttp2ConnectionProviderMeterRegistrar() {
	}

	void registerMetrics(String poolName, String id, SocketAddress remoteAddress, InstrumentedPool.PoolMetrics metrics) {
		String addressAsString = Metrics.formatSocketAddress(remoteAddress);
		Tags tags = Tags.of(ID, id, REMOTE_ADDRESS, addressAsString, NAME, poolName);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::acquiredSize)
		     .description(ACTIVE_CONNECTIONS_DESCRIPTION)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + ACTIVE_STREAMS, metrics, poolMetrics -> ((Http2Pool) poolMetrics).activeStreams())
		     .description(ACTIVE_STREAMS_DESCRIPTION)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, metrics, InstrumentedPool.PoolMetrics::idleSize)
				.description(IDLE_CONNECTIONS_DESCRIPTION)
				.tags(tags)
				.register(REGISTRY);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + PENDING_STREAMS, metrics, InstrumentedPool.PoolMetrics::pendingAcquireSize)
		     .description(PENDING_STREAMS_DESCRIPTION)
		     .tags(tags)
		     .register(REGISTRY);
	}

	void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
		String addressAsString = Metrics.formatSocketAddress(remoteAddress);
		Tags tags = Tags.of(ID, id, REMOTE_ADDRESS, addressAsString, NAME, poolName);

		REGISTRY.remove(new Meter.Id(CONNECTION_PROVIDER_PREFIX + ACTIVE_CONNECTIONS, tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(CONNECTION_PROVIDER_PREFIX + ACTIVE_STREAMS, tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(CONNECTION_PROVIDER_PREFIX + IDLE_CONNECTIONS, tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(CONNECTION_PROVIDER_PREFIX + PENDING_STREAMS, tags, null, null, Meter.Type.GAUGE));
	}
}