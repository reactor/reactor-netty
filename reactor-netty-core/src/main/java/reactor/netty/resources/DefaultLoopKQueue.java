/*
 * Copyright (c) 2018-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.util.concurrent.ThreadFactory;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueDomainDatagramChannel;
import io.netty.channel.kqueue.KQueueDomainSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerDomainSocketChannel;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.unix.DomainDatagramChannel;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.channel.unix.ServerDomainSocketChannel;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * {@link DefaultLoop} that uses {@code KQueue} transport.
 *
 * @author Violeta Georgieva
 */
final class DefaultLoopKQueue implements DefaultLoop {

	@Override
	@SuppressWarnings("unchecked")
	public <CHANNEL extends Channel> CHANNEL getChannel(Class<CHANNEL> channelClass) {
		if (channelClass.equals(SocketChannel.class)) {
			return (CHANNEL) new KQueueSocketChannel();
		}
		if (channelClass.equals(ServerSocketChannel.class)) {
			return (CHANNEL) new KQueueServerSocketChannel();
		}
		if (channelClass.equals(DatagramChannel.class)) {
			return (CHANNEL) new KQueueDatagramChannel();
		}
		if (channelClass.equals(DomainSocketChannel.class)) {
			return (CHANNEL) new KQueueDomainSocketChannel();
		}
		if (channelClass.equals(ServerDomainSocketChannel.class)) {
			return (CHANNEL) new KQueueServerDomainSocketChannel();
		}
		if (channelClass.equals(DomainDatagramChannel.class)) {
			return (CHANNEL) new KQueueDomainDatagramChannel();
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	@SuppressWarnings("unchecked")
	public <CHANNEL extends Channel> Class<? extends CHANNEL> getChannelClass(Class<CHANNEL> channelClass) {
		if (channelClass.equals(SocketChannel.class)) {
			return (Class<? extends CHANNEL>) KQueueSocketChannel.class;
		}
		if (channelClass.equals(ServerSocketChannel.class)) {
			return (Class<? extends CHANNEL>) KQueueServerSocketChannel.class;
		}
		if (channelClass.equals(DatagramChannel.class)) {
			return (Class<? extends CHANNEL>) KQueueDatagramChannel.class;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public String getName() {
		return "kqueue";
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		return new KQueueEventLoopGroup(threads, factory);
	}

	@Override
	public boolean supportGroup(EventLoopGroup group) {
		if (group instanceof ColocatedEventLoopGroup) {
			group = ((ColocatedEventLoopGroup) group).get();
		}
		return group instanceof KQueueEventLoopGroup;
	}

	static final Logger log = Loggers.getLogger(DefaultLoopKQueue.class);

	static final boolean isKqueueAvailable;

	static {
		boolean kqueueCheck = false;
		try {
			Class.forName("io.netty.channel.kqueue.KQueue");
			kqueueCheck = KQueue.isAvailable();
		}
		catch (ClassNotFoundException cnfe) {
			// noop
		}
		isKqueueAvailable = kqueueCheck;
		if (log.isDebugEnabled()) {
			log.debug("Default KQueue support : " + isKqueueAvailable);
		}
	}
}
