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
final class DefaultLoopEpoll implements DefaultLoop {

	static final Logger log = Loggers.getLogger(DefaultLoopEpoll.class);

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
			log.debug("Default Epoll support : " + epoll);
		}
	}

	public static boolean hasEpoll() {
		return epoll;
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		return new EpollEventLoopGroup(threads, factory);
	}

	@Override
	public Class<? extends ServerChannel> getServerChannel(EventLoopGroup group) {
		return useEpoll(group) ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
	}

	@Override
	public Class<? extends Channel> getChannel(EventLoopGroup group) {
		return useEpoll(group) ? EpollSocketChannel.class : NioSocketChannel.class;
	}

	@Override
	public Class<? extends DatagramChannel> getDatagramChannel(EventLoopGroup group) {
		return useEpoll(group) ? EpollDatagramChannel.class : NioDatagramChannel.class;
	}

	@Override
	public String getName() {
		return "epoll";
	}

	private boolean useEpoll(EventLoopGroup group) {
		if (group instanceof ColocatedEventLoopGroup) {
			group = ((ColocatedEventLoopGroup) group).get();
		}
		return group instanceof EpollEventLoopGroup;
	}

}
