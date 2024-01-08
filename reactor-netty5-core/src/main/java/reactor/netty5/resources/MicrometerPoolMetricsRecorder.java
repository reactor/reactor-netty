/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.resources;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import reactor.core.Disposable;
import reactor.pool.PoolMetricsRecorder;

import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import static reactor.netty5.Metrics.ERROR;
import static reactor.netty5.Metrics.REGISTRY;
import static reactor.netty5.Metrics.SUCCESS;
import static reactor.netty5.Metrics.formatSocketAddress;
import static reactor.netty5.resources.ConnectionProviderMeters.PENDING_CONNECTIONS_TIME;
import static reactor.netty5.resources.ConnectionProviderMeters.PendingConnectionsTimeTags.ID;
import static reactor.netty5.resources.ConnectionProviderMeters.PendingConnectionsTimeTags.NAME;
import static reactor.netty5.resources.ConnectionProviderMeters.PendingConnectionsTimeTags.REMOTE_ADDRESS;
import static reactor.netty5.resources.ConnectionProviderMeters.PendingConnectionsTimeTags.STATUS;

final class MicrometerPoolMetricsRecorder implements Disposable, PoolMetricsRecorder {

	final Timer pendingSuccessTimer;
	final Timer pendingErrorTimer;

	MicrometerPoolMetricsRecorder(String id, String poolName, SocketAddress remoteAddress) {
		pendingSuccessTimer = buildTimer(id, poolName, remoteAddress, SUCCESS);
		pendingErrorTimer = buildTimer(id, poolName, remoteAddress, ERROR);
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
		pendingSuccessTimer.record(latencyMs, TimeUnit.MILLISECONDS);
	}

	@Override
	public void recordPendingFailureAndLatency(long latencyMs) {
		pendingErrorTimer.record(latencyMs, TimeUnit.MILLISECONDS);
	}

	@Override
	public void dispose() {
		REGISTRY.remove(pendingSuccessTimer);
		REGISTRY.remove(pendingErrorTimer);
	}

	static Timer buildTimer(String id, String poolName, SocketAddress remoteAddress, String status) {
		return Timer.builder(PENDING_CONNECTIONS_TIME.getName())
		            .tags(Tags.of(ID.asString(), id, REMOTE_ADDRESS.asString(), formatSocketAddress(remoteAddress),
		                    NAME.asString(), poolName, STATUS.asString(), status))
		            .register(REGISTRY);
	}
}