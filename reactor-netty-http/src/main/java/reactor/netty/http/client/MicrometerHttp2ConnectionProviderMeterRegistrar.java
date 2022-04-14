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
import io.micrometer.core.instrument.Tags;
import reactor.netty.Metrics;
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;

import java.net.SocketAddress;

import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.ACTIVE_STREAMS;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.Http2ConnectionProviderMetersTags.ID;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.Http2ConnectionProviderMetersTags.NAME;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.Http2ConnectionProviderMetersTags.REMOTE_ADDRESS;
import static reactor.netty.http.client.Http2ConnectionProviderMeters.PENDING_STREAMS;

final class MicrometerHttp2ConnectionProviderMeterRegistrar {

	static final MicrometerHttp2ConnectionProviderMeterRegistrar INSTANCE =
			new MicrometerHttp2ConnectionProviderMeterRegistrar();

	private MicrometerHttp2ConnectionProviderMeterRegistrar() {
	}

	void registerMetrics(String poolName, String id, SocketAddress remoteAddress, InstrumentedPool.PoolMetrics metrics) {
		String addressAsString = Metrics.formatSocketAddress(remoteAddress);
		Tags tags = Tags.of(ID.getKeyName(), id, REMOTE_ADDRESS.getKeyName(), addressAsString, NAME.getKeyName(), poolName);

		Gauge.builder(ACTIVE_STREAMS.getName(), metrics, InstrumentedPool.PoolMetrics::acquiredSize)
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(PENDING_STREAMS.getName(), metrics, InstrumentedPool.PoolMetrics::pendingAcquireSize)
		     .tags(tags)
		     .register(REGISTRY);
	}
}