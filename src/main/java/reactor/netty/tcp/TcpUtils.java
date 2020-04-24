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
package reactor.netty.tcp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import javax.annotation.Nullable;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.util.NetUtil;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.transport.AddressUtils;

/**
 * @author Stephane Maldini
 */
final class TcpUtils {

	static ServerBootstrap updateHost(ServerBootstrap b, String host) {
		return b.localAddress(_updateHost(b.config().localAddress(), host));
	}

	static SocketAddress _updateHost(@Nullable SocketAddress address, String host) {
		if(!(address instanceof InetSocketAddress)) {
			return AddressUtils.createUnresolved(host, 0);
		}

		InetSocketAddress inet = (InetSocketAddress)address;

		return AddressUtils.createUnresolved(host, inet.getPort());
	}

	static ServerBootstrap updatePort(ServerBootstrap b, int port) {
		return b.localAddress(_updatePort(b.config().localAddress(), port));
	}

	static SocketAddress _updatePort(@Nullable SocketAddress address, int port) {
		if(!(address instanceof InetSocketAddress)) {
			return AddressUtils.createUnresolved(NetUtil.LOCALHOST.getHostAddress(), port);
		}

		InetSocketAddress inet = (InetSocketAddress)address;

		InetAddress addr = inet.getAddress();

		String host = addr == null ? inet.getHostName() : addr.getHostAddress();

		return AddressUtils.createUnresolved(host, port);
	}

	static final ChannelOperations.OnSetup TCP_OPS =
			(ch, c, msg) -> new ChannelOperations<>(ch, c);


}
