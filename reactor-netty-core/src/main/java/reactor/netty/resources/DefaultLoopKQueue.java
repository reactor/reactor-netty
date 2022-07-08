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

import io.netty5.channel.Channel;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.ServerChannel;
import io.netty5.channel.kqueue.KQueue;
import io.netty5.channel.kqueue.KQueueDatagramChannel;
import io.netty5.channel.kqueue.KQueueDomainDatagramChannel;
import io.netty5.channel.kqueue.KQueueDomainSocketChannel;
import io.netty5.channel.kqueue.KQueueHandler;
import io.netty5.channel.kqueue.KQueueServerDomainSocketChannel;
import io.netty5.channel.kqueue.KQueueServerSocketChannel;
import io.netty5.channel.kqueue.KQueueSocketChannel;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.channel.socket.ServerSocketChannel;
import io.netty5.channel.socket.SocketChannel;
import io.netty5.channel.unix.DomainDatagramChannel;
import io.netty5.channel.unix.DomainSocketChannel;
import io.netty5.channel.unix.ServerDomainSocketChannel;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

/**
 * {@link DefaultLoop} that uses {@code KQueue} transport.
 *
 * @author Violeta Georgieva
 */
final class DefaultLoopKQueue implements DefaultLoop {

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <CHANNEL extends Channel> CHANNEL getChannel(Class<CHANNEL> channelClass, EventLoop eventLoop) {
		if (channelClass.equals(SocketChannel.class)) {
			return eventLoop.isCompatible(KQueueSocketChannel.class) ?
					(CHANNEL) new KQueueSocketChannel(eventLoop) : null;
		}
		if (channelClass.equals(DatagramChannel.class)) {
			return eventLoop.isCompatible(KQueueDatagramChannel.class) ?
					(CHANNEL) new KQueueDatagramChannel(eventLoop) : null;
		}
		if (channelClass.equals(DomainSocketChannel.class)) {
			return eventLoop.isCompatible(KQueueDomainSocketChannel.class) ?
					(CHANNEL) new KQueueDomainSocketChannel(eventLoop) : null;
		}
		if (channelClass.equals(DomainDatagramChannel.class)) {
			return eventLoop.isCompatible(KQueueDomainDatagramChannel.class) ?
					(CHANNEL) new KQueueDomainDatagramChannel(eventLoop) : null;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public String getName() {
		return "kqueue";
	}

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <SERVERCHANNEL extends ServerChannel> SERVERCHANNEL getServerChannel(Class<SERVERCHANNEL> channelClass, EventLoop eventLoop,
			EventLoopGroup childEventLoopGroup) {
		if (channelClass.equals(ServerSocketChannel.class)) {
			return eventLoop.isCompatible(KQueueServerSocketChannel.class) ?
					(SERVERCHANNEL) new KQueueServerSocketChannel(eventLoop, childEventLoopGroup) : null;
		}
		if (channelClass.equals(ServerDomainSocketChannel.class)) {
			return eventLoop.isCompatible(KQueueServerDomainSocketChannel.class) ?
					(SERVERCHANNEL) new KQueueServerDomainSocketChannel(eventLoop, childEventLoopGroup) : null;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		return new MultithreadEventLoopGroup(threads, factory, KQueueHandler.newFactory());
	}

	static final Logger log = Loggers.getLogger(DefaultLoopKQueue.class);

	static final boolean kqueue;

	static {
		boolean kqueueCheck = false;
		try {
			Class.forName("io.netty5.channel.kqueue.KQueue");
			kqueueCheck = KQueue.isAvailable();
		}
		catch (ClassNotFoundException cnfe) {
			// noop
		}
		kqueue = kqueueCheck;
		if (log.isDebugEnabled()) {
			log.debug("Default KQueue support : " + kqueue);
		}
	}
}
