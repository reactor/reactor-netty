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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * Reuse local event loop if already working inside one.
 */
final class ColocatedEventLoopGroup implements EventLoopGroup, Supplier<EventLoopGroup> {

	final EventLoopGroup eventLoopGroup;
	final FastThreadLocal<EventLoop> localLoop = new FastThreadLocal<>();

	@SuppressWarnings("FutureReturnValueIgnored")
	ColocatedEventLoopGroup(EventLoopGroup eventLoopGroup) {
		this.eventLoopGroup = eventLoopGroup;
		for (EventExecutor ex : eventLoopGroup) {
			if (ex instanceof EventLoop) {
				//"FutureReturnValueIgnored" this is deliberate
				ex.submit(() -> {
					if (!localLoop.isSet()) {
						localLoop.set((EventLoop) ex);
					}
				});
			}
		}
	}

	@Override
	public EventLoop next() {
		if (localLoop.isSet()) {
			return localLoop.get();
		}
		return eventLoopGroup.next();
	}

	@Override
	public ChannelFuture register(Channel channel) {
		return next().register(channel);
	}

	@Override
	public ChannelFuture register(ChannelPromise promise) {
		return next().register(promise);
	}

	@Deprecated
	@Override
	public ChannelFuture register(Channel channel, ChannelPromise promise) {
		return next().register(channel, promise);
	}

	@Override
	public boolean isShuttingDown() {
		return eventLoopGroup.isShuttingDown();
	}

	@Override
	public io.netty.util.concurrent.Future<?> shutdownGracefully() {
		clean();
		return eventLoopGroup.shutdownGracefully();
	}

	@Override
	public io.netty.util.concurrent.Future<?> shutdownGracefully(long quietPeriod,
			long timeout,
			TimeUnit unit) {
		clean();
		return eventLoopGroup.shutdownGracefully(quietPeriod, timeout, unit);
	}

	void clean() {
		for (EventExecutor ex : eventLoopGroup) {
			ex.execute(() -> localLoop.set(null));
		}
	}

	@Override
	public io.netty.util.concurrent.Future<?> terminationFuture() {
		return eventLoopGroup.terminationFuture();
	}

	@Deprecated
	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void shutdown() {
		//"FutureReturnValueIgnored" this is deliberate
		shutdownGracefully();
	}

	@Override
	@Deprecated
	public List<Runnable> shutdownNow() {
		clean();
		return eventLoopGroup.shutdownNow();
	}

	@Override
	public Iterator<EventExecutor> iterator() {
		return eventLoopGroup.iterator();
	}

	@Override
	public io.netty.util.concurrent.Future<?> submit(Runnable task) {
		return next().submit(task);
	}

	@Override
	public <T> io.netty.util.concurrent.Future<T> submit(Runnable task, T result) {
		return next().submit(task, result);
	}

	@Override
	public <T> io.netty.util.concurrent.Future<T> submit(Callable<T> task) {
		return next().submit(task);
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
		return next().schedule(command, delay, unit);
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable,
			long delay,
			TimeUnit unit) {
		return next().schedule(callable, delay, unit);
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
			long initialDelay,
			long period,
			TimeUnit unit) {
		return next().scheduleAtFixedRate(command, initialDelay, period, unit);
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
			long initialDelay,
			long delay,
			TimeUnit unit) {
		return next().scheduleWithFixedDelay(command, initialDelay, delay, unit);
	}

	@Override
	public boolean isShutdown() {
		return eventLoopGroup.isShutdown();
	}

	@Override
	public boolean isTerminated() {
		return eventLoopGroup.isTerminated();
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit)
			throws InterruptedException {
		return eventLoopGroup.awaitTermination(timeout, unit);
	}

	@Override
	public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
			throws InterruptedException {
		return next().invokeAll(tasks);
	}

	@Override
	public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
			long timeout,
			TimeUnit unit) throws InterruptedException {
		return next().invokeAll(tasks, timeout, unit);
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
			throws InterruptedException, ExecutionException {
		return next().invokeAny(tasks);
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
			long timeout,
			TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
		return next().invokeAny(tasks, timeout, unit);
	}

	@Override
	public void execute(Runnable command) {
		next().execute(command);
	}

	@Override
	public EventLoopGroup get() {
		return eventLoopGroup;
	}


}
