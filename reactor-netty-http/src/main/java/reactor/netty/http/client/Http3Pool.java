/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import reactor.netty.Connection;
import reactor.netty.internal.shaded.reactor.pool.PoolConfig;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.annotation.Nullable;

/**
 * <p>This class is intended to be used only as {@code HTTP/3} connection pool. It doesn't have generic purpose.
 *
 * @author Violeta Georgieva
 * @since 1.2.0
 */
final class Http3Pool extends Http2Pool {

	Http3Pool(PoolConfig<Connection> poolConfig, @Nullable ConnectionProvider.AllocationStrategy<?> allocationStrategy) {
		super(poolConfig, allocationStrategy);
	}

	@Override
	Slot createSlot(Connection connection) {
		return new Slot(this, connection);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	void destroyPoolableInternal(Http2PooledRef ref) {
		// If there is eviction in background, the background process will remove this connection
		if (poolConfig.evictInBackgroundInterval().isZero()) {
			// not active
			if (!ref.poolable().channel().isActive()) {
				ref.slot.invalidate();
				removeSlot(ref.slot);
			}
			// received GO_AWAY
			else if (ref.slot.goAwayReceived()) {
				ref.slot.invalidate();
				removeSlot(ref.slot);
			}
			// eviction predicate evaluates to true
			else if (testEvictionPredicate(ref.slot)) {
				//"FutureReturnValueIgnored" this is deliberate
				ref.slot.connection.channel().close();
				ref.slot.invalidate();
				removeSlot(ref.slot);
			}
		}
	}

	static final class Slot extends Http2Pool.Slot {

		Slot(Http2Pool pool, Connection connection) {
			super(pool, connection);
		}

		@Override
		void initMaxConcurrentStreams() {
			this.maxConcurrentStreams = pool.maxConcurrentStreams;
		}

		@Override
		boolean canOpenStream() {
			return true;
		}

		@Override
		boolean goAwayReceived() {
			// TODO
			return false;
		}
	}
}
