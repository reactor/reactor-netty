/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
import reactor.netty.Metrics;
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;

import java.net.SocketAddress;

import static reactor.netty.Metrics.ACTIVE_STREAMS;
import static reactor.netty.Metrics.CONNECTION_PROVIDER_PREFIX;
import static reactor.netty.Metrics.ID;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.PENDING_STREAMS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;

final class MicrometerHttp2ConnectionProviderMeterRegistrar {

	static final MicrometerHttp2ConnectionProviderMeterRegistrar INSTANCE =
			new MicrometerHttp2ConnectionProviderMeterRegistrar();

	private MicrometerHttp2ConnectionProviderMeterRegistrar() {
	}

	void registerMetrics(String poolName, String id, SocketAddress remoteAddress, InstrumentedPool.PoolMetrics metrics) {
		String addressAsString = Metrics.formatSocketAddress(remoteAddress);
		String[] tags = new String[]{ID, id, REMOTE_ADDRESS, addressAsString, NAME, poolName};

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + ACTIVE_STREAMS, metrics, InstrumentedPool.PoolMetrics::acquiredSize)
		     .description("The number of the active HTTP/2 streams")
		     .tags(tags)
		     .register(REGISTRY);

		Gauge.builder(CONNECTION_PROVIDER_PREFIX + PENDING_STREAMS, metrics, InstrumentedPool.PoolMetrics::pendingAcquireSize)
		     .description("The number of requests that are waiting for opening HTTP/2 stream")
		     .tags(tags)
		     .register(REGISTRY);
	}
}