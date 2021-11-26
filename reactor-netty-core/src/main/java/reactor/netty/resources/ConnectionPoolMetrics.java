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

	/**
	 * Get the maximum number of live resources this Pool will allow.
	 * <p>
	 * A Pool might be unbounded, in which case this method returns {@code Integer.MAX_VALUE}.
	 * @return the maximum number of live resources that can be allocated by this Pool
	 * @since 1.0.14
	 */
	int maxAllocatedSize();

	/**
	 * Get the maximum number of {@code Pool.acquire()} this Pool can queue in a pending state
	 * when no available resource is immediately handy (and the Pool cannot allocate more
	 * resources).
	 * <p>
	 * A Pool pending queue might be unbounded, in which case this method returns
	 * {@code Integer.MAX_VALUE}.
	 * @return the maximum number of pending acquire that can be enqueued by this Pool
	 * @since 1.0.14
	 */
	int maxPendingAcquireSize();

}
