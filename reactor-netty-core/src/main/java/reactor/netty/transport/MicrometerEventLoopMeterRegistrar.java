/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.EVENT_LOOP_PREFIX;
import static reactor.netty.Metrics.NAME;
import static reactor.netty.Metrics.PENDING_TASKS;
import static reactor.netty.Metrics.REGISTRY;

/**
 * Registers gauges for a given {@link EventLoop}.
 *
 * Every gauge uses thread name as tag.
 *
 * @author Pierre De Rop
 * @since 1.0.14
 */
final class MicrometerEventLoopMeterRegistrar {
	static final String PENDING_TASKS_DESCRIPTION = "Event loop pending scheduled tasks.";

	final static MicrometerEventLoopMeterRegistrar INSTANCE = new MicrometerEventLoopMeterRegistrar();

	private final ConcurrentMap<String, EventLoop> cache = new ConcurrentHashMap<>();

	private MicrometerEventLoopMeterRegistrar() {}

	void registerMetrics(EventLoop eventLoop) {
		if (eventLoop instanceof SingleThreadEventExecutor) {
			SingleThreadEventExecutor singleThreadEventExecutor = (SingleThreadEventExecutor) eventLoop;
			String executorName = singleThreadEventExecutor.threadProperties().name();
			EventLoop executor = cache.get(executorName);
			if (executor != null) {
				return;
			}
			cache.computeIfAbsent(executorName, key -> {
				Gauge.builder(EVENT_LOOP_PREFIX + PENDING_TASKS, singleThreadEventExecutor::pendingTasks)
				     .description(PENDING_TASKS_DESCRIPTION)
				     .tag(NAME, executorName)
				     .register(REGISTRY);
				return eventLoop;
			});
		}
	}

}
