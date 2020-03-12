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
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * @author Violeta Georgieva
 */
interface DefaultLoop {

	default EventLoopGroup newEventLoopGroup(int threads, ThreadFactory factory) {
		throw new IllegalStateException("Missing Epoll/KQueue on current system");
	}

	default Class<? extends ServerChannel> getServerChannel(EventLoopGroup group) {
		return NioServerSocketChannel.class;
	}

	default Class<? extends Channel> getChannel(EventLoopGroup group) {
		return NioSocketChannel.class;
	}

	default Class<? extends DatagramChannel> getDatagramChannel(EventLoopGroup group) {
		return NioDatagramChannel.class;
	}

	default String getName() {
		return "nio";
	}
}
