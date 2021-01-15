/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.quic;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import reactor.core.publisher.Mono;
import reactor.netty.resources.LoopResources;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Hold the default QUIC resources
 *
 * @author Violeta Georgieva
 */
public class QuicResources implements LoopResources {

	/**
	 * Return the global QUIC resources for loops
	 *
	 * @return the global QUIC resources for loops
	 */
	public static QuicResources get() {
		return getOrCreate(null, ON_QUIC_NEW, "quic");
	}

	/**
	 * Reset QUIC resources to default and return its instance
	 *
	 * @return the global QUIC resources
	 */
	public static QuicResources reset() {
		disposeLoops();
		return getOrCreate(null, ON_QUIC_NEW, "quic");
	}

	/**
	 * Update event loops resources and return the global QUIC resources.
	 * Note: The previous {@link LoopResources} will be disposed.
	 *
	 * @param loops a new {@link LoopResources} to replace the current
	 * @return the global QUIC resources
	 */
	public static QuicResources set(LoopResources loops) {
		return getOrCreate(loops, ON_QUIC_NEW, "quic");
	}

	/**
	 * Shutdown the global {@link QuicResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones.
	 * This method is NOT blocking. It is implemented as fire-and-forget.
	 * Use {@link #disposeLoopsLater()} when you need to observe the final
	 * status of the operation, combined with {@link Mono#block()}
	 * if you need to synchronously wait for the underlying resources to be disposed.
	 */
	public static void disposeLoops() {
		QuicResources resources = quicResources.getAndSet(null);
		if (resources != null) {
			resources._dispose();
		}
	}

	/**
	 * Prepare to shutdown the global {@link QuicResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 * The quiet period will be {@code 2s} and the timeout will be {@code 15s}
	 *
	 * @return a {@link Mono} triggering the {@link #disposeLoops()} when subscribed to.
	 */
	public static Mono<Void> disposeLoopsLater() {
		return disposeLoopsLater(Duration.ofSeconds(LoopResources.DEFAULT_SHUTDOWN_QUIET_PERIOD),
				Duration.ofSeconds(LoopResources.DEFAULT_SHUTDOWN_TIMEOUT));
	}

	/**
	 * Prepare to shutdown the global {@link QuicResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 * It is guaranteed that the disposal of the underlying LoopResources will not happen before
	 * {@code quietPeriod} is over. If a task is submitted during the {@code quietPeriod},
	 * it is guaranteed to be accepted and the {@code quietPeriod} will start over.
	 *
	 * @param quietPeriod the quiet period as described above
	 * @param timeout the maximum amount of time to wait until the disposal of the underlying
	 * LoopResources regardless if a task was submitted during the quiet period
	 * @return a {@link Mono} triggering the {@link #disposeLoops()} when subscribed to.
	 * @since 0.9.3
	 */
	public static Mono<Void> disposeLoopsLater(Duration quietPeriod, Duration timeout) {
		requireNonNull(quietPeriod, "quietPeriod");
		requireNonNull(timeout, "timeout");
		return Mono.defer(() -> {
			QuicResources resources = quicResources.getAndSet(null);
			if (resources != null) {
				return resources._disposeLater(quietPeriod, timeout);
			}
			return Mono.empty();
		});
	}

	final LoopResources defaultLoops;

	protected QuicResources(LoopResources defaultLoops) {
		this.defaultLoops = defaultLoops;
	}

	@Override
	public boolean daemon() {
		return defaultLoops.daemon();
	}

	/**
	 * This has a {@code NOOP} implementation by default in order to prevent unintended disposal of
	 * the global QUIC resources which has a longer lifecycle than regular {@link LoopResources}.
	 * If a disposal of the global QUIC resources is needed, {@link #disposeLoops()} should be used instead.
	 */
	@Override
	public void dispose() {
		//noop on global by default
	}

	/**
	 * This has a {@code NOOP} implementation by default in order to prevent unintended disposal of
	 * the global QUIC resources which has a longer lifecycle than regular {@link LoopResources}.
	 * If a disposal of the global QUIC resources is needed, {@link #disposeLoopsLater()} should be used instead.
	 */
	@Override
	public Mono<Void> disposeLater() {
		//noop on global by default
		return Mono.empty();
	}

	/**
	 * This has a {@code NOOP} implementation by default in order to prevent unintended disposal of
	 * the global QUIC resources which has a longer lifecycle than regular {@link LoopResources}.
	 * If a disposal of the global QUIC resources is needed, {@link #disposeLoopsLater(Duration, Duration)}
	 * should be used instead.
	 */
	@Override
	public Mono<Void> disposeLater(Duration quietPeriod, Duration timeout) {
		//noop on global by default
		return Mono.empty();
	}

