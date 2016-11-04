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

package reactor.ipc.netty.udp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.function.BiFunction;

import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.concurrent.Future;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.ChannelFutureMono;
import reactor.ipc.netty.NettyState;
import reactor.ipc.netty.channel.NettyOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class UdpOperations extends NettyOperations<UdpInbound, UdpOutbound>
		implements UdpInbound, UdpOutbound {

	static UdpOperations bind(DatagramChannel channel,
			NetworkInterface iface,
			BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> clientSink) {
		UdpOperations ops = new UdpOperations(channel, iface, handler, clientSink);

		channel.attr(NettyOperations.OPERATIONS_ATTRIBUTE_KEY)
		       .set(ops);

		NettyOperations.addHandler(channel);

		return ops;
	}

	final DatagramChannel  datagramChannel;
	final NetworkInterface multicastInterface;

	UdpOperations(DatagramChannel channel,
			NetworkInterface multicastInterface,
			BiFunction<? super UdpInbound, ? super UdpOutbound, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> clientSink) {
		super(channel, handler, clientSink);
		this.datagramChannel = channel;
		this.multicastInterface = multicastInterface;
	}

	/**
	 * Join a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to join
	 *
	 * @return a {@link Publisher} that will be complete when the group has been joined
	 */
	public Mono<Void> join(final InetAddress multicastAddress, NetworkInterface iface) {
		if (null == iface && null != multicastInterface) {
			iface = multicastInterface;
		}

		final ChannelFuture future;
		if (null != iface) {
			future = datagramChannel.joinGroup(new InetSocketAddress(multicastAddress,
					datagramChannel.localAddress()
					               .getPort()), iface);
		}
		else {
			future = datagramChannel.joinGroup(multicastAddress);
		}

		return new ChannelFutureMono<Future<?>>(future) {
			@Override
			protected void doComplete(Future<?> future, Subscriber<? super Void> s) {
				log.info("JOIN {}", multicastAddress);
				super.doComplete(future, s);
			}
		};
	}

	/**
	 * Leave a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to leave
	 *
	 * @return a {@link Publisher} that will be complete when the group has been left
	 */
	public Mono<Void> leave(final InetAddress multicastAddress, NetworkInterface iface) {
		if (null == iface && null != multicastInterface) {
			iface = multicastInterface;
		}

		final ChannelFuture future;
		if (null != iface) {
			future = datagramChannel.leaveGroup(new InetSocketAddress(multicastAddress,
					datagramChannel.localAddress()
					               .getPort()), iface);
		}
		else {
			future = datagramChannel.leaveGroup(multicastAddress);
		}

		return new ChannelFutureMono<Future<?>>(future) {
			@Override
			protected void doComplete(Future<?> future, Subscriber<? super Void> s) {
				log.info("LEAVE {}", multicastAddress);
				super.doComplete(future, s);
			}
		};
	}

	static final Logger log = Loggers.getLogger(UdpOperations.class);
}
