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
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Violeta Georgieva
 */
final class DefaultLoopKQueue implements DefaultLoop {

	static final Logger log = Loggers.getLogger(DefaultLoopKQueue.class);

	private static final boolean kqueue;

	static {
		boolean kqueueCheck = false;
		try{
			Class.forName("io.netty.channel.kqueue.KQueue");
			kqueueCheck = KQueue.isAvailable();
		}
		catch (ClassNotFoundException cnfe){
		}
		kqueue = kqueueCheck;
		if (log.isDebugEnabled()) {
			log.debug("Default KQueue support : " + kqueue);
		}
	}

	public static boolean hasKQueue() {
		return kqueue;
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		return new KQueueEventLoopGroup(threads, factory);
	}

	@Override
	public Class<? extends ServerChannel> getServerChannel(EventLoopGroup group) {
		return useKQueue(group) ? KQueueServerSocketChannel.class : NioServerSocketChannel.class;
	}

	@Override
	public Class<? extends Channel> getChannel(EventLoopGroup group) {
		return useKQueue(group) ? KQueueSocketChannel.class : NioSocketChannel.class;
	}

	@Override
	public Class<? extends DatagramChannel> getDatagramChannel(EventLoopGroup group) {
		return useKQueue(group) ? KQueueDatagramChannel.class : NioDatagramChannel.class;
	}

	@Override
	public String getName() {
		return "kqueue";
	}

	private boolean useKQueue(EventLoopGroup group) {
		if (group instanceof ColocatedEventLoopGroup) {
			group = ((ColocatedEventLoopGroup) group).get();
		}
		return group instanceof KQueueEventLoopGroup;
	}
}
