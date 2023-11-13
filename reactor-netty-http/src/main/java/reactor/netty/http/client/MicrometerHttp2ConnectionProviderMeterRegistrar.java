/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;

import java.net.SocketAddress;

import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.ACTIVE_CONNECTIONS;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.ACTIVE_STREAMS;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.Http2ConnectionProviderMetersTags.ID;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.Http2ConnectionProviderMetersTags.NAME;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.Http2ConnectionProviderMetersTags.REMOTE_ADDRESS;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.IDLE_CONNECTIONS;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.PENDING_STREAMS;
import static reactor.netty.Metrics.formatSocketAddress;

final class MicrometerHttp2ConnectionProviderMeterRegistrar {

	static final MicrometerHttp2ConnectionProviderMeterRegistrar INSTANCE =
			new MicrometerHttp2ConnectionProviderMeterRegistrar();

	private MicrometerHttp2ConnectionProviderMeterRegistrar() {
	}

	void registerMetrics(String poolName, String id, SocketAddress remoteAddress, InstrumentedPool.PoolMetrics metrics) {
		String addressAsString = formatSocketAddress(remoteAddress);
		Tags tags = Tags.of(ID.asString(), id, REMOTE_ADDRESS.asString(), addressAsString, NAME.asString(), poolName);

		Gauge.builder(ACTIVE_CONNECTIONS.getName(), metrics, InstrumentedPool.PoolMetrics::acquiredSize)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(ACTIVE_STREAMS.getName(), metrics, poolMetrics -> ((Http2Pool) poolMetrics).activeStreams())
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(IDLE_CONNECTIONS.getName(), metrics, InstrumentedPool.PoolMetrics::idleSize)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(PENDING_STREAMS.getName(), metrics, InstrumentedPool.PoolMetrics::pendingAcquireSize)
		     .tags(tags)
		     .register(REGISTRY);
	}

	void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
		String addressAsString = formatSocketAddress(remoteAddress);
		Tags tags = Tags.of(ID.asString(), id, REMOTE_ADDRESS.asString(), addressAsString, NAME.asString(), poolName);

		REGISTRY.remove(new Meter.Id(ACTIVE_CONNECTIONS.getName(), tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(ACTIVE_STREAMS.getName(), tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(IDLE_CONNECTIONS.getName(), tags, null, null, Meter.Type.GAUGE));
		REGISTRY.remove(new Meter.Id(PENDING_STREAMS.getName(), tags, null, null, Meter.Type.GAUGE));
	}
}