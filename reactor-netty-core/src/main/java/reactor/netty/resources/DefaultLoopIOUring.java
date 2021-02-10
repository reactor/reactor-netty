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

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.incubator.channel.uring.IOUring;
import io.netty.incubator.channel.uring.IOUringDatagramChannel;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringServerSocketChannel;
import io.netty.incubator.channel.uring.IOUringSocketChannel;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.concurrent.ThreadFactory;

/**
 * {@link DefaultLoop} that uses {@code io_uring} transport.
 *
 * @author Violeta Georgieva
 */
final class DefaultLoopIOUring implements DefaultLoop {

	@Override
	@SuppressWarnings("unchecked")
	public <CHANNEL extends Channel> CHANNEL getChannel(Class<CHANNEL> channelClass) {
		if (channelClass.equals(SocketChannel.class)) {
			return (CHANNEL) new IOUringSocketChannel();
		}
		if (channelClass.equals(ServerSocketChannel.class)) {
			return (CHANNEL) new IOUringServerSocketChannel();
		}
		if (channelClass.equals(DatagramChannel.class)) {
			return (CHANNEL) new IOUringDatagramChannel();
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	@SuppressWarnings("unchecked")
	public <CHANNEL extends Channel> Class<? extends CHANNEL> getChannelClass(Class<CHANNEL> channelClass) {
		if (channelClass.equals(SocketChannel.class)) {
			return (Class<? extends CHANNEL>) IOUringSocketChannel.class;
		}
		if (channelClass.equals(ServerSocketChannel.class)) {
			return (Class<? extends CHANNEL>) IOUringServerSocketChannel.class;
		}
		if (channelClass.equals(DatagramChannel.class)) {
			return (Class<? extends CHANNEL>) IOUringDatagramChannel.class;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public String getName() {
		return "io_uring";
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		return new IOUringEventLoopGroup(threads, factory);
	}

	@Override
	public boolean supportGroup(EventLoopGroup group) {
		if (group instanceof ColocatedEventLoopGroup) {
			group = ((ColocatedEventLoopGroup) group).get();
		}
		return group instanceof IOUringEventLoopGroup;
	}

	static final Logger log = Loggers.getLogger(DefaultLoopIOUring.class);

	static final boolean ioUring;

	static {
		boolean ioUringCheck = false;
		try {
			Class.forName("io.netty.incubator.channel.uring.IOUring");
			ioUringCheck = IOUring.isAvailable();
		}
		catch (ClassNotFoundException cnfe) {
			// noop
		}
		ioUring = ioUringCheck;
		if (log.isDebugEnabled()) {
			log.debug("Default io_uring support : " + ioUring);
		}
	}
}
