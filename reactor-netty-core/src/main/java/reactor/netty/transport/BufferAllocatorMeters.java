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

import io.micrometer.common.docs.KeyName;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.docs.DocumentedMeter;

/**
 * {@link io.netty5.buffer.api.BufferAllocator} meters.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum BufferAllocatorMeters implements DocumentedMeter {

	/**
	 * The actual bytes consumed by in-use buffers allocated from the buffer pools.
	 */
	ACTIVE_MEMORY {
		@Override
		public String getName() {
			return "reactor.netty.buffer.allocator.active.memory";
		}

		@Override
		public KeyName[] getKeyNames() {
			return BufferAllocatorMetersTags.values();
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
			return "reactor.netty.buffer.allocator.chunk.size";
		}

		@Override
		public KeyName[] getKeyNames() {
			return BufferAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of arenas.
	 */
	ARENAS {
		@Override
		public String getName() {
			return "reactor.netty.buffer.allocator.arenas";
		}

		@Override
		public KeyName[] getKeyNames() {
			return BufferAllocatorMetersTags.values();
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
			return "reactor.netty.buffer.allocator.normal.cache.size";
		}

		@Override
		public KeyName[] getKeyNames() {
			return BufferAllocatorMetersTags.values();
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
			return "reactor.netty.buffer.allocator.small.cache.size";
		}

		@Override
		public KeyName[] getKeyNames() {
			return BufferAllocatorMetersTags.values();
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
			return "reactor.netty.buffer.allocator.threadlocal.caches";
		}

		@Override
		public KeyName[] getKeyNames() {
			return BufferAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of bytes reserved by the buffer allocator.
	 */
	USED_MEMORY {
		@Override
		public String getName() {
			return "reactor.netty.buffer.allocator.used.memory";
		}

		@Override
		public KeyName[] getKeyNames() {
			return BufferAllocatorMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	};

	enum BufferAllocatorMetersTags implements KeyName {

		/**
		 * ID.
		 */
		ID {
			@Override
			public String getKeyName() {
				return "id";
			}
		},

		/**
		 * TYPE.
		 */
		TYPE {
			@Override
			public String getKeyName() {
				return "type";
			}
		}
	}
}
