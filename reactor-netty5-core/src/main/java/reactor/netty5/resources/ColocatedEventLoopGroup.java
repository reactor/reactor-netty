/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.util.concurrent.EventExecutor;
import io.netty5.util.concurrent.FastThreadLocal;
import io.netty5.util.concurrent.Future;
import reactor.util.annotation.Nullable;

/**
 * Reuse local event loop if already working inside one.
 */
final class ColocatedEventLoopGroup implements EventLoopGroup, Supplier<EventLoopGroup> {

	final EventLoopGroup eventLoopGroup;
	final FastThreadLocal<EventLoop> localLoop = new FastThreadLocal<>();

	ColocatedEventLoopGroup(EventLoopGroup eventLoopGroup) {
		this.eventLoopGroup = eventLoopGroup;
		for (EventExecutor ex : eventLoopGroup) {
			if (ex instanceof EventLoop eventLoop) {
				if (eventLoop.inEventLoop()) {
					if (!localLoop.isSet()) {
						localLoop.set(eventLoop);
					}
				}
				else {
					eventLoop.submit(() -> {
						if (!localLoop.isSet()) {
							localLoop.set(eventLoop);
						}
					});
				}
			}
		}
	}

	@Override
	public EventLoopGroup get() {
		return eventLoopGroup;
	}

	@Override
	public boolean isShutdown() {
		return eventLoopGroup.isShutdown();
	}

	@Override
	public boolean isShuttingDown() {
		return eventLoopGroup.isShuttingDown();
	}

	@Override
	public Iterator<EventExecutor> iterator() {
		return eventLoopGroup.iterator();
	}

	@Override
	public EventLoop next() {
		EventLoop loop = nextInternal();
		return loop != null ? loop : eventLoopGroup.next();
	}

	@Override
	public Future<Void> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
		clean();
		return eventLoopGroup.shutdownGracefully(quietPeriod, timeout, unit);
	}

	@Override
	public Future<Void> terminationFuture() {
		return eventLoopGroup.terminationFuture();
	}

	void clean() {
		for (EventExecutor ex : eventLoopGroup) {
			ex.execute(() -> localLoop.set(null));
		}
	}

	@Nullable
	EventLoop nextInternal() {
		return localLoop.isSet() ? localLoop.get() : null;
	}
}
