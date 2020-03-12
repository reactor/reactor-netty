/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.channel;

import io.micrometer.core.instrument.Gauge;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.util.internal.PlatformDependent;

import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.BYTE_BUF_ALLOCATOR_PREFIX;
import static reactor.netty.Metrics.CHUNK_SIZE;
import static reactor.netty.Metrics.DIRECT_ARENAS;
import static reactor.netty.Metrics.HEAP_ARENAS;
import static reactor.netty.Metrics.ID;
import static reactor.netty.Metrics.NORMAL_CACHE_SIZE;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.SMALL_CACHE_SIZE;
import static reactor.netty.Metrics.THREAD_LOCAL_CACHES;
import static reactor.netty.Metrics.TINY_CACHE_SIZE;
import static reactor.netty.Metrics.TYPE;
import static reactor.netty.Metrics.USED_DIRECT_MEMORY;
import static reactor.netty.Metrics.USED_HEAP_MEMORY;

/**
 * @author Violeta Georgieva
 * @since 0.9
 */
final class ByteBufAllocatorMetrics {
	static final ByteBufAllocatorMetrics INSTANCE = new ByteBufAllocatorMetrics();

	final ConcurrentMap<String, ByteBufAllocatorMetric> cache = PlatformDependent.newConcurrentHashMap();

	private ByteBufAllocatorMetrics() {
	}

	void registerMetrics(String allocType, ByteBufAllocatorMetric metrics) {
		cache.computeIfAbsent(metrics.hashCode() + "", key -> {
			String[] tags = new String[] {ID, key, TYPE, allocType};

			Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + USED_HEAP_MEMORY, metrics, ByteBufAllocatorMetric::usedHeapMemory)
			     .description("The number of the bytes of the heap memory.")
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + USED_DIRECT_MEMORY, metrics, ByteBufAllocatorMetric::usedDirectMemory)
			     .description("The number of the bytes of the direct memory.")
			     .tags(tags)
			     .register(REGISTRY);

			if (metrics instanceof PooledByteBufAllocatorMetric) {
				PooledByteBufAllocatorMetric pooledMetrics = (PooledByteBufAllocatorMetric) metrics;

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + HEAP_ARENAS, pooledMetrics, PooledByteBufAllocatorMetric::numHeapArenas)
				     .description("The number of heap arenas.")
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + DIRECT_ARENAS, pooledMetrics, PooledByteBufAllocatorMetric::numDirectArenas)
				     .description("The number of direct arenas.")
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + THREAD_LOCAL_CACHES, pooledMetrics, PooledByteBufAllocatorMetric::numThreadLocalCaches)
				     .description("The number of thread local caches.")
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + TINY_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::tinyCacheSize)
				     .description("The size of the tiny cache.")
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + SMALL_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::smallCacheSize)
				     .description("The size of the small cache.")
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + NORMAL_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::normalCacheSize)
				     .description("The size of the normal cache.")
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + CHUNK_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::chunkSize)
				     .description("The chunk size for an arena.")
				     .tags(tags)
				     .register(REGISTRY);
			}

			return metrics;
		});
	}
}
