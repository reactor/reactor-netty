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

package reactor.netty.udp;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ReactorNetty;
import reactor.netty.resources.LoopResources;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Hold the default UDP resources
 *
 * @author Violeta Georgieva
 */
public class UdpResources implements LoopResources {

	/**
	 * Return the global UDP resources for pooling
	 *
	 * @return the global UDP resources for pooling
	 */
	public static UdpResources get() {
		return getOrCreate(null, ON_UDP_NEW, "udp");
	}

	/**
	 * Update pooling resources and return the global UDP resources
	 *
	 * @param loops the eventual new {@link LoopResources}
	 * @return the global UDP resources
	 */
	public static UdpResources set(LoopResources loops) {
		return getOrCreate(loops, ON_UDP_NEW , "udp");
	}

	/**
	 * Reset UDP resources to default and return its instance
	 *
	 * @return the global UDP resources
	 */
	public static UdpResources reset() {
		shutdown();
		return getOrCreate(null, ON_UDP_NEW, "udp");
	}

	/**
	 * Shutdown the global {@link UdpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones.
	 * This method is NOT blocking. It is implemented as fire-and-forget.
	 * Use {@link #shutdownLater()} when you need to observe the final
	 * status of the operation, combined with {@link Mono#block()}
	 * if you need to synchronously wait for the underlying resources to be disposed.
	 */
	public static void shutdown() {
		UdpResources resources = udpResources.getAndSet(null);
		if (resources != null) {
			resources._dispose();
		}
	}

	/**
	 * Prepare to shutdown the global {@link UdpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 * The quiet period will be {@code 2s} and the timeout will be {@code 15s}
	 *
	 * @return a {@link Mono} triggering the {@link #shutdown()} when subscribed to.
	 */
	public static Mono<Void> shutdownLater() {
		return shutdownLater(Duration.ofSeconds(LoopResources.DEFAULT_SHUTDOWN_QUIET_PERIOD),
				Duration.ofSeconds(LoopResources.DEFAULT_SHUTDOWN_TIMEOUT));
	}

	/**
	 * Prepare to shutdown the global {@link UdpResources} without resetting them,
	 * effectively cleaning up associated resources without creating new ones. This only
	 * occurs when the returned {@link Mono} is subscribed to.
	 * It is guaranteed that the disposal of the underlying LoopResources will not happen before
	 * {@code quietPeriod} is over. If a task is submitted during the {@code quietPeriod},
	 * it is guaranteed to be accepted and the {@code quietPeriod} will start over.
	 *
	 * @param quietPeriod the quiet period as described above
	 * @param timeout the maximum amount of time to wait until the disposal of the underlying
	 *                LoopResources regardless if a task was submitted during the quiet period
	 * @return a {@link Mono} triggering the {@link #shutdown()} when subscribed to.
	 * @since 0.9.3
	 */
	public static Mono<Void> shutdownLater(Duration quietPeriod, Duration timeout) {
		return Mono.defer(() -> {
			UdpResources resources = udpResources.getAndSet(null);
			if (resources != null) {
				return resources._disposeLater(quietPeriod, timeout);
			}
			return Mono.empty();
		});
	}

	final LoopResources defaultLoops;

	protected UdpResources(LoopResources defaultLoops) {
		this.defaultLoops = defaultLoops;
	}

	/**
	 * Dispose underlying resources
	 */
	protected void _dispose(){
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
	 *                LoopResources regardless if a task was submitted during the quiet period
	 * @return the Mono that represents the end of disposal
	 */
	protected Mono<Void> _disposeLater(Duration quietPeriod, Duration timeout) {
		return defaultLoops.disposeLater(quietPeriod, timeout);
	}

	@Override
	public boolean isDisposed() {
		return defaultLoops.isDisposed();
	}

	@Override
	public Class<? extends Channel> onChannel(EventLoopGroup group) {
		return defaultLoops.onChannel(group);
	}

	@Override
	public EventLoopGroup onClient(boolean useNative) {
		return defaultLoops.onClient(useNative);
	}

	@Override
	public Class<? extends DatagramChannel> onDatagramChannel(EventLoopGroup group) {
		return defaultLoops.onDatagramChannel(group);
	}

	@Override
	public EventLoopGroup onServer(boolean useNative) {
		return defaultLoops.onServer(useNative);
	}

	@Override
	public Class<? extends ServerChannel> onServerChannel(EventLoopGroup group) {
		return defaultLoops.onServerChannel(group);
	}

	@Override
	public EventLoopGroup onServerSelect(boolean useNative) {
		return defaultLoops.onServerSelect(useNative);
	}

	@Override
	public boolean preferNative() {
		return defaultLoops.preferNative();
	}

	@Override
	public boolean daemon() {
		return defaultLoops.daemon();
	}

	/**
	 * Safely check if existing resource exist and proceed to update/cleanup if new
	 * resources references are passed.
	 *
	 * @param loops the eventual new {@link LoopResources}
	 * @param onNew a {@link UdpResources} factory
	 * @param name a name for resources
	 *
	 * @return an existing or new {@link UdpResources}
	 */
	protected static UdpResources getOrCreate(@Nullable LoopResources loops,
			Function<LoopResources, UdpResources> onNew, String name) {
		UdpResources update;
		for (; ; ) {
			UdpResources resources = udpResources.get();
			if (resources == null || loops != null) {
				update = create(resources, loops, name, onNew);
				if (udpResources.compareAndSet(resources, update)) {
					if(resources != null) {
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

	static final Logger                                log = Loggers.getLogger(UdpResources.class);
	static final AtomicReference<UdpResources>         udpResources;
	static final Function<LoopResources, UdpResources> ON_UDP_NEW;

	/**
	 * Default worker thread count, fallback to available processor
	 * (but with a minimum value of 4)
	 */
	static final int DEFAULT_UDP_THREAD_COUNT = Integer.parseInt(System.getProperty(
			ReactorNetty.UDP_IO_THREAD_COUNT,
			"" + Schedulers.DEFAULT_POOL_SIZE));

	static {
		ON_UDP_NEW = UdpResources::new;
		udpResources  = new AtomicReference<>();
	}

	static <T extends UdpResources> T create(@Nullable T previous,
			@Nullable LoopResources loops,
			String name,
			Function<LoopResources, T> onNew) {
		if (previous == null) {
			loops = loops == null ? LoopResources.create(name, DEFAULT_UDP_THREAD_COUNT, true) : loops;
		}
		else {
			loops = loops == null ? previous.defaultLoops : loops;
		}
		return onNew.apply(loops);
	}
}
