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

package reactor.ipc.netty.http.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.Attribute;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.tcp.TcpServer;

import java.util.Objects;

/**
 * @author Stephane Maldini
 */
final class HttpServerBind extends HttpServer {

	static final HttpServerBind INSTANCE = new HttpServerBind();

	final TcpServer tcpServer;

	HttpServerBind() {
		this(DEFAULT_TCP_SERVER);
	}

	HttpServerBind(TcpServer tcpServer) {
		this.tcpServer = Objects.requireNonNull(tcpServer, "tcpServer");
	}

	@Override
	protected TcpServer tcpConfiguration() {
		return tcpServer;
	}

	@Override
	public ServerBootstrap configure(ServerBootstrap b) {
		if (b.config()
			 .group() == null) {
			LoopResources loops = HttpResources.get();

			boolean useNative = LoopResources.DEFAULT_NATIVE && !(tcpConfiguration().sslContext() instanceof JdkSslContext);

			EventLoopGroup selector = loops.onServerSelect(useNative);
			EventLoopGroup elg = loops.onServer(useNative);

			b.group(selector, elg)
			 .channel(loops.onServerChannel(elg));
		}

		BootstrapHandlers.updateConfiguration(b, NettyPipeline.HttpInitializer, (ctx, channel) -> {
			ChannelPipeline p = channel.pipeline();

			p.addLast(NettyPipeline.HttpCodec, new HttpServerCodec());

			Attribute<Integer> minCompressionSize = channel.attr(HttpServerOperations.PRODUCE_GZIP);
			if(minCompressionSize != null &&
			        minCompressionSize.get() != null &&
			        minCompressionSize.get() >= 0) {
				p.addLast(NettyPipeline.CompressionHandler,
				        new CompressionHandler(minCompressionSize.get()));
			}

			p.addLast(NettyPipeline.HttpServerHandler, new HttpServerHandler(ctx));
		});

		return b;
	}
}
