/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import reactor.netty.resources.ConnectionProvider.MeterRegistrar;
import reactor.pool.PoolMetricsRecorder;

import java.net.SocketAddress;

/**
 * A {@link PoolMetricsRecorder} that bridges pending acquisition latencies to a user-provided
 * {@link MeterRegistrar}. This allows custom registrars to receive the {@code pending.connections.time}
 * data that is otherwise only available to the built-in Micrometer integration.
 *
 * @author Violeta Georgieva
 * @since 1.3.7
 */
final class MeterRegistrarPoolMetricsRecorder implements PoolMetricsRecorder {

	final MeterRegistrar registrar;
	final String poolName;
	final String id;
	final SocketAddress remoteAddress;

	MeterRegistrarPoolMetricsRecorder(MeterRegistrar registrar, String poolName, String id, SocketAddress remoteAddress) {
		this.registrar = registrar;
		this.poolName = poolName;
		this.id = id;
		this.remoteAddress = remoteAddress;
	}

	@Override
	public void recordAllocationSuccessAndLatency(long latencyMs) {
		//noop
	}

	@Override
	public void recordAllocationFailureAndLatency(long latencyMs) {
		//noop
	}

	@Override
	public void recordResetLatency(long latencyMs) {
		//noop
	}

	@Override
	public void recordDestroyLatency(long latencyMs) {
		//noop
	}

	@Override
	public void recordRecycled() {
		//noop
	}

	@Override
	public void recordLifetimeDuration(long millisecondsSinceAllocation) {
		//noop
	}

	@Override
	public void recordIdleTime(long millisecondsIdle) {
		//noop
	}

	@Override
	public void recordSlowPath() {
		//noop
	}

	@Override
	public void recordFastPath() {
		//noop
	}

	@Override
	public void recordPendingSuccessAndLatency(long latencyMs) {
		registrar.recordPendingAcquireSuccess(poolName, id, remoteAddress, latencyMs);
	}

	@Override
	public void recordPendingFailureAndLatency(long latencyMs) {
		registrar.recordPendingAcquireFailure(poolName, id, remoteAddress, latencyMs);
	}
}
