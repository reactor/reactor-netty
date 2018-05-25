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

package reactor.ipc.netty.http.server;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.JdkSslContext;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.tcp.SslProvider;
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
		SslProvider ssl = SslProvider.findSslSupport(b);

		if (b.config()
		     .group() == null) {
			LoopResources loops = HttpResources.get();

			boolean useNative =
					LoopResources.DEFAULT_NATIVE || (ssl != null && !(ssl.getSslContext() instanceof JdkSslContext));

			EventLoopGroup selector = loops.onServerSelect(useNative);
			EventLoopGroup elg = loops.onServer(useNative);

			b.group(selector, elg)
			 .channel(loops.onServerChannel(elg));
		}

		HttpServerConfiguration conf = HttpServerConfiguration.getAndClean(b);

		//remove any OPS since we will initialize below
		BootstrapHandlers.channelOperationFactory(b);

		return BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.HttpInitializer,
				new HttpServerInitializer(
						conf.decoder.maxInitialLineLength,
						conf.decoder.maxHeaderSize,
						conf.decoder.maxChunkSize,
						conf.decoder.validateHeaders,
						conf.decoder.initialBufferSize,
						conf.minCompressionSize,
						compressPredicate(conf.compressPredicate, conf.minCompressionSize),
						conf.forwarded));
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

	static final class HttpServerInitializer
			implements BiConsumer<ConnectionObserver, Channel> {

		final int                                                line;
		final int                                                header;
		final int                                                chunk;
		final boolean                                            validate;
		final int                                                buffer;
		final int                                                minCompressionSize;
		final BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate;
		final boolean                                            forwarded;

		HttpServerInitializer(int line,
				int header,
				int chunk,
				boolean validate,
				int buffer,
				int minCompressionSize,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded) {
			this.line = line;
			this.header = header;
			this.chunk = chunk;
			this.validate = validate;
			this.buffer = buffer;
			this.minCompressionSize = minCompressionSize;
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			ChannelPipeline p = channel.pipeline();

			HttpServerCodec httpServerCodec =
					new HttpServerCodec(line, header, chunk, validate, buffer);

			p.addLast(NettyPipeline.HttpCodec, httpServerCodec);

			boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

			if (alwaysCompress) {
				p.addLast(NettyPipeline.CompressionHandler,
						new SimpleCompressionHandler());
			}

			p.addLast(NettyPipeline.HttpTrafficHandler,
					new HttpTrafficHandler(listener, forwarded, compressPredicate));
		}
	}
}
