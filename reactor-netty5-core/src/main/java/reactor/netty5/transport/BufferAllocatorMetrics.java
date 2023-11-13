/*
 * Copyright (c) 2019-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.transport;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.netty5.buffer.Buffer;
import io.netty5.buffer.pool.BufferAllocatorMetric;
import reactor.netty5.internal.util.MapUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty5.Metrics.REGISTRY;
import static reactor.netty5.transport.BufferAllocatorMeters.ACTIVE_MEMORY;
import static reactor.netty5.transport.BufferAllocatorMeters.BufferAllocatorMetersTags.ID;
import static reactor.netty5.transport.BufferAllocatorMeters.BufferAllocatorMetersTags.TYPE;
import static reactor.netty5.transport.BufferAllocatorMeters.CHUNK_SIZE;
import static reactor.netty5.transport.BufferAllocatorMeters.ARENAS;
import static reactor.netty5.transport.BufferAllocatorMeters.NORMAL_CACHE_SIZE;
import static reactor.netty5.transport.BufferAllocatorMeters.SMALL_CACHE_SIZE;
import static reactor.netty5.transport.BufferAllocatorMeters.THREAD_LOCAL_CACHES;
import static reactor.netty5.transport.BufferAllocatorMeters.USED_MEMORY;

/**
 * Metrics related to {@link Buffer}.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
final class BufferAllocatorMetrics {

	static final BufferAllocatorMetrics INSTANCE = new BufferAllocatorMetrics();

	final ConcurrentMap<String, BufferAllocatorMetric> cache = new ConcurrentHashMap<>();

	private BufferAllocatorMetrics() {
	}

	void registerMetrics(String allocType, BufferAllocatorMetric metrics) {
		MapUtils.computeIfAbsent(cache, metrics.hashCode() + "", key -> {
			Tags tags = Tags.of(ID.asString(), key, TYPE.asString(), allocType);

			Gauge.builder(USED_MEMORY.getName(), metrics, BufferAllocatorMetric::usedMemory)
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(ARENAS.getName(), metrics, BufferAllocatorMetric::numArenas)
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(THREAD_LOCAL_CACHES.getName(), metrics, BufferAllocatorMetric::numThreadLocalCaches)
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(SMALL_CACHE_SIZE.getName(), metrics, BufferAllocatorMetric::smallCacheSize)
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(NORMAL_CACHE_SIZE.getName(), metrics, BufferAllocatorMetric::normalCacheSize)
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(CHUNK_SIZE.getName(), metrics, BufferAllocatorMetric::chunkSize)
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(ACTIVE_MEMORY.getName(), metrics, BufferAllocatorMetric::pinnedMemory)
			     .tags(tags)
			     .register(REGISTRY);

			return metrics;
		});
	}
}
