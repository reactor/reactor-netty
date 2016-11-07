/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.options;

import java.util.Objects;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * An {@link EventLoopSelector} selector with associated
 * {@link io.netty.channel.Channel} factories.
 *
 * @author Stephane Maldini
 * @since 0.6
 */
@FunctionalInterface
public interface EventLoopSelector {

	/**
	 * Default worker thread count, fallback to available processor
	 */
	int DEFAULT_IO_WORKER_COUNT = Integer.parseInt(System.getProperty(
			"reactor.tcp.workerCount",
			"" + Runtime.getRuntime()
			            .availableProcessors()));
	/**
	 * Default selector thread count, fallback to -1 (no selector thread)
	 */
	int DEFAULT_IO_SELECT_COUNT = Integer.parseInt(System.getProperty(
			"reactor.tcp.selectCount",
			"" + -1));

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
	 * Create a simple {@link EventLoopSelector} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 *
	 * @param prefix the event loop thread name prefix
	 * @param workerCount number of worker threads
	 * @param daemon shoult the thread be released on jvm shutdown
	 *
	 * @return a new {@link EventLoopSelector} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 */
	static EventLoopSelector create(String prefix, int workerCount, boolean daemon) {
		if (workerCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive " + "worker number, " + "was: " + workerCount);
		}
		return new DefaultEventLoopSelector(prefix, workerCount, daemon);
	}

	/**
	 * Create a simple {@link EventLoopSelector} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 *
	 * @param prefix the event loop thread name prefix
	 * @param selectCount number of selector threads
	 * @param workerCount number of worker threads
	 * @param daemon shoult the thread be released on jvm shutdown
	 *
	 * @return a new {@link EventLoopSelector} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 */
	static EventLoopSelector create(String prefix,
			int selectCount,
			int workerCount,
			boolean daemon) {
		if (Objects.requireNonNull(prefix, "prefix")
		           .isEmpty()) {
			throw new IllegalArgumentException("Cannot use empty prefix");
		}
		if (workerCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive " + "worker number, " + "was: " + workerCount);
		}
		if (selectCount < 1) {
			throw new IllegalArgumentException("Must provide a strictly positive " + "worker number, " + "was: " + workerCount);
		}
		return new DefaultEventLoopSelector(prefix, selectCount, workerCount, daemon);
	}

	/**
	 * Create a simple {@link EventLoopSelector} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 *
	 * @param prefix the event loop thread name prefix
	 *
	 * @return a new {@link EventLoopSelector} to provide automatically for {@link
	 * EventLoopGroup} and {@link Channel} factories
	 */
	static EventLoopSelector create(String prefix) {
		return new DefaultEventLoopSelector(prefix, DEFAULT_IO_SELECT_COUNT,
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
		return preferNative() ? SafeEpollDetector.getChannel(group) :
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
		return onClient(useNative);
	}

	/**
	 * Callback for UDP channel factory selection.
	 *
	 * @param group the source {@link EventLoopGroup} to assign a loop from
	 *
	 * @return a {@link Class} target for the underlying {@link Channel} factory
	 */
	default Class<? extends DatagramChannel> onDatagramChannel(EventLoopGroup group) {
		return preferNative() ? SafeEpollDetector.getDatagramChannel(group) :
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
		return preferNative() ? SafeEpollDetector.getServerChannel(group) :
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
	 * @return true if should default to native {@link EventLoopGroup} and {@link Channel}
	 */
	default boolean preferNative() {
		return SafeEpollDetector.hasEpoll();
	}
}
