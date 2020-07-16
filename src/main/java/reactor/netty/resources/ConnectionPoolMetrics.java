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
package reactor.netty.resources;

import reactor.pool.InstrumentedPool;

public interface ConnectionPoolMetrics {

	/**
	 * Measure the current number of resources that have been successfully
	 * acquired and are in active use.
	 *
	 * @return the number of acquired resources
	 */
	int acquiredSize();

	/**
	 * Measure the current number of allocated resources in the pool, acquired
	 * or idle.
	 *
	 * @return the total number of allocated resources managed by the pool
	 */
	int allocatedSize();

	/**
	 * Measure the current number of idle resources in the pool.
	 * <p>
	 * Note that some resources might be lazily evicted when they're next considered
	 * for an incoming acquire call. Such resources would still count
	 * towards this method.
	 *
	 * @return the number of idle resources
	 */
	int idleSize();

	/**
	 * Measure the current number of "pending" acquire Monos in the  Pool.
	 * <p>
	 * An acquire is in the pending state when it is attempted at a point when no idle
	 * resource is available in the pool, and no new resource can be created.
	 *
	 * @return the number of pending acquire
	 */
	int pendingAcquireSize();

	class DelegatingConnectionPoolMetrics implements ConnectionPoolMetrics {

		private final InstrumentedPool.PoolMetrics delegate;

		public DelegatingConnectionPoolMetrics(InstrumentedPool.PoolMetrics delegate) {
			this.delegate = delegate;
		}

		@Override
		public int acquiredSize() {
			return delegate.acquiredSize();
		}

		@Override
		public int allocatedSize() {
			return delegate.allocatedSize();
		}

		@Override
		public int idleSize() {
			return delegate.idleSize();
		}

		@Override
		public int pendingAcquireSize() {
			return delegate.pendingAcquireSize();
		}
	}

}
