/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.time.Duration;
import java.util.Objects;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.netty.ReactorNetty;

/**
 * An {@link EventLoopGroup} selector with associated
 * {@link io.netty.channel.Channel} factories.
 *
 * @author Stephane Maldini
 * @since 0.6
 */
@FunctionalInterface
public interface LoopResources extends Disposable {

	/**
	 * Default pending task log threshold, fallback to -1 (no logging).
	 */
	int DEFAULT_PENDING_TASK_LOG_THRESHOLD = Integer.parseInt(System.getProperty(
			ReactorNetty.PENDING_TASK_LOG_THRESHOLD,
			"" + -1));

	/**
	 * Default worker thread count, fallback to available processor
	 * (but with a minimum value of 4).
	 */
	int DEFAULT_IO_WORKER_COUNT = Integer.parseInt(System.getProperty(
			ReactorNetty.IO_WORKER_COUNT,
			"" + Math.max(Runtime.getRuntime().availableProcessors(), 4)));

	/**
	 * Default selector thread count, fallback to -1 (no selector thread),
	 * but fallback to 1 if pending task logging is enabled.
	 */
	int DEFAULT_IO_SELECT_COUNT = Integer.parseInt(System.getProperty(
			ReactorNetty.IO_SELECT_COUNT,
			DEFAULT_PENDING_TASK_LOG_THRESHOLD > -1 ? "" + 1 : "" + -1));

	/**
	 * Default value whether the native transport (epoll, kqueue) will be preferred,
	 * fallback it will be preferred when available.
	 */
	boolean DEFAULT_NATIVE = Boolean.parseBoolean(System.getProperty(
			ReactorNetty.NATIVE,
			"true"));

	/**
	 * Default quiet period that guarantees that the disposal of the underlying LoopResources
	 * will not happen, fallback to 2 seconds.
	 */
	long DEFAULT_SHUTDOWN_QUIET_PERIOD = Long.parseLong(System.getProperty(
			ReactorNetty.SHUTDOWN_QUIET_PERIOD,
			"" + 2));

	/**
	 * Default maximum amount of time to wait until the disposal of the underlying LoopResources
	 * regardless if a task was submitted during the quiet period, fallback to 15 seconds.
	 */
	long DEFAULT_SHUTDOWN_TIMEOUT = Long.parseLong(System.getProperty(
			ReactorNetty.SHUTDOWN_TIMEOUT,
			"" + 15));

	/**
	 * Create a delegating {@link EventLoopGroup} which reuse local event loop if already
	 * working
	 * inside one.
	 *
	 * @param group the {@link EventLoopGroup} to decorate
	 * @return a decorated {@link EventLoopGroup} that will colocate executions on the
	 * same thread stack
	 */
	static EventLoopGroup colocate(EventLoopGroup group) {
		Objects.requireNonNull(group, "group");
		return new ColocatedEventLoopGroup(group);
	}

	/**
	 * Create a simple {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories.
	 *
	 * @param prefix the event loop thread name prefix
	 * @return a new {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 */
	static LoopResources create(String prefix) {
		if (Objects.requireNonNull(prefix, "prefix").isEmpty()) {
			throw new IllegalArgumentException("Cannot use empty prefix");
		}
		return new DefaultLoopResources(prefix, DEFAULT_IO_SELECT_COUNT, DEFAULT_IO_WORKER_COUNT, true);
	}

	/**
	 * Create a simple {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories.
	 *
	 * @param prefix the event loop thread name prefix
	 * @param workerCount number of worker threads
	 * @param daemon should the thread be released on jvm shutdown
	 * @return a new {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 */
	static LoopResources create(String prefix, int workerCount, boolean daemon) {
		if (Objects.requireNonNull(prefix, "prefix").isEmpty()) {
			throw new IllegalArgumentException("Cannot use empty prefix");
		}
		if (workerCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive worker threads number, was: " + workerCount);
		}
		return new DefaultLoopResources(prefix, workerCount, daemon);
	}

	/**
	 * Create a simple {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories.
	 *
	 * @param prefix the event loop thread name prefix
	 * @param selectCount number of selector threads
	 * @param workerCount number of worker threads
	 * @param daemon should the thread be released on jvm shutdown
	 * @return a new {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 */
	static LoopResources create(String prefix, int selectCount, int workerCount, boolean daemon) {
		if (Objects.requireNonNull(prefix, "prefix").isEmpty()) {
			throw new IllegalArgumentException("Cannot use empty prefix");
		}
		if (workerCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive worker threads number, was: " + workerCount);
		}
		if (selectCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive selector threads number, was: " + selectCount);
		}
		return new DefaultLoopResources(prefix, selectCount, workerCount, daemon);
	}

