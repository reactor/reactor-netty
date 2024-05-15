/*
 * Copyright (c) 2020-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty5.channel.Channel;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.ServerChannel;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.channel.socket.ServerSocketChannel;
import io.netty5.channel.socket.SocketChannel;
import io.netty5.channel.uring.IOUring;
import io.netty5.channel.uring.IOUringDatagramChannel;
import io.netty5.channel.uring.IOUringHandler;
import io.netty5.channel.uring.IOUringServerSocketChannel;
import io.netty5.channel.uring.IOUringSocketChannel;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.ProtocolFamily;
import java.util.concurrent.ThreadFactory;

/**
 * {@link DefaultLoop} that uses {@code io_uring} transport.
 *
 * @author Violeta Georgieva
 */
final class DefaultLoopIOUring implements DefaultLoop {

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <CHANNEL extends Channel> CHANNEL getChannel(Class<CHANNEL> channelClass, EventLoop eventLoop,
			@Nullable ProtocolFamily protocolFamily) {
		if (channelClass.equals(SocketChannel.class)) {
			return eventLoop.isCompatible(IOUringSocketChannel.class) ?
					(CHANNEL) new IOUringSocketChannel(eventLoop) : // ignore protocolFamily
					null;
		}
		if (channelClass.equals(DatagramChannel.class)) {
			return eventLoop.isCompatible(IOUringDatagramChannel.class) ?
					(CHANNEL) new IOUringDatagramChannel(eventLoop) : null; // ignore protocolFamily
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public String getName() {
		return "io_uring";
	}

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <SERVERCHANNEL extends ServerChannel> SERVERCHANNEL getServerChannel(Class<SERVERCHANNEL> channelClass, EventLoop eventLoop,
			EventLoopGroup childEventLoopGroup, @Nullable ProtocolFamily protocolFamily) {
		if (channelClass.equals(ServerSocketChannel.class)) {
			return eventLoop.isCompatible(IOUringServerSocketChannel.class) ?
					(SERVERCHANNEL) new IOUringServerSocketChannel(eventLoop, childEventLoopGroup) : // ignore protocolFamily
					null;
		}
		throw new IllegalArgumentException("Unsupported channel type: " + channelClass.getSimpleName());
	}

	@Override
	public EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		return new MultithreadEventLoopGroup(threads, factory, IOUringHandler.newFactory());
	}

	static final Logger log = Loggers.getLogger(DefaultLoopIOUring.class);

	static final boolean isIoUringAvailable;

	static {
		boolean ioUringCheck = false;
		try {
			Class.forName("io.netty5.channel.uring.IOUring");
			ioUringCheck = IOUring.isAvailable();
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