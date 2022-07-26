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

import java.net.ProtocolFamily;
import java.util.concurrent.ThreadFactory;

import io.netty5.channel.Channel;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.ServerChannel;
import io.netty5.channel.epoll.Epoll;
import io.netty5.channel.epoll.EpollDatagramChannel;
import io.netty5.channel.epoll.EpollHandler;
import io.netty5.channel.epoll.EpollServerSocketChannel;
import io.netty5.channel.epoll.EpollSocketChannel;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.channel.socket.ServerSocketChannel;
import io.netty5.channel.socket.SocketChannel;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

/**
 * {@link DefaultLoop} that uses {@code Epoll} transport.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class DefaultLoopEpoll implements DefaultLoop {

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <CHANNEL extends Channel> CHANNEL getChannel(Class<CHANNEL> channelClass, EventLoop eventLoop,
			@Nullable ProtocolFamily protocolFamily) {
		if (channelClass.equals(SocketChannel.class)) {
			return eventLoop.isCompatible(EpollSocketChannel.class) ?
					(CHANNEL) new EpollSocketChannel(eventLoop, protocolFamily) : null;
		}
		if (channelClass.equals(DatagramChannel.class)) {
			return eventLoop.isCompatible(EpollDatagramChannel.class) ?
					(CHANNEL) new EpollDatagramChannel(eventLoop, protocolFamily) : null;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public String getName() {
		return "epoll";
	}

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <SERVERCHANNEL extends ServerChannel> SERVERCHANNEL getServerChannel(Class<SERVERCHANNEL> channelClass, EventLoop eventLoop,
			EventLoopGroup childEventLoopGroup, @Nullable ProtocolFamily protocolFamily) {
		if (channelClass.equals(ServerSocketChannel.class)) {
			return eventLoop.isCompatible(EpollServerSocketChannel.class) ?
					(SERVERCHANNEL) new EpollServerSocketChannel(eventLoop, childEventLoopGroup, protocolFamily) : null;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		return new MultithreadEventLoopGroup(threads, factory, EpollHandler.newFactory());
	}

	static final Logger log = Loggers.getLogger(DefaultLoopEpoll.class);

	static final boolean epoll;

	static {
		boolean epollCheck = false;
		try {
			Class.forName("io.netty5.channel.epoll.Epoll");
			epollCheck = Epoll.isAvailable();
		}
		catch (ClassNotFoundException cnfe) {
			// noop
		}
		epoll = epollCheck;
		if (log.isDebugEnabled()) {
			log.debug("Default Epoll support : " + epoll);
		}
	}
}
