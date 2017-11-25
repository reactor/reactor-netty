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

package reactor.ipc.netty.http.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.Attribute;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.tcp.InetSocketAddressUtil;
import reactor.ipc.netty.tcp.TcpClient;

import java.util.Objects;

/**
 * @author Stephane Maldini
 */
final class HttpClientConnect extends HttpClient {

	static final HttpClientConnect INSTANCE = new HttpClientConnect();

	final TcpClient tcpClient;
	UriEndpointFactory uriEndpointFactory;

	HttpClientConnect() {
		this(DEFAULT_TCP_CLIENT);
	}

	HttpClientConnect(TcpClient tcpClient) {
		this.tcpClient = Objects.requireNonNull(tcpClient, "tcpClient");
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return tcpClient;
	}

	@Override
	Bootstrap configure(Bootstrap b) {
		this.uriEndpointFactory = new UriEndpointFactory(() -> b.config().remoteAddress(),
				tcpConfiguration().isSecure(),
				(hostname, port) -> InetSocketAddressUtil.createInetSocketAddress(hostname, port, false));

		if (b.config()
			 .group() == null) {
			LoopResources loops = HttpResources.get();

			boolean useNative = LoopResources.DEFAULT_NATIVE && !(tcpConfiguration().sslContext() instanceof JdkSslContext);

			EventLoopGroup elg = loops.onClient(useNative);

			b.group(elg)
			 .channel(loops.onChannel(elg));
		}

		BootstrapHandlers.updateConfiguration(b, NettyPipeline.HttpInitializer, (ctx, channel) -> {
			channel.pipeline().addLast(NettyPipeline.HttpCodec, new HttpClientCodec());
			Attribute<Boolean> acceptGzip = channel.attr(HttpClientOperations.ACCEPT_GZIP);
			/*if (acceptGzip != null && acceptGzip.get()) {
				channel.pipeline().addAfter(NettyPipeline.HttpCodec,
						NettyPipeline.HttpDecompressor,
						new HttpContentDecompressor());
			}*/
		});
		return b;
	}

	@Override
	protected UriEndpointFactory uriEndpointFactory() {
		return this.uriEndpointFactory;
	}
}
