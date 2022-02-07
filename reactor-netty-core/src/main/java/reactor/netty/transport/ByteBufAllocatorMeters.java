/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.api.instrument.Gauge;
import io.micrometer.api.instrument.Meter;
import io.micrometer.api.instrument.docs.DocumentedMeter;
import io.micrometer.api.instrument.docs.TagKey;

import java.util.function.ToDoubleFunction;

/**
 * {@link io.netty.buffer.ByteBufAllocator} meters.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum ByteBufAllocatorMeters implements DocumentedMeter {

	/**
	 * The actual bytes consumed by in-use buffers allocated from direct buffer pools.
	 */
	ACTIVE_DIRECT_MEMORY {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.active.direct.memory";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The actual bytes consumed by in-use buffers allocated from heap buffer pools.
	 */
	ACTIVE_HEAP_MEMORY {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.active.heap.memory";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The chunk size for an arena.
	 */
	CHUNK_SIZE {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.chunk.size";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of direct arenas.
	 */
	DIRECT_ARENAS {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.direct.arenas";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of heap arenas.
	 */
	HEAP_ARENAS {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.heap.arenas";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The size of the normal cache.
	 */
	NORMAL_CACHE_SIZE {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.normal.cache.size";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The size of the small cache.
	 */
	SMALL_CACHE_SIZE {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.small.cache.size";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of thread local caches.
	 */
	THREAD_LOCAL_CACHES {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.threadlocal.caches";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of bytes reserved by direct buffer allocator.
	 */
	USED_DIRECT_MEMORY {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.used.direct.memory";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of bytes reserved by heap buffer allocator.
	 */
	USED_HEAP_MEMORY {
		@Override
		public String getName() {
			return "reactor.netty.bytebuf.allocator.used.heap.memory";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ByteBufAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	};

	enum ByteBufAllocatorMetersTags implements TagKey {

		/**
		 * ID.
		 */
		ID {
			@Override
			public String getKey() {
				return "id";
			}
		},

		/**
		 * TYPE.
		 */
		TYPE {
			@Override
			public String getKey() {
				return "type";
			}
		}
	}

	static <T> Gauge.Builder<T> toGaugeBuilder(DocumentedMeter meter, T obj, ToDoubleFunction<T> f) {
		return Gauge.builder(meter.getName(), obj, f);
	}
}
