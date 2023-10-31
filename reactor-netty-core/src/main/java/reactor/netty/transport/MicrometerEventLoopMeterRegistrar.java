/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import reactor.netty.internal.util.MapUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.transport.EventLoopMeters.PENDING_TASKS;
import static reactor.netty.transport.EventLoopMeters.EventLoopMetersTags.NAME;

import static reactor.netty.Metrics.REGISTRY;

/**
 * Registers gauges for a given {@link EventLoop}.
 *
 * Every gauge uses thread name as tag.
 *
 * @author Pierre De Rop
 * @author Violeta Georgieva
 * @since 1.0.14
 */
final class MicrometerEventLoopMeterRegistrar {

	static final MicrometerEventLoopMeterRegistrar INSTANCE = new MicrometerEventLoopMeterRegistrar();

	private final ConcurrentMap<String, EventLoop> cache = new ConcurrentHashMap<>();

	private MicrometerEventLoopMeterRegistrar() {}

	void registerMetrics(EventLoop eventLoop) {
		if (eventLoop instanceof SingleThreadEventExecutor) {
			SingleThreadEventExecutor singleThreadEventExecutor = (SingleThreadEventExecutor) eventLoop;
			String executorName = singleThreadEventExecutor.threadProperties().name();
			MapUtils.computeIfAbsent(cache, executorName, key -> {
				Gauge.builder(PENDING_TASKS.getName(), singleThreadEventExecutor::pendingTasks)
				     .tag(NAME.asString(), executorName)
				     .register(REGISTRY);
				return eventLoop;
			});
		}
	}
}
