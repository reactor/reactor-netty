/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.DatagramChannel;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class UdpOperations extends ChannelOperations<UdpInbound, UdpOutbound>
		implements UdpInbound, UdpOutbound {

	static UdpOperations bindUdp(DatagramChannel channel,
			ContextHandler<?> context) {
		return new UdpOperations(channel, context);
	}

	final DatagramChannel  datagramChannel;

	UdpOperations(DatagramChannel channel,
			ContextHandler<?> context) {
		super(channel, null, context);
		this.datagramChannel = channel;
	}

	/**
	 * Join a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to join
	 *
	 * @return a {@link Publisher} that will be complete when the group has been joined
	 */
	public Mono<Void> join(final InetAddress multicastAddress, NetworkInterface iface) {
		if (null == iface && null != datagramChannel.config().getNetworkInterface()) {
			iface = datagramChannel.config().getNetworkInterface();
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

		return FutureMono.from(future)
		                 .doOnSuccess(v -> log.info("JOIN {}", multicastAddress));
	}

	/**
	 * Leave a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to leave
	 *
	 * @return a {@link Publisher} that will be complete when the group has been left
	 */
	public Mono<Void> leave(final InetAddress multicastAddress, NetworkInterface iface) {
		if (null == iface && null != datagramChannel.config().getNetworkInterface()) {
			iface = datagramChannel.config().getNetworkInterface();
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

		return FutureMono.from(future)
		                 .doOnSuccess(v -> log.info("JOIN {}", multicastAddress));
	}

	static final Logger log = Loggers.getLogger(UdpOperations.class);
}
