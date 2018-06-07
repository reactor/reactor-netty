/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.resources;

import java.util.Objects;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.netty.SystemPropertiesNames;

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
	 * Default worker thread count, fallback to available processor
	 * (but with a minimum value of 4)
	 */
	int DEFAULT_IO_WORKER_COUNT = Integer.parseInt(System.getProperty(
			SystemPropertiesNames.IO_WORKER_COUNT,
			"" + Math.max(Runtime.getRuntime()
			            .availableProcessors(), 4)));
	/**
	 * Default selector thread count, fallback to -1 (no selector thread)
	 */
	int DEFAULT_IO_SELECT_COUNT = Integer.parseInt(System.getProperty(
			SystemPropertiesNames.IO_SELECT_COUNT,
			"" + -1));

	/**
	 * Default value whether the native transport (epoll, kqueue) will be preferred,
	 * fallback it will be preferred when available
	 */
	boolean DEFAULT_NATIVE = Boolean.parseBoolean(System.getProperty(
			SystemPropertiesNames.NATIVE,
			"true"));

	/**
	 * Create a delegating {@link EventLoopGroup} which reuse local event loop if already
	 * working
	 * inside one.
	 *
	 * @param group the {@link EventLoopGroup} to decorate
	 *
	 * @return a decorated {@link EventLoopGroup} that will colocate executions on the
	 * same thread stack
	 */
	static EventLoopGroup colocate(EventLoopGroup group) {
		return new ColocatedEventLoopGroup(group);
	}

	/**
	 * Create a simple {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 *
	 * @param prefix the event loop thread name prefix
	 * @param workerCount number of worker threads
	 * @param daemon should the thread be released on jvm shutdown
	 *
	 * @return a new {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 */
	static LoopResources create(String prefix, int workerCount, boolean daemon) {
		if (workerCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive " + "worker threads number, " + "was: " + workerCount);
		}
		return new DefaultLoopResources(prefix, workerCount, daemon);
	}

	/**
	 * Create a simple {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 *
	 * @param prefix the event loop thread name prefix
	 * @param selectCount number of selector threads
	 * @param workerCount number of worker threads
	 * @param daemon should the thread be released on jvm shutdown
	 *
	 * @return a new {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 */
	static LoopResources create(String prefix,
			int selectCount,
			int workerCount,
			boolean daemon) {
		if (Objects.requireNonNull(prefix, "prefix")
		           .isEmpty()) {
			throw new IllegalArgumentException("Cannot use empty prefix");
		}
		if (workerCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive " + "worker threads number, " + "was: " + workerCount);
		}
		if (selectCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive " + "selector threads number, " + "was: " + selectCount);
		}
		return new DefaultLoopResources(prefix, selectCount, workerCount, daemon);
	}

	/**
	 * Create a simple {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 *
	 * @param prefix the event loop thread name prefix
	 *
	 * @return a new {@link LoopResources} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 */
	static LoopResources create(String prefix) {
		return new DefaultLoopResources(prefix, DEFAULT_IO_SELECT_COUNT,
				DEFAULT_IO_WORKER_COUNT,
				true);
	}

	/**
	 * Callback for client or generic channel factory selection.
	 *
	 * @param group the source {@link EventLoopGroup} to assign a loop from
	 *
	 * @return a {@link Class} target for the underlying {@link Channel} factory
	 */
	default Class<? extends Channel> onChannel(EventLoopGroup group) {
		return preferNative() ? DefaultLoopNativeDetector.getInstance().getChannel(group) :
				NioSocketChannel.class;
	}

	/**
	 * Callback for client {@link EventLoopGroup} creation.
	 *
	 * @param useNative should use native group if current {@link #preferNative()} is also
	 * true
	 *
	 * @return a new {@link EventLoopGroup}
	 */
	default EventLoopGroup onClient(boolean useNative) {
		return onServer(useNative);
	}

	/**
	 * Callback for UDP channel factory selection.
	 *
	 * @param group the source {@link EventLoopGroup} to assign a loop from
	 *
	 * @return a {@link Class} target for the underlying {@link Channel} factory
	 */
	default Class<? extends DatagramChannel> onDatagramChannel(EventLoopGroup group) {
		return preferNative() ? DefaultLoopNativeDetector.getInstance().getDatagramChannel(group) :
				NioDatagramChannel.class;
	}

	/**
	 * Callback for server {@link EventLoopGroup} creation.
	 *
	 * @param useNative should use native group if current {@link #preferNative()} is also
	 * true
	 *
	 * @return a new {@link EventLoopGroup}
	 */
	EventLoopGroup onServer(boolean useNative);

	/**
	 * Callback for server channel factory selection.
	 *
	 * @param group the source {@link EventLoopGroup} to assign a loop from
	 *
	 * @return a {@link Class} target for the underlying {@link ServerChannel} factory
	 */
	default Class<? extends ServerChannel> onServerChannel(EventLoopGroup group) {
		return preferNative() ? DefaultLoopNativeDetector.getInstance().getServerChannel(group) :
				NioServerSocketChannel.class;
	}

	/**
	 * Create a server select {@link EventLoopGroup} for servers to be used
	 *
	 * @param useNative should use native group if current {@link #preferNative()} is also
	 * true
	 *
	 * @return a new {@link EventLoopGroup}
	 */
	default EventLoopGroup onServerSelect(boolean useNative) {
		return onServer(useNative);
	}

	/**
	 * Return true if should default to native {@link EventLoopGroup} and {@link Channel}
	 *
	 * @return true if should default to native {@link EventLoopGroup} and {@link Channel}
	 */
	default boolean preferNative() {
		return DefaultLoopEpoll.hasEpoll() || DefaultLoopKQueue.hasKQueue();
	}

	/**
	 * return true if {@link EventLoopGroup} should not be shutdown
	 *
	 * @return true if {@link EventLoopGroup} should not be shutdown
	 */
	default boolean daemon() {
		return false;
	}

	@Override
	default void dispose() {
		//noop default
		disposeLater().subscribe();
	}

	/**
	 * Returns a Mono that triggers the disposal of underlying resources when subscribed to.
	 *
	 * @return a Mono representing the completion of resources disposal.
	 **/
	default Mono<Void> disposeLater() {
		return Mono.empty(); //noop default
	}
}
