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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelPool;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ContextHandler;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.resources.PoolResources;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * @author Stephane Maldini
 */
final class TcpClientAcquire extends TcpClient {

	final PoolResources poolResources;

	TcpClientAcquire(PoolResources poolResources) {
		this.poolResources = Objects.requireNonNull(poolResources, "poolResources");
	}

	@Override
	public Mono<? extends Connection> connect(Bootstrap b) {
		ChannelOperations.OnSetup<Channel> ops = BootstrapHandlers.channelOperationFactory(b);

		if (b.config()
		     .group() == null) {

			TcpClientRunOn.configure(b,
					LoopResources.DEFAULT_NATIVE,
					TcpResources.get(),
					TcpUtils.findSslContext(b));
		}

		if (b.config().remoteAddress() == null) {
			String host = (String) b.config().attrs().get(HOST);
			Integer port = (Integer) b.config().attrs().get(PORT);
			String defaultHost = (String) b.config().attrs().get(DEFAULT_HOST_ATTR);
			Integer defaultPort = (Integer) b.config().attrs().get(DEFAULT_PORT_ATTR);
			if (host == null) {
				b.remoteAddress(new InetSocketAddress(defaultHost, port != null ? port : defaultPort));
			} else {
				b.remoteAddress(InetSocketAddressUtil.createUnresolved(host, port != null ? port : defaultPort));
			}
			b.attr(HOST, null)
			 .attr(PORT, null);
		}

		return Mono.create(sink -> {
			ChannelPool pool = poolResources.selectOrCreate(b.config().remoteAddress(), () -> b,
					ContextHandler.newClientContext(sink,
							isSecure(),
							b.config().remoteAddress(),
							null,
							(ch, c, msg) -> null),
					b.config().group());

			ContextHandler<Channel> ctx =
					ContextHandler.newClientContext(sink,
							isSecure(),
							b.config().remoteAddress(),
							pool,
							ops);

			sink.onCancel(ctx);

			ctx.setFuture(pool.acquire());
		});
	}
}