	@Override
	public boolean isDisposed() {
		return defaultLoops.isDisposed();
	}

	@Override
	public <CHANNEL extends Channel> CHANNEL onChannel(Class<CHANNEL> channelType, EventLoopGroup group) {
		requireNonNull(channelType, "channelType");
		requireNonNull(group, "group");
		return defaultLoops.onChannel(channelType, group);
	}

	@Override
	public <CHANNEL extends Channel> Class<? extends CHANNEL> onChannelClass(Class<CHANNEL> channelType, EventLoopGroup group) {
		requireNonNull(channelType, "channelType");
		requireNonNull(group, "group");
		return defaultLoops.onChannelClass(channelType, group);
	}

	@Override
	public EventLoopGroup onClient(boolean useNative) {
		return defaultLoops.onClient(useNative);
	}

	@Override
	public EventLoopGroup onServer(boolean useNative) {
		return defaultLoops.onServer(useNative);
	}

	@Override
	public EventLoopGroup onServerSelect(boolean useNative) {
		return defaultLoops.onServerSelect(useNative);
	}

	/**
	 * Dispose underlying resources
	 */
	protected void _dispose() {
		defaultLoops.dispose();
	}

	/**
	 * Dispose underlying resources in a listenable fashion.
	 * It is guaranteed that the disposal of the underlying LoopResources will not happen before
	 * {@code quietPeriod} is over. If a task is submitted during the {@code quietPeriod},
	 * it is guaranteed to be accepted and the {@code quietPeriod} will start over.
	 *
	 * @param quietPeriod the quiet period as described above
	 * @param timeout the maximum amount of time to wait until the disposal of the underlying
	 * LoopResources regardless if a task was submitted during the quiet period
	 * @return the Mono that represents the end of disposal
	 */
	protected Mono<Void> _disposeLater(Duration quietPeriod, Duration timeout) {
		return defaultLoops.disposeLater(quietPeriod, timeout);
	}

	/**
	 * Safely check if existing resource exist and proceed to update/cleanup if new
	 * resources references are passed.
	 *
	 * @param loops the eventual new {@link LoopResources}
	 * @param onNew a {@link QuicResources} factory
	 * @param name a name for resources
	 * @return an existing or new {@link QuicResources}
	 */
	protected static QuicResources getOrCreate(@Nullable LoopResources loops,
	                                           Function<LoopResources, QuicResources> onNew, String name) {
		QuicResources update;
		for (;;) {
			QuicResources resources = quicResources.get();
			if (resources == null || loops != null) {
				update = create(resources, loops, name, onNew);
				if (quicResources.compareAndSet(resources, update)) {
					if (resources != null) {
						if (log.isWarnEnabled()) {
							log.warn("[{}] resources will use a new LoopResources: {}," +
									"the previous LoopResources will be disposed", name, loops);
						}
						resources.defaultLoops.dispose();
					}
					else {
						String loopType = loops == null ? "default" : "provided";
						if (log.isDebugEnabled()) {
							log.debug("[{}] resources will use the {} LoopResources: {}", name, loopType, update.defaultLoops);
						}
					}
					return update;
				}
				else {
					update._dispose();
				}
			}
			else {
				return resources;
			}
		}
	}

	static <T extends QuicResources> T create(@Nullable T previous,
	                                          @Nullable LoopResources loops,
	                                          String name,
	                                          Function<LoopResources, T> onNew) {
		if (previous == null) {
			loops = loops == null ? LoopResources.create(name, Runtime.getRuntime().availableProcessors(), true) : loops;
		}
		else {
			loops = loops == null ? previous.defaultLoops : loops;
		}
		return onNew.apply(loops);
	}

	/**
	 * Default worker thread count, fallback to available processor
	 * (but with a minimum value of 4)
	 */
	//	static final int DEFAULT_QUIC_THREAD_COUNT = Integer.parseInt(System.getProperty(
	//			ReactorNetty.QUIC_IO_THREAD_COUNT,
	//			"" + Schedulers.DEFAULT_POOL_SIZE));

	static final Logger                                 log = Loggers.getLogger(QuicResources.class);

	static final Function<LoopResources, QuicResources> ON_QUIC_NEW;

	static final AtomicReference<QuicResources>         quicResources;

	static {
		ON_QUIC_NEW = QuicResources::new;
		quicResources = new AtomicReference<>();
	}
}