	/**
	 * Create a simple {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories.
	 *
	 * @param prefix the event loop thread name prefix
	 * @param selectCount number of selector threads. When -1 is specified, no selectors threads will be created,
	 *                    and worker threads will be also used as selector threads.
	 * @param workerCount number of worker threads
	 * @param daemon should the thread be released on jvm shutdown
	 * @param colocate true means that {@link EventLoopGroup} created for clients will reuse current local event loop if already
	 *                 working inside one.
	 * @return a new {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 *
	 * @since 1.0.28
	 */
	static LoopResources create(String prefix, int selectCount, int workerCount, boolean daemon, boolean colocate) {
		if (Objects.requireNonNull(prefix, "prefix").isEmpty()) {
			throw new IllegalArgumentException("Cannot use empty prefix");
		}
		if (workerCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive worker threads number, was: " + workerCount);
		}
		if (selectCount < 1 && selectCount != -1) {
			throw new IllegalArgumentException("Must provide a strictly positive selector threads number or -1, was: " + selectCount);
		}
		return new DefaultLoopResources(prefix, selectCount, workerCount, daemon, colocate);
	}

	/**
	 * return true if {@link EventLoopGroup} should not be shutdown.
	 *
	 * @return true if {@link EventLoopGroup} should not be shutdown
	 */
	default boolean daemon() {
		return false;
	}

	/**
	 * Dispose the underlying LoopResources.
	 * This method is NOT blocking. It is implemented as fire-and-forget.
	 * Use {@link #disposeLater()} when you need to observe the final
	 * status of the operation, combined with {@link Mono#block()}
	 * if you need to synchronously wait for the underlying resources to be disposed.
	 */
	@Override
	@SuppressWarnings("FunctionalInterfaceMethodChanged")
	default void dispose() {
		//noop default
		disposeLater().subscribe();
	}

	/**
	 * Returns a Mono that triggers the disposal of the underlying LoopResources when subscribed to.
	 * The quiet period will be {@code 2s} and the timeout will be {@code 15s}
	 *
	 * @return a Mono representing the completion of the LoopResources disposal.
	 **/
	default Mono<Void> disposeLater() {
		return disposeLater(Duration.ofSeconds(DEFAULT_SHUTDOWN_QUIET_PERIOD),
				Duration.ofSeconds(DEFAULT_SHUTDOWN_TIMEOUT));
	}

	/**
	 * Returns a Mono that triggers the disposal of the underlying LoopResources when subscribed to.
	 * It is guaranteed that the disposal of the underlying LoopResources will not happen before
	 * {@code quietPeriod} is over. If a task is submitted during the {@code quietPeriod},
	 * it is guaranteed to be accepted and the {@code quietPeriod} will start over.
	 *
	 * @param quietPeriod the quiet period as described above
	 * @param timeout the maximum amount of time to wait until the disposal of the underlying
	 * LoopResources regardless if a task was submitted during the quiet period
	 * @return a Mono representing the completion of the LoopResources disposal.
	 * @since 0.9.3
	 **/
	default Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
		//noop default
		return Mono.empty();
	}

	/**
	 * Callback for a {@link Channel} selection.
	 *
	 * @param channelType the channel type
	 * @param group the source {@link EventLoopGroup} to assign a loop from
	 * @param <CHANNEL> the {@link Channel} implementation
	 * @return a {@link Channel} instance
	 */
	default <CHANNEL extends Channel> CHANNEL onChannel(Class<CHANNEL> channelType, EventLoopGroup group) {
		DefaultLoop channelFactory =
				DefaultLoopNativeDetector.INSTANCE.supportGroup(group) ?
						DefaultLoopNativeDetector.INSTANCE :
						DefaultLoopNativeDetector.NIO;

		return channelFactory.getChannel(channelType);
	}

	/**
	 * Callback for a {@link Channel} class selection.
	 *
	 * @param channelType the channel type
	 * @param group the source {@link EventLoopGroup} to assign a loop from
	 * @param <CHANNEL> the {@link Channel} implementation
	 * @return a {@link Channel} class
	 */
	default <CHANNEL extends Channel> Class<? extends CHANNEL> onChannelClass(Class<CHANNEL> channelType, EventLoopGroup group) {
		DefaultLoop channelFactory =
				DefaultLoopNativeDetector.INSTANCE.supportGroup(group) ?
						DefaultLoopNativeDetector.INSTANCE :
						DefaultLoopNativeDetector.NIO;

		return channelFactory.getChannelClass(channelType);
	}

	/**
	 * Callback for client {@link EventLoopGroup} creation.
	 *
	 * @param useNative should use native group if current environment supports it
	 * @return a new {@link EventLoopGroup}
	 */
	default EventLoopGroup onClient(boolean useNative) {
		return onServer(useNative);
	}

	/**
	 * Callback for server {@link EventLoopGroup} creation,
	 * this is the {@link EventLoopGroup} for the child channel.
	 *
	 * @param useNative should use native group if current environment supports it
	 * @return a new {@link EventLoopGroup}
	 */
	EventLoopGroup onServer(boolean useNative);

	/**
	 * Callback for server select {@link EventLoopGroup} creation,
	 * this is the {@link EventLoopGroup} for the acceptor channel.
	 *
	 * @param useNative should use native group if current environment supports it
	 * @return a new {@link EventLoopGroup}
	 */
	default EventLoopGroup onServerSelect(boolean useNative) {
		return onServer(useNative);
	}

	/**
	 * Return true if environment supports native connections.
	 *
	 * @return true if environment supports native connections
	 */
	static boolean hasNativeSupport() {
		return DefaultLoopNativeDetector.INSTANCE != DefaultLoopNativeDetector.NIO;
	}
}
