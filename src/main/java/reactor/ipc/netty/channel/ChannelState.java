/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.channel;

import java.net.InetSocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.NettyState;

/**
 * @author Stephane Maldini
 */
final class ChannelState implements NettyState {

	final Channel c;

	ChannelState(Channel c) {
		this.c = c;
	}

	@Override
	public InetSocketAddress address() {
		if (c instanceof SocketChannel) {
			return ((SocketChannel) c).remoteAddress();
		}
		if (c instanceof ServerSocketChannel){
			return ((ServerSocketChannel) c).localAddress();
		}
		if (c instanceof DatagramChannel) {
			return ((DatagramChannel) c).localAddress();
		}
		throw new IllegalStateException("Does not have an InetSocketAddress");
	}

	@Override
	public Channel channel() {
		return c;
	}

	@Override
	public Mono<Void> onClose() {
		return ChannelFutureMono.from(c.closeFuture());
	}

	@Override
	public void dispose() {
		try {
			c.close()
			 .sync();
		}
		catch (InterruptedException ie) {
			Thread.currentThread()
			      .interrupt();
		}
	}
}
