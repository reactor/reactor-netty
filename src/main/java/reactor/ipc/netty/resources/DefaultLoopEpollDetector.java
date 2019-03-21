/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.resources;

import java.util.concurrent.ThreadFactory;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class DefaultLoopEpollDetector {

	static final Logger log = Loggers.getLogger(DefaultLoopEpollDetector.class);

	private static final boolean epoll;

	static {
		boolean epollCheck = false;
		try{
			Class.forName("io.netty.channel.epoll.Epoll");
			epollCheck = Epoll.isAvailable();
		}
		catch (ClassNotFoundException cnfe){
		}
		epoll = epollCheck;
		if (log.isDebugEnabled()) {
			log.debug("Default epoll " + "support : " + epoll);
		}
	}

	public static EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		if(epoll){
			return new EpollEventLoopGroup(threads, factory);
		}
		throw new IllegalStateException("Missing EPoll on current system");
	}

	public static Class<? extends ServerChannel> getServerChannel(EventLoopGroup group) {
		return useEpoll(group) ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
	}

	public static Class<? extends Channel> getChannel(EventLoopGroup group) {
		return useEpoll(group) ? EpollSocketChannel.class : NioSocketChannel.class;
	}

	public static Class<? extends DatagramChannel> getDatagramChannel(EventLoopGroup group) {
		return useEpoll(group) ? EpollDatagramChannel.class : NioDatagramChannel.class;
	}

	public static boolean hasEpoll() {
		return epoll;
	}

	private static boolean useEpoll(EventLoopGroup group) {
		if (!epoll) {
			return false;
		}
		if (group instanceof ColocatedEventLoopGroup) {
			group = ((ColocatedEventLoopGroup) group).get();
		}
		return group instanceof EpollEventLoopGroup;
	}

}
