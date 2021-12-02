/*
 * Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import reactor.pool.InstrumentedPool;

final class DelegatingConnectionPoolMetrics implements ConnectionPoolMetrics {

	private final InstrumentedPool.PoolMetrics delegate;

	DelegatingConnectionPoolMetrics(InstrumentedPool.PoolMetrics delegate) {
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

	@Override
	public int maxAllocatedSize() {
		return delegate.getMaxAllocatedSize();
	}

	@Override
	public int maxPendingAcquireSize() {
		return delegate.getMaxPendingAcquireSize();
	}
}
