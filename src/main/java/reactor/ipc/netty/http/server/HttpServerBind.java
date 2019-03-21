/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.server;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.JdkSslContext;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.util.annotation.Nullable;

/**
 * @author Stephane Maldini
 */
final class HttpServerBind extends HttpServer
		implements Function<ServerBootstrap, ServerBootstrap> {

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
	@SuppressWarnings("unchecked")
	public Mono<? extends DisposableServer> bind(TcpServer delegate) {
		return delegate.bootstrap(this)
		               .bind();
	}

	@Override
	public ServerBootstrap apply(ServerBootstrap b) {
		if (b.config()
		     .group() == null) {
			LoopResources loops = HttpResources.get();

			boolean useNative =
					LoopResources.DEFAULT_NATIVE && !(tcpConfiguration().sslContext() instanceof JdkSslContext);

			EventLoopGroup selector = loops.onServerSelect(useNative);
			EventLoopGroup elg = loops.onServer(useNative);

			b.group(selector, elg)
			 .channel(loops.onServerChannel(elg));
		}

		HttpServerConfiguration conf = HttpServerConfiguration.getAndClean(b);

		//remove any OPS since we will initialize below
		BootstrapHandlers.channelOperationFactory(b);

		int line = conf.decoder.maxInitialLineLength;
		int header = conf.decoder.maxHeaderSize;
		int buffer = conf.decoder.initialBufferSize;
		int chunk = conf.decoder.maxChunkSize;
		boolean validate = conf.decoder.validateHeaders;
		boolean forwarded = conf.forwarded;
		int minCompressionSize = conf.minCompressionSize;

		BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate = compressPredicate(conf.compressPredicate, minCompressionSize);


		return BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.HttpInitializer,
				(listener, channel) -> {
					ChannelPipeline p = channel.pipeline();

					p.addLast(NettyPipeline.HttpCodec, new HttpServerCodec(line, header, chunk, validate, buffer));

					boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;
					if(alwaysCompress) {
						p.addLast(NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
					}

					p.addLast(NettyPipeline.HttpServerHandler,
							new HttpRequestPipeliningHandler((c, l, msg) -> new HttpServerOperations(c, l, compressPredicate, (HttpRequest) msg, forwarded), listener));
				});
	}

	@Nullable static BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate(
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
			int minResponseSize) {

		if (minResponseSize <= 0) {
			if (compressionPredicate != null) {
				return compressionPredicate;
			}
			else {
				return null;
			}
		}

		BiPredicate<HttpServerRequest, HttpServerResponse> lengthPredicate =
				(req, res) -> {
					String length = res.responseHeaders()
					                   .get(HttpHeaderNames.CONTENT_LENGTH);

					if (length == null) {
						return true;
					}

					try {
						return Long.parseLong(length) >= minResponseSize;
					}
					catch (NumberFormatException nfe) {
						return true;
					}
				};

		if (compressionPredicate != null) {
			lengthPredicate = lengthPredicate.and(compressionPredicate);
		}
		return lengthPredicate;
	}
}
