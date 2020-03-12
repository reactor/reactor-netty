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

package reactor.netty.udp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;

import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.DatagramChannel;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 */
final class UdpOperations extends ChannelOperations<UdpInbound, UdpOutbound>
		implements UdpInbound, UdpOutbound {

	final DatagramChannel  datagramChannel;

	@SuppressWarnings("unchecked")
	UdpOperations(Connection c, ConnectionObserver listener) {
		super(c, listener);
		this.datagramChannel = (DatagramChannel)c.channel();
	}

	/**
	 * Join a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to join
	 *
	 * @return a {@link Publisher} that will be complete when the group has been joined
	 */
	@Override
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
		                 .doOnSuccess(v -> {
		                     if (log.isInfoEnabled()) {
		                         log.info(format(future.channel(), "JOIN {}"), multicastAddress);
		                     }
		                 });
	}

	/**
	 * Leave a multicast group.
	 *
	 * @param multicastAddress multicast address of the group to leave
	 *
	 * @return a {@link Publisher} that will be complete when the group has been left
	 */
	@Override
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
		                 .doOnSuccess(v -> {
		                     if (log.isInfoEnabled()) {
		                         log.info(format(future.channel(), "JOIN {}"), multicastAddress);
		                     }
		                 });
	}

	static final Logger log = Loggers.getLogger(UdpOperations.class);
}
