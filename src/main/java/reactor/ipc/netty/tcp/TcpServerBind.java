/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.util.NetUtil;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.resources.LoopResources;

import java.net.InetSocketAddress;

/**
 * @author Stephane Maldini
 */
final class TcpServerBind extends TcpServer {

	static final TcpServerBind INSTANCE = new TcpServerBind();

	@Override
	public Mono<? extends Connection> bind(ServerBootstrap b) {
		ChannelOperations.OnSetup<Channel> ops = BootstrapHandlers.channelOperationFactory(b);

		if (b.config()
		     .group() == null) {

			TcpServerRunOn.configure(b,
					LoopResources.DEFAULT_NATIVE,
					TcpResources.get(),
					TcpUtils.findSslContext(b));
		}

		if (b.config().localAddress() == null) {
			String host = (String) b.config().attrs().get(HOST);
			Integer port = (Integer) b.config().attrs().get(PORT);
			Integer defaultPort = (Integer) b.config().attrs().get(DEFAULT_PORT_ATTR);
			if (host == null) {
				InetSocketAddress address = port != null ? new InetSocketAddress(port) :
						new InetSocketAddress(NetUtil.LOCALHOST, defaultPort);
				b.localAddress(InetSocketAddressUtil.replaceWithResolved(address));
			} else {
				b.localAddress(InetSocketAddressUtil.createResolved(host, port != null ? port : defaultPort));
			}
			b.attr(HOST, null)
			 .attr(PORT, null);
		}

		return Mono.create(sink -> {
			ContextHandler<Channel> ctx = ContextHandler.newServerContext(sink,
					ops,
					b.config().localAddress());
			BootstrapHandlers.finalize(b, ctx, ops.createOnConnected());
			ctx.setFuture(b.bind());
		});
	}
}
