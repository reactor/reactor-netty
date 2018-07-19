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

package reactor.netty.http.server;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexCodec;
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
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpServer;
import reactor.util.annotation.Nullable;

import static reactor.netty.LogFormatter.format;

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
		HttpServerConfiguration conf = HttpServerConfiguration.getAndClean(b);

		SslProvider ssl = SslProvider.findSslSupport(b);
		if (ssl != null && ssl.getDefaultConfigurationType() == null) {
			switch (conf.protocols) {
				case HttpServerConfiguration.h11:
					ssl = SslProvider.updateDefaultConfiguration(ssl, SslProvider.DefaultConfigurationType.HTTP11);
					SslProvider.updateSslSupport(b, ssl);
					break;
				case HttpServerConfiguration.h2:
					ssl = SslProvider.updateDefaultConfiguration(ssl, SslProvider.DefaultConfigurationType.H2);
					SslProvider.updateSslSupport(b, ssl);
					break;
				default: break;
			}
		}

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

		//remove any OPS since we will initialize below
		BootstrapHandlers.channelOperationFactory(b);

		if (ssl != null) {
			if ((conf.protocols & HttpServerConfiguration.h2c) == HttpServerConfiguration.h2c) {
				throw new IllegalArgumentException("Configured H2 Clear-Text protocol " +
						"with TLS. Use the non clear-text h2 protocol via " +
						"HttpServer#protocol or disable TLS" +
						" via HttpServer#tcpConfiguration(tcp -> tcp.noSSL())");
			}
			if ((conf.protocols & HttpServerConfiguration.h11orH2) == HttpServerConfiguration.h11orH2) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new Http1OrH2Initializer(conf.decoder.maxInitialLineLength,
								conf.decoder.maxHeaderSize,
								conf.decoder.maxChunkSize,
								conf.decoder.validateHeaders,
								conf.decoder.initialBufferSize,
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded));
			}
			if ((conf.protocols & HttpServerConfiguration.h11) == HttpServerConfiguration.h11) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new Http1Initializer(conf.decoder.maxInitialLineLength,
								conf.decoder.maxHeaderSize,
								conf.decoder.maxChunkSize,
								conf.decoder.validateHeaders,
								conf.decoder.initialBufferSize,
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded));
			}
			if ((conf.protocols & HttpServerConfiguration.h2) == HttpServerConfiguration.h2) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new H2Initializer(
								conf.decoder.validateHeaders,
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded));
			}
		}
		else {
			if ((conf.protocols & HttpServerConfiguration.h2) == HttpServerConfiguration.h2) {
				throw new IllegalArgumentException(
						"Configured H2 protocol without TLS. Use" + " a clear-text h2 protocol via HttpServer#protocol or configure TLS" + " via HttpServer#secure");
			}
			if ((conf.protocols & HttpServerConfiguration.h11orH2c) == HttpServerConfiguration.h11orH2c) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new Http1OrH2CleartextInitializer(conf.decoder.maxInitialLineLength,
								conf.decoder.maxHeaderSize,
								conf.decoder.maxChunkSize,
								conf.decoder.validateHeaders,
								conf.decoder.initialBufferSize,
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded));
			}
			if ((conf.protocols & HttpServerConfiguration.h11) == HttpServerConfiguration.h11) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new Http1Initializer(conf.decoder.maxInitialLineLength,
								conf.decoder.maxHeaderSize,
								conf.decoder.maxChunkSize,
								conf.decoder.validateHeaders,
								conf.decoder.initialBufferSize,
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded));
			}
			if ((conf.protocols & HttpServerConfiguration.h2c) == HttpServerConfiguration.h2c) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new H2CleartextInitializer(
								conf.decoder.validateHeaders,
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded));
			}
		}
		throw new IllegalArgumentException("An unknown HttpServer#protocol " +
				"configuration has been provided: "+String.format("0x%x", conf
				.protocols));
	}

	@Nullable
	static BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate(@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
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

	static void addStreamHandlers(Channel ch, ConnectionObserver listener, boolean readForwardHeaders) {
		ch.pipeline()
		  .addLast(new Http2StreamBridgeHandler(listener, readForwardHeaders))
		  .addLast(new Http2StreamFrameToHttpObjectCodec(true));

		ChannelOperations.addReactiveBridge(ch, ChannelOperations.OnSetup.empty(), listener);

		if (log.isDebugEnabled()) {
			log.debug(format(ch, "Initialized HTTP/2 pipeline {}"), ch.pipeline());
		}
	}


	static final class Http1Initializer
			implements BiConsumer<ConnectionObserver, Channel>  {

		final int                                                line;
		final int                                                header;
		final int                                                chunk;
		final boolean                                            validate;
		final int                                                buffer;
		final int                                                minCompressionSize;
		final BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate;
		final boolean                                            forwarded;

		Http1Initializer(int line,
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

			p.addLast(NettyPipeline.HttpCodec, new HttpServerCodec(line, header, chunk, validate, buffer));

			boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

			if (alwaysCompress) {
				p.addLast(NettyPipeline.CompressionHandler,
						new SimpleCompressionHandler());
			}

			p.addLast(NettyPipeline.HttpTrafficHandler,
					new HttpTrafficHandler(listener, forwarded, compressPredicate));
		}
	}

	static final class Http1OrH2CleartextInitializer
			implements BiConsumer<ConnectionObserver, Channel>  {

		final int                                                line;
		final int                                                header;
		final int                                                chunk;
		final boolean                                            validate;
		final int                                                buffer;
		final int                                                minCompressionSize;
		final BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate;
		final boolean                                            forwarded;

		Http1OrH2CleartextInitializer(int line,
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

//			p.addLast(NettyPipeline.HttpCodec, httpServerCodec)
//			 .addLast(new HttpServerUpgradeHandler(httpServerCodec,
//					 new Http1OrH2CleartextCodec(this,/;poõ
//							 listener,
//							 p.get(NettyPipeline.LoggingHandler) != null)));

			Http1OrH2CleartextCodec
					upgrader = new Http1OrH2CleartextCodec(this, listener, p.get(NettyPipeline.LoggingHandler) != null);


			final CleartextHttp2ServerUpgradeHandler h2cUpgradeHandler =
					new CleartextHttp2ServerUpgradeHandler(httpServerCodec, new HttpServerUpgradeHandler(httpServerCodec, upgrader), upgrader.multiplexCodec);

			p.addLast(NettyPipeline.HttpCodec, h2cUpgradeHandler);

			boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

			if (alwaysCompress) {
				p.addLast(NettyPipeline.CompressionHandler,
						new SimpleCompressionHandler());
			}

			p.addLast(NettyPipeline.HttpTrafficHandler,
					new HttpTrafficHandler(listener, forwarded, compressPredicate));
		}
	}

	/**
	 * Initialize Http1 - Http2 pipeline configuration using packet inspection
	 * or cleartext upgrade
	 */
	@ChannelHandler.Sharable
	static final class Http1OrH2CleartextCodec extends ChannelInitializer<Channel>
			implements HttpServerUpgradeHandler.UpgradeCodecFactory {

		final Http1OrH2CleartextInitializer parent;
		final ConnectionObserver            listener;
		final Http2MultiplexCodec           multiplexCodec;

		Http1OrH2CleartextCodec(Http1OrH2CleartextInitializer parent, ConnectionObserver listener, boolean debug) {
			this.parent = parent;
			this.listener = listener;
			Http2MultiplexCodecBuilder http2MultiplexCodecBuilder =
					Http2MultiplexCodecBuilder.forServer(this)
					                          .validateHeaders(parent.validate)
					                          .initialSettings(Http2Settings.defaultSettings());

			if (debug) {
				http2MultiplexCodecBuilder.frameLogger(new Http2FrameLogger(
						LogLevel.DEBUG,
						"reactor.netty.http.server.h2.cleartext"));
			}
			this.multiplexCodec = http2MultiplexCodecBuilder.build();
		}

		/**
		 * Inline channel initializer
		 */
		@Override
		protected void initChannel(Channel ch) {
			addStreamHandlers(ch, listener, parent.forwarded);
		}

		@Override
		@Nullable
		public HttpServerUpgradeHandler.UpgradeCodec newUpgradeCodec(CharSequence protocol) {
			if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME,
					protocol)) {
				return new Http2ServerUpgradeCodec(multiplexCodec);
			}
			else {
				return null;
			}
		}
	}

	static final class H2CleartextInitializer
			implements BiConsumer<ConnectionObserver, Channel>  {

		final boolean                                            validate;
		final int                                                minCompressionSize;
		final BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate;
		final boolean                                            forwarded;

		H2CleartextInitializer(
				boolean validate,
				int minCompressionSize,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded) {
			this.validate = validate;
			this.minCompressionSize = minCompressionSize;
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			ChannelPipeline p = channel.pipeline();

			Http2MultiplexCodecBuilder http2MultiplexCodecBuilder =
					Http2MultiplexCodecBuilder.forServer(new Http2StreamInitializer(listener, forwarded))
					                          .validateHeaders(validate)
					                          .initialSettings(Http2Settings.defaultSettings());

			if (p.get(NettyPipeline.LoggingHandler) != null) {
				http2MultiplexCodecBuilder.frameLogger(new Http2FrameLogger(
						LogLevel.DEBUG,
						"reactor.netty.http.server.h2.cleartext"));
			}

			p.addLast(NettyPipeline.HttpCodec, http2MultiplexCodecBuilder.build());

			channel.read();
		}
	}

	/**
	 * Initialize Http1 - Http2 pipeline configuration using SSL detection
	 */
	static final class Http1OrH2Initializer implements BiConsumer<ConnectionObserver, Channel> {

		final int                                                line;
		final int                                                header;
		final int                                                chunk;
		final boolean                                            validate;
		final int                                                buffer;
		final int                                                minCompressionSize;
		final BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate;
		final boolean                                            forwarded;

		Http1OrH2Initializer(
				int line,
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
		public void accept(ConnectionObserver observer, Channel channel) {
			channel.pipeline()
			       .addLast(new Http1OrH2Codec(this, observer));
		}
	}

	static final class Http1OrH2Codec extends ApplicationProtocolNegotiationHandler {

		final ConnectionObserver listener;
		final Http1OrH2Initializer parent;

		Http1OrH2Codec(Http1OrH2Initializer parent, ConnectionObserver listener) {
			super(ApplicationProtocolNames.HTTP_1_1);
			this.listener = listener;
			this.parent = parent;
		}

		@Override
		protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
			ChannelPipeline p = ctx.pipeline();

			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {

				p.remove(NettyPipeline.ReactiveBridge);

				Http2MultiplexCodecBuilder http2MultiplexCodecBuilder =
						Http2MultiplexCodecBuilder.forServer(new Http2StreamInitializer(listener, parent.forwarded))
						                          .initialSettings(Http2Settings.defaultSettings());

				if (p.get(NettyPipeline.LoggingHandler) != null) {
					http2MultiplexCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, "reactor.netty.http.server.h2.secure"));
				}

				p.addLast(NettyPipeline.HttpCodec, http2MultiplexCodecBuilder.build());

				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {

				p.addBefore(NettyPipeline.ReactiveBridge,
						NettyPipeline.HttpCodec,
						new HttpServerCodec(parent.line, parent.header, parent.chunk, parent.validate, parent.buffer))
				 .addBefore(NettyPipeline.ReactiveBridge,
						 NettyPipeline.HttpTrafficHandler,
						 new HttpTrafficHandler( listener, parent.forwarded, parent.compressPredicate));

				boolean alwaysCompress = parent.compressPredicate == null && parent.minCompressionSize == 0;

				if (alwaysCompress) {
					p.addBefore(NettyPipeline.HttpTrafficHandler,
							NettyPipeline.CompressionHandler,
							new SimpleCompressionHandler());
				}
				return;
			}

			throw new IllegalStateException("unknown protocol: " + protocol);
		}
	}

	static final class H2Initializer
			implements BiConsumer<ConnectionObserver, Channel>  {

		final boolean                                            validate;
		final int                                                minCompressionSize;
		final BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate;
		final boolean                                            forwarded;

		H2Initializer(
				boolean validate,
				int minCompressionSize,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded) {
			this.validate = validate;
			this.minCompressionSize = minCompressionSize;
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			ChannelPipeline p = channel.pipeline();

			Http2MultiplexCodecBuilder http2MultiplexCodecBuilder =
					Http2MultiplexCodecBuilder.forServer(new Http2StreamInitializer
							(listener, forwarded))
					                          .validateHeaders(validate)
					                          .initialSettings(Http2Settings.defaultSettings());

			if (p.get(NettyPipeline.LoggingHandler) != null) {
				http2MultiplexCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, "reactor.netty.http.server.h2.secured"));
			}

			p.addLast(NettyPipeline.HttpCodec, http2MultiplexCodecBuilder.build());
		}
	}

	static final class Http2StreamInitializer extends ChannelInitializer<Channel> {

		final boolean            forwarded;
		final ConnectionObserver listener;

		Http2StreamInitializer(ConnectionObserver listener, boolean forwarded) {
			this.forwarded = forwarded;
			this.listener = listener;
		}

		@Override
		protected void initChannel(Channel ch) {
			addStreamHandlers(ch, listener, forwarded);
		}
	}

}
