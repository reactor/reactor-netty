/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.channel.unix.ServerDomainSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringDatagramChannel;
import io.netty.channel.uring.IoUringDomainSocketChannel;
import io.netty.channel.uring.IoUringIoHandle;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerDomainSocketChannel;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.channel.uring.IoUringSocketChannel;
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
			return (CHANNEL) new IoUringSocketChannel();
		}
		if (channelClass.equals(ServerSocketChannel.class)) {
			return (CHANNEL) new IoUringServerSocketChannel();
		}
		if (channelClass.equals(DatagramChannel.class)) {
			return (CHANNEL) new IoUringDatagramChannel();
		}
		if (channelClass.equals(DomainSocketChannel.class)) {
			return (CHANNEL) new IoUringDomainSocketChannel();
		}
		if (channelClass.equals(ServerDomainSocketChannel.class)) {
			return (CHANNEL) new IoUringServerDomainSocketChannel();
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	@SuppressWarnings("unchecked")
	public <CHANNEL extends Channel> Class<? extends CHANNEL> getChannelClass(Class<CHANNEL> channelClass) {
		if (channelClass.equals(SocketChannel.class)) {
			return (Class<? extends CHANNEL>) IoUringSocketChannel.class;
		}
		if (channelClass.equals(ServerSocketChannel.class)) {
			return (Class<? extends CHANNEL>) IoUringServerSocketChannel.class;
		}
		if (channelClass.equals(DatagramChannel.class)) {
			return (Class<? extends CHANNEL>) IoUringDatagramChannel.class;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public String getName() {
		return "io_uring";
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		return new MultiThreadIoEventLoopGroup(threads, factory, IoUringIoHandler.newFactory());
	}

	@Override
	public boolean supportGroup(EventLoopGroup group) {
		if (group instanceof ColocatedEventLoopGroup) {
			group = ((ColocatedEventLoopGroup) group).get();
		}
		return group instanceof IoEventLoopGroup && ((IoEventLoopGroup) group).isCompatible(IoUringIoHandle.class);
	}

	static final Logger log = Loggers.getLogger(DefaultLoopIOUring.class);

	static final boolean isIoUringAvailable;

	static {
		boolean ioUringCheck = false;
		try {
			Class.forName("io.netty.channel.uring.IoUring");
			ioUringCheck = IoUring.isAvailable();
		}
		catch (ClassNotFoundException cnfe) {
			// noop
		}
		isIoUringAvailable = ioUringCheck;
		if (log.isDebugEnabled()) {
			log.debug("Default io_uring support : " + isIoUringAvailable);
		}
	}
}
