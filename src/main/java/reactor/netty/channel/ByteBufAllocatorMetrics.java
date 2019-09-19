/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.PooledByteBufAllocatorMetric;

/**
 * @author Violeta Georgieva
 */
final class ByteBufAllocatorMetrics {

	static void registerMetrics(String allocName, String id, ByteBufAllocatorMetric metrics) {
		String name = String.format(NAME, allocName);

		Gauge.builder(name + USED_HEAP_MEMORY, metrics, ByteBufAllocatorMetric::usedHeapMemory)
		     .description("The number of the bytes of the heap memory.")
		     .tags(ID, id)
		     .register(registry);

		Gauge.builder(name + USED_DIRECT_MEMORY, metrics, ByteBufAllocatorMetric::usedDirectMemory)
		     .description("The number of the bytes of the direct memory.")
		     .tags(ID, id)
		     .register(registry);

		if (metrics instanceof PooledByteBufAllocatorMetric) {
			PooledByteBufAllocatorMetric pooledMetrics = (PooledByteBufAllocatorMetric) metrics;

			Gauge.builder(name + HEAP_ARENAS, pooledMetrics, PooledByteBufAllocatorMetric::numHeapArenas)
			     .description("The number of heap arenas.")
			     .tags(ID, id)
			     .register(registry);

			Gauge.builder(name + DIRECT_ARENAS, pooledMetrics, PooledByteBufAllocatorMetric::numDirectArenas)
			     .description("The number of direct arenas.")
			     .tags(ID, id)
			     .register(registry);

			Gauge.builder(name + THREAD_LOCAL_CACHES, pooledMetrics, PooledByteBufAllocatorMetric::numThreadLocalCaches)
			     .description("The number of thread local caches.")
			     .tags(ID, id)
			     .register(registry);

			Gauge.builder(name + TINY_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::tinyCacheSize)
			     .description("The size of the tiny cache.")
			     .tags(ID, id)
			     .register(registry);

			Gauge.builder(name + SMALL_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::smallCacheSize)
			     .description("The size of the small cache.")
			     .tags(ID, id)
			     .register(registry);

			Gauge.builder(name + NORMAL_CACHE_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::normalCacheSize)
			     .description("The size of the normal cache.")
			     .tags(ID, id)
			     .register(registry);

			Gauge.builder(name + CHUNK_SIZE, pooledMetrics, PooledByteBufAllocatorMetric::chunkSize)
			     .description("The chunk size for an arena.")
			     .tags(ID, id)
			     .register(registry);
		}
	}

	static final MeterRegistry registry = Metrics.globalRegistry;

	static final String NAME = "reactor.netty.%s.bytebuf.allocator";

	static final String USED_HEAP_MEMORY = ".used.heap.memory";

	static final String USED_DIRECT_MEMORY = ".used.direct.memory";

	static final String HEAP_ARENAS = ".heap.arenas";

	static final String DIRECT_ARENAS = ".direct.arenas";

	static final String THREAD_LOCAL_CACHES = ".threadlocal.caches";

	static final String TINY_CACHE_SIZE = ".tiny.cache.size";

	static final String SMALL_CACHE_SIZE = ".small.cache.size";

	static final String NORMAL_CACHE_SIZE = ".normal.cache.size";

	static final String CHUNK_SIZE = ".chunk.size";

	static final String ID = "id";
}
