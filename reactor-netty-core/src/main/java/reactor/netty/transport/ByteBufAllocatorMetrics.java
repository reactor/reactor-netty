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
package reactor.netty.transport;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import reactor.netty.internal.util.MapUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.transport.ByteBufAllocatorMeters.ACTIVE_DIRECT_MEMORY;
import static reactor.netty.transport.ByteBufAllocatorMeters.ACTIVE_HEAP_MEMORY;
import static reactor.netty.transport.ByteBufAllocatorMeters.ByteBufAllocatorMetersTags.ID;
import static reactor.netty.transport.ByteBufAllocatorMeters.ByteBufAllocatorMetersTags.TYPE;
import static reactor.netty.transport.ByteBufAllocatorMeters.CHUNK_SIZE;
import static reactor.netty.transport.ByteBufAllocatorMeters.DIRECT_ARENAS;
import static reactor.netty.transport.ByteBufAllocatorMeters.HEAP_ARENAS;
import static reactor.netty.transport.ByteBufAllocatorMeters.NORMAL_CACHE_SIZE;
import static reactor.netty.transport.ByteBufAllocatorMeters.SMALL_CACHE_SIZE;
import static reactor.netty.transport.ByteBufAllocatorMeters.THREAD_LOCAL_CACHES;
import static reactor.netty.transport.ByteBufAllocatorMeters.USED_DIRECT_MEMORY;
import static reactor.netty.transport.ByteBufAllocatorMeters.USED_HEAP_MEMORY;

/**
 * Metrics related to {@link ByteBuf}.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
final class ByteBufAllocatorMetrics {

	static final ByteBufAllocatorMetrics INSTANCE = new ByteBufAllocatorMetrics();

	final ConcurrentMap<String, ByteBufAllocatorMetric> cache = new ConcurrentHashMap<>();

	private ByteBufAllocatorMetrics() {
	}

	void registerMetrics(String allocType, ByteBufAllocatorMetric metrics, ByteBufAllocator alloc) {
		MapUtils.computeIfAbsent(cache, metrics.hashCode() + "", key -> {
			Tags tags = Tags.of(ID.asString(), key, TYPE.asString(), allocType);

			Gauge.builder(USED_HEAP_MEMORY.getName(), metrics, ByteBufAllocatorMetric::usedHeapMemory)
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(USED_DIRECT_MEMORY.getName(), metrics, ByteBufAllocatorMetric::usedDirectMemory)
			     .tags(tags)
			     .register(REGISTRY);

			if (metrics instanceof PooledByteBufAllocatorMetric) {
				PooledByteBufAllocatorMetric pooledMetrics = (PooledByteBufAllocatorMetric) metrics;
				PooledByteBufAllocator pooledAlloc = (PooledByteBufAllocator) alloc;

				Gauge.builder(HEAP_ARENAS.getName(), pooledMetrics, PooledByteBufAllocatorMetric::numHeapArenas)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(DIRECT_ARENAS.getName(), pooledMetrics, PooledByteBufAllocatorMetric::numDirectArenas)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(THREAD_LOCAL_CACHES.getName(), pooledMetrics, PooledByteBufAllocatorMetric::numThreadLocalCaches)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(SMALL_CACHE_SIZE.getName(), pooledMetrics, PooledByteBufAllocatorMetric::smallCacheSize)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(NORMAL_CACHE_SIZE.getName(), pooledMetrics, PooledByteBufAllocatorMetric::normalCacheSize)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(CHUNK_SIZE.getName(), pooledMetrics, PooledByteBufAllocatorMetric::chunkSize)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(ACTIVE_HEAP_MEMORY.getName(), pooledAlloc, PooledByteBufAllocator::pinnedHeapMemory)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(ACTIVE_DIRECT_MEMORY.getName(), pooledAlloc, PooledByteBufAllocator::pinnedDirectMemory)
				     .tags(tags)
				     .register(REGISTRY);
			}

			return metrics;
		});
	}
}
