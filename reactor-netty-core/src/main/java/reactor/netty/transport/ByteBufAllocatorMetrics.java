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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import reactor.netty.internal.util.MapUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.ACTIVE_DIRECT_MEMORY;
import static reactor.netty.Metrics.ACTIVE_HEAP_MEMORY;
import static reactor.netty.Metrics.BYTE_BUF_ALLOCATOR_PREFIX;
import static reactor.netty.Metrics.CHUNK_SIZE;
import static reactor.netty.Metrics.DIRECT_ARENAS;
import static reactor.netty.Metrics.HEAP_ARENAS;
import static reactor.netty.Metrics.ID;
import static reactor.netty.Metrics.NORMAL_CACHE_SIZE;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.SMALL_CACHE_SIZE;
import static reactor.netty.Metrics.THREAD_LOCAL_CACHES;
import static reactor.netty.Metrics.TYPE;
import static reactor.netty.Metrics.USED_DIRECT_MEMORY;
import static reactor.netty.Metrics.USED_HEAP_MEMORY;

/**
 * @author Violeta Georgieva
 * @since 0.9
 */
final class ByteBufAllocatorMetrics {
	static final String CHUNK_SIZE_DESCRIPTION = "The chunk size for an arena.";
	static final String DIRECT_ARENAS_DESCRIPTION = "The number of direct arenas.";
	static final String HEAP_ARENAS_DESCRIPTION = "The number of heap arenas.";
	static final String NORMAL_CACHE_SIZE_DESCRIPTION = "The size of the normal cache.";
	static final String SMALL_CACHE_SIZE_DESCRIPTION = "The size of the small cache.";
	static final String THREAD_LOCAL_CACHES_DESCRIPTION = "The number of thread local caches.";
	static final String USED_DIRECT_MEMORY_DESCRIPTION = "The number of bytes reserved by direct buffer allocator.";
	static final String USED_HEAP_MEMORY_DESCRIPTION = "The number of bytes reserved by heap buffer allocator.";
	static final String ACTIVE_DIRECT_MEMORY_DESCRIPTION = "The actual bytes consumed by in-use buffers allocated from heap buffer pools.";
	static final String ACTIVE_HEAP_MEMORY_DESCRIPTION = "The actual bytes consumed by in-use buffers allocated from direct buffer pools.";

	static final ByteBufAllocatorMetrics INSTANCE = new ByteBufAllocatorMetrics();

	final ConcurrentMap<String, ByteBufAllocatorMetric> cache = new ConcurrentHashMap<>();

	private ByteBufAllocatorMetrics() {
	}

	void registerMetrics(String allocType, ByteBufAllocatorMetric metrics, ByteBufAllocator alloc) {
		MapUtils.computeIfAbsent(cache, metrics.hashCode() + "", key -> {
			Tags tags = Tags.of(ID, key, TYPE, allocType);

			Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + USED_HEAP_MEMORY, metrics, ByteBufAllocatorMetric::usedHeapMemory)
			     .description(USED_HEAP_MEMORY_DESCRIPTION)
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + USED_DIRECT_MEMORY, metrics, ByteBufAllocatorMetric::usedDirectMemory)
			     .description(USED_DIRECT_MEMORY_DESCRIPTION)
			     .tags(tags)
			     .register(REGISTRY);

			if (metrics instanceof PooledByteBufAllocatorMetric) {
				PooledByteBufAllocatorMetric pooledMetrics = (PooledByteBufAllocatorMetric) metrics;
				PooledByteBufAllocator pooledAlloc = (PooledByteBufAllocator) alloc;

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + HEAP_ARENAS, pooledMetrics, PooledByteBufAllocatorMetric::numHeapArenas)
				     .description(HEAP_ARENAS_DESCRIPTION)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + DIRECT_ARENAS, pooledMetrics, PooledByteBufAllocatorMetric::numDirectArenas)
				     .description(DIRECT_ARENAS_DESCRIPTION)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + THREAD_LOCAL_CACHES, pooledMetrics, PooledByteBufAllocatorMetric::numThreadLocalCaches)
				     .description(THREAD_LOCAL_CACHES_DESCRIPTION)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + SMALL_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::smallCacheSize)
				     .description(SMALL_CACHE_SIZE_DESCRIPTION)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + NORMAL_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::normalCacheSize)
				     .description(NORMAL_CACHE_SIZE_DESCRIPTION)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + CHUNK_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::chunkSize)
				     .description(CHUNK_SIZE_DESCRIPTION)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_HEAP_MEMORY, pooledAlloc, PooledByteBufAllocator::pinnedHeapMemory)
				     .description(ACTIVE_HEAP_MEMORY_DESCRIPTION)
				     .tags(tags)
				     .register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_DIRECT_MEMORY, pooledAlloc, PooledByteBufAllocator::pinnedDirectMemory)
				     .description(ACTIVE_DIRECT_MEMORY_DESCRIPTION)
				     .tags(tags)
				     .register(REGISTRY);
			}

			return metrics;
		});
	}
}
