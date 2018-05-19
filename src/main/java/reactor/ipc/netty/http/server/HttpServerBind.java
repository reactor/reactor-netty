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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexCodecBuilder;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.AsciiString;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.channel.ChannelOperationsHandler;
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

		int line = conf.decoder.maxInitialLineLength;
		int header = conf.decoder.maxHeaderSize;
		int buffer = conf.decoder.initialBufferSize;
		int chunk = conf.decoder.maxChunkSize;
		boolean validate = conf.decoder.validateHeaders;
		boolean forwarded = conf.forwarded;
		int minCompressionSize = conf.minCompressionSize;

		BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate =
				compressPredicate(conf.compressPredicate, minCompressionSize);


		return BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.HttpInitializer,
				(listener, channel) -> {
					ChannelPipeline p = channel.pipeline();

					if (p.get(NettyPipeline.SslHandler) != null) {
						p.addLast(new Http2ServerInitializer(line, header, chunk, validate, buffer,
								minCompressionSize, compressPredicate, forwarded, listener));
					}
					else {
						HttpServerCodec httpServerCodec = new HttpServerCodec(line, header, chunk, validate, buffer);
						ChannelOperations.OnSetup ops = (c, l, msg) ->
								new HttpServerOperations(c, l, compressPredicate, (HttpRequest) msg, forwarded);

						p.addLast(NettyPipeline.HttpCodec, httpServerCodec)
						 .addLast(new HttpServerUpgradeHandler(httpServerCodec,
						          new UpgradeCodecFactoryImpl(compressPredicate, forwarded, listener)));

						boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;
						if (alwaysCompress) {
							p.addLast(NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
						}

						HttpRequestPipeliningHandler httpServerHandler = new HttpRequestPipeliningHandler(ops, listener);
						p.addLast(NettyPipeline.HttpServerHandler, httpServerHandler);
					}
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

	static final class UpgradeCodecFactoryImpl implements HttpServerUpgradeHandler.UpgradeCodecFactory {
		final BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate;
		final boolean forwarded;
		final ConnectionObserver listener;

		UpgradeCodecFactoryImpl(BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded, ConnectionObserver listener) {
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
			this.listener = listener;
		}

		@Override
		public HttpServerUpgradeHandler.UpgradeCodec newUpgradeCodec(CharSequence protocol) {
			if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
				Http2MultiplexCodecBuilder http2MultiplexCodecBuilder =
						Http2MultiplexCodecBuilder.forServer(new ChannelInitializer() {
							@Override
							protected void initChannel(Channel ch) {
								ChannelOperations.OnSetup ops = (c, l, msg) ->
										new HttpServerOperations(c, l, compressPredicate, msg, forwarded);
								Http2BridgeServerHandler httpServerHandler = new Http2BridgeServerHandler(ops, listener);
								ch.pipeline().addLast(NettyPipeline.HttpServerHandler, httpServerHandler);

								ch.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true));

								ch.pipeline().addLast(NettyPipeline.ReactiveBridge,
										new ChannelOperationsHandler((c, listener, msg) -> null, listener));

								if (log.isDebugEnabled()) {
									log.debug("{} Initialized HTTP/2 pipeline {}", ch, ch.pipeline());
								}
							}
						})
						.initialSettings(Http2Settings.defaultSettings())
						// TODO how to check that LoggingHandler is configured
						.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, HttpServer.class));
				return new Http2ServerUpgradeCodec(http2MultiplexCodecBuilder.build());
			} else {
				return null;
			}
		}
	}

	static final class Http2ServerInitializer extends ApplicationProtocolNegotiationHandler {
		final Integer line;
		final Integer header;
		final Integer chunk;
		final Boolean validate;
		final Integer buffer;
		final Integer minCompressionSize;
		final BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate;
		final boolean forwarded;
		final ConnectionObserver listener;

		Http2ServerInitializer(Integer line, Integer header, Integer chunk, Boolean validate,
				Integer buffer, Integer minCompressionSize,
				BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded, ConnectionObserver listener) {
			super(ApplicationProtocolNames.HTTP_1_1);
			this.line = line;
			this.header = header;
			this.chunk = chunk;
			this.validate = validate;
			this.buffer = buffer;
			this.minCompressionSize = minCompressionSize;
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
			this.listener = listener;
		}

		@Override
		protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
			ChannelPipeline p = ctx.pipeline();

			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				Http2MultiplexCodecBuilder http2MultiplexCodecBuilder =
						Http2MultiplexCodecBuilder.forServer(new ChannelInitializer() {
							@Override
							protected void initChannel(Channel ch) {
								ChannelOperations.OnSetup ops = (c, l, msg) ->
										new HttpServerOperations(c, l, compressPredicate, msg, forwarded);
								Http2BridgeServerHandler httpServerHandler = new Http2BridgeServerHandler(ops, listener);
								ch.pipeline().addLast(NettyPipeline.HttpServerHandler, httpServerHandler);

								ch.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true));

								ch.pipeline().addLast(NettyPipeline.ReactiveBridge,
										new ChannelOperationsHandler((c, listener, msg) -> null, listener));

								if (log.isDebugEnabled()) {
									log.debug("{} Initialized HTTP/2 pipeline {}", ch, ch.pipeline());
								}
							}
						})
						.initialSettings(Http2Settings.defaultSettings());
				if (p.get(NettyPipeline.LoggingHandler) != null) {
					http2MultiplexCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, HttpServer.class));
				}

				p.addLast(http2MultiplexCodecBuilder.build());
				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				HttpServerCodec httpServerCodec = new HttpServerCodec(line, header, chunk, validate, buffer);
				p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpCodec, httpServerCodec);

				boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;
				if (alwaysCompress) {
					p.addBefore(NettyPipeline.HttpServerHandler, NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
				}

				HttpServerHandler httpServerHandler = new HttpServerHandler((c, l, msg) ->
						new HttpServerOperations(c, l, compressPredicate, msg, forwarded), listener);
				p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpServerHandler, httpServerHandler);
				return;
			}

			throw new IllegalStateException("unknown protocol: " + protocol);
		}
	}
}
