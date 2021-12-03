/*
 * Copyright (c) 2019-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PoolChunkListMetric;
import io.netty.buffer.PoolChunkMetric;
import io.netty.buffer.PooledByteBufAllocatorMetric;

import java.util.List;
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
	static final ByteBufAllocatorMetrics INSTANCE = new ByteBufAllocatorMetrics();

	final ConcurrentMap<String, ByteBufAllocatorMetric> cache = new ConcurrentHashMap<>();

	private ByteBufAllocatorMetrics() {
	}

	void registerMetrics(String allocType, ByteBufAllocatorMetric metrics) {
		String hash = metrics.hashCode() + "";
		ByteBufAllocatorMetric cachedMetrics = cache.get(hash);
		if (cachedMetrics != null) {
			return;
		}
		cache.computeIfAbsent(hash, key -> {
			String[] tags = new String[] {ID, key, TYPE, allocType};

			Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + USED_HEAP_MEMORY, metrics, ByteBufAllocatorMetric::usedHeapMemory)
			     .description("The number of bytes committed to heap buffer allocator.")
			     .tags(tags)
			     .register(REGISTRY);

			Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + USED_DIRECT_MEMORY, metrics, ByteBufAllocatorMetric::usedDirectMemory)
			     .description("The number of bytes committed to direct buffer allocator.")
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

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_HEAP_MEMORY, pooledMetrics.heapArenas(), this::activeMemory)
						.description("The actual bytes consumed by in-use buffers allocated from heap buffer pools.")
						.tags(tags)
						.register(REGISTRY);

				Gauge.builder(BYTE_BUF_ALLOCATOR_PREFIX + ACTIVE_DIRECT_MEMORY, pooledMetrics.directArenas(), this::activeMemory)
						.description("The actual bytes consumed by in-use buffers allocated from direct buffer pools.")
						.tags(tags)
						.register(REGISTRY);
			}

			return metrics;
		});
	}

	/**
	 * Obtains an estimate of bytes actually allocated for in-use buffers.
	 * @param arenas the list of pool arenas from where the buffers are allocated
	 */
	private double activeMemory(List<PoolArenaMetric> arenas) {
		double totalUsed = 0;
		for (PoolArenaMetric arenaMetrics : arenas) {
			for (PoolChunkListMetric arenaMetric : arenaMetrics.chunkLists()) {
				for (PoolChunkMetric chunkMetric : arenaMetric) {
					// chunkMetric.chunkSize() returns maximum of bytes that can be served out of the chunk
					// and chunkMetric.freeBytes() returns the bytes that are not yet allocated by in-use buffers
					totalUsed += chunkMetric.chunkSize() - chunkMetric.freeBytes();
				}
			}
		}
		return totalUsed;
	}
}
