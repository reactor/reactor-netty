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
package reactor.netty.transport;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import reactor.netty.internal.util.MapUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

//import static reactor.netty.Metrics.REGISTRY;
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
 * @author Violeta Georgieva
 * @since 0.9
 */
final class ByteBufAllocatorMetrics {
	static final io.micrometer.api.instrument.MeterRegistry REGISTRY = io.micrometer.api.instrument.Metrics.globalRegistry;

	static final ByteBufAllocatorMetrics INSTANCE = new ByteBufAllocatorMetrics();

	final ConcurrentMap<String, ByteBufAllocatorMetric> cache = new ConcurrentHashMap<>();

	private ByteBufAllocatorMetrics() {
	}

	void registerMetrics(String allocType, ByteBufAllocatorMetric metrics, ByteBufAllocator alloc) {
		MapUtils.computeIfAbsent(cache, metrics.hashCode() + "", key -> {
			String[] tags = new String[] {ID.getKey(), key, TYPE.getKey(), allocType};

			ByteBufAllocatorMeters.toGaugeBuilder(USED_HEAP_MEMORY, metrics, ByteBufAllocatorMetric::usedHeapMemory)
			                      .tags(tags)
			                      .register(REGISTRY);

			ByteBufAllocatorMeters.toGaugeBuilder(USED_DIRECT_MEMORY, metrics, ByteBufAllocatorMetric::usedDirectMemory)
			                      .tags(tags)
			                      .register(REGISTRY);

			if (metrics instanceof PooledByteBufAllocatorMetric) {
				PooledByteBufAllocatorMetric pooledMetrics = (PooledByteBufAllocatorMetric) metrics;
				PooledByteBufAllocator pooledAlloc = (PooledByteBufAllocator) alloc;

				ByteBufAllocatorMeters.toGaugeBuilder(HEAP_ARENAS, pooledMetrics, PooledByteBufAllocatorMetric::numHeapArenas)
				                      .tags(tags)
				                      .register(REGISTRY);

				ByteBufAllocatorMeters.toGaugeBuilder(DIRECT_ARENAS, pooledMetrics, PooledByteBufAllocatorMetric::numDirectArenas)
				                      .tags(tags)
				                      .register(REGISTRY);

				ByteBufAllocatorMeters.toGaugeBuilder(THREAD_LOCAL_CACHES, pooledMetrics, PooledByteBufAllocatorMetric::numThreadLocalCaches)
				                      .tags(tags)
				                      .register(REGISTRY);

				ByteBufAllocatorMeters.toGaugeBuilder(SMALL_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::smallCacheSize)
				                      .tags(tags)
				                      .register(REGISTRY);

				ByteBufAllocatorMeters.toGaugeBuilder(NORMAL_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::normalCacheSize)
				                      .tags(tags)
				                      .register(REGISTRY);

				ByteBufAllocatorMeters.toGaugeBuilder(CHUNK_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::chunkSize)
				                      .tags(tags)
				                      .register(REGISTRY);

				ByteBufAllocatorMeters.toGaugeBuilder(ACTIVE_HEAP_MEMORY, pooledAlloc, PooledByteBufAllocator::pinnedHeapMemory)
				                      .tags(tags)
				                      .register(REGISTRY);

				ByteBufAllocatorMeters.toGaugeBuilder(ACTIVE_DIRECT_MEMORY, pooledAlloc, PooledByteBufAllocator::pinnedDirectMemory)
				                      .tags(tags)
				                      .register(REGISTRY);
			}

			return metrics;
		});
	}
}
