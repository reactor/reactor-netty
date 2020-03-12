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

package reactor.netty.http.server;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.util.AsciiString;
import reactor.core.publisher.Mono;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.channel.ChannelMetricsHandler;
import reactor.netty.tcp.TcpServer;
import reactor.util.annotation.Nullable;

import static reactor.netty.ReactorNetty.ACCESS_LOG_ENABLED;
import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 */
final class HttpServerBind extends HttpServer
		implements Function<ServerBootstrap, ServerBootstrap> {

	static final HttpServerBind INSTANCE = new HttpServerBind();

	static final Function<DisposableServer, DisposableServer> CLEANUP_GLOBAL_RESOURCE = DisposableBind::new;

	static final boolean ACCESS_LOG =
			Boolean.parseBoolean(System.getProperty(ACCESS_LOG_ENABLED, "false"));

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
	public Mono<? extends DisposableServer> bind(TcpServer delegate) {
		return delegate.bootstrap(this)
		               .bind()
		               .map(CLEANUP_GLOBAL_RESOURCE);
	}

	@Override
	public ServerBootstrap apply(ServerBootstrap b) {
		HttpServerConfiguration conf = HttpServerConfiguration.getAndClean(b);

		SslProvider ssl = SslProvider.findSslSupport(b);
		if (ssl != null && ssl.getDefaultConfigurationType() == null) {
			if ((conf.protocols & HttpServerConfiguration.h2) == HttpServerConfiguration.h2) {
				ssl = SslProvider.updateDefaultConfiguration(ssl,
						SslProvider.DefaultConfigurationType.H2);
				SslProvider.setBootstrap(b, ssl);
			}
			else {
				ssl = SslProvider.updateDefaultConfiguration(ssl,
						SslProvider.DefaultConfigurationType.TCP);
				SslProvider.setBootstrap(b, ssl);
			}
		}

		if (b.config()
		     .group() == null) {
			LoopResources loops = HttpResources.get();

			EventLoopGroup selector = loops.onServerSelect(LoopResources.DEFAULT_NATIVE);
			EventLoopGroup elg = loops.onServer(LoopResources.DEFAULT_NATIVE);

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
						new Http1OrH2Initializer(conf.decoder.maxInitialLineLength(),
								conf.decoder.maxHeaderSize(),
								conf.decoder.maxChunkSize(),
								conf.decoder.validateHeaders(),
								conf.decoder.initialBufferSize(),
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded,
								conf.cookieEncoder,
								conf.cookieDecoder));
			}
			if ((conf.protocols & HttpServerConfiguration.h11) == HttpServerConfiguration.h11) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new Http1Initializer(conf.decoder.maxInitialLineLength(),
								conf.decoder.maxHeaderSize(),
								conf.decoder.maxChunkSize(),
								conf.decoder.validateHeaders(),
								conf.decoder.initialBufferSize(),
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded,
								conf.cookieEncoder,
								conf.cookieDecoder));
			}
			if ((conf.protocols & HttpServerConfiguration.h2) == HttpServerConfiguration.h2) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new H2Initializer(
								conf.decoder.validateHeaders(),
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded,
								conf.cookieEncoder,
								conf.cookieDecoder));
			}
		}
		else {
			if ((conf.protocols & HttpServerConfiguration.h2) == HttpServerConfiguration.h2) {
				throw new IllegalArgumentException(
						"Configured H2 protocol without TLS. Use" +
								" a clear-text h2 protocol via HttpServer#protocol or configure TLS" +
								" via HttpServer#secure");
			}
			if ((conf.protocols & HttpServerConfiguration.h11orH2c) == HttpServerConfiguration.h11orH2c) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new Http1OrH2CleartextInitializer(conf.decoder.maxInitialLineLength(),
								conf.decoder.maxHeaderSize(),
								conf.decoder.maxChunkSize(),
								conf.decoder.validateHeaders(),
								conf.decoder.initialBufferSize(),
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded,
								conf.cookieEncoder,
								conf.cookieDecoder));
			}
			if ((conf.protocols & HttpServerConfiguration.h11) == HttpServerConfiguration.h11) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new Http1Initializer(conf.decoder.maxInitialLineLength(),
								conf.decoder.maxHeaderSize(),
								conf.decoder.maxChunkSize(),
								conf.decoder.validateHeaders(),
								conf.decoder.initialBufferSize(),
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded,
								conf.cookieEncoder,
								conf.cookieDecoder));
			}
			if ((conf.protocols & HttpServerConfiguration.h2c) == HttpServerConfiguration.h2c) {
				return BootstrapHandlers.updateConfiguration(b,
						NettyPipeline.HttpInitializer,
						new H2CleartextInitializer(
								conf.decoder.validateHeaders(),
								conf.minCompressionSize,
								compressPredicate(conf.compressPredicate, conf.minCompressionSize),
								conf.forwarded,
								conf.cookieEncoder,
								conf.cookieDecoder));
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
			return compressionPredicate;
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

	static void addStreamHandlers(Channel ch, ConnectionObserver listener, boolean readForwardHeaders,
			ServerCookieEncoder encoder, ServerCookieDecoder decoder) {
		if (ACCESS_LOG) {
			ch.pipeline()
			  .addLast(NettyPipeline.AccessLogHandler, new AccessLogHandlerH2());
		}
		ch.pipeline()
		  .addLast(new Http2StreamFrameToHttpObjectCodec(true))
		  .addLast(new Http2StreamBridgeHandler(listener, readForwardHeaders, encoder, decoder));

		ChannelOperations.addReactiveBridge(ch, ChannelOperations.OnSetup.empty(), listener);

		if (log.isDebugEnabled()) {
			log.debug(format(ch, "Initialized HTTP/2 pipeline {}"), ch.pipeline());
		}
	}


	static final class DisposableBind implements DisposableServer {

		final DisposableServer server;

		DisposableBind(DisposableServer server) {
			this.server = server;
		}

		@Override
		public void dispose() {
			server.dispose();

			HttpResources.get()
			             .disposeWhen(server.address());
		}

		@Override
		public Channel channel() {
			return server.channel();
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
		final ServerCookieEncoder                                cookieEncoder;
		final ServerCookieDecoder                                cookieDecoder;

		Http1Initializer(int line,
				int header,
				int chunk,
				boolean validate,
				int buffer,
				int minCompressionSize,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded,
				ServerCookieEncoder encoder,
				ServerCookieDecoder decoder) {
			this.line = line;
			this.header = header;
			this.chunk = chunk;
			this.validate = validate;
			this.buffer = buffer;
			this.minCompressionSize = minCompressionSize;
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
			this.cookieEncoder = encoder;
			this.cookieDecoder = decoder;
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			ChannelPipeline p = channel.pipeline();

			p.addLast(NettyPipeline.HttpCodec, new HttpServerCodec(line, header, chunk, validate, buffer));

			if (ACCESS_LOG) {
				p.addLast(NettyPipeline.AccessLogHandler, new AccessLogHandler());
			}

			boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

			if (alwaysCompress) {
				p.addLast(NettyPipeline.CompressionHandler,
						new SimpleCompressionHandler());
			}

			p.addLast(NettyPipeline.HttpTrafficHandler,
					new HttpTrafficHandler(listener, forwarded, compressPredicate, cookieEncoder, cookieDecoder));

			ChannelHandler handler = p.get(NettyPipeline.ChannelMetricsHandler);
			if (handler != null) {
				ChannelMetricsRecorder channelMetricsRecorder = ((ChannelMetricsHandler) handler).recorder();
				if (channelMetricsRecorder instanceof HttpServerMetricsRecorder) {
					p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.HttpMetricsHandler,
							new HttpServerMetricsHandler((HttpServerMetricsRecorder) channelMetricsRecorder));
				}
			}

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
		final ServerCookieEncoder                                cookieEncoder;
		final ServerCookieDecoder                                cookieDecoder;

		Http1OrH2CleartextInitializer(int line,
				int header,
				int chunk,
				boolean validate,
				int buffer,
				int minCompressionSize,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded,
				ServerCookieEncoder encoder,
				ServerCookieDecoder decoder) {
			this.line = line;
			this.header = header;
			this.chunk = chunk;
			this.validate = validate;
			this.buffer = buffer;
			this.minCompressionSize = minCompressionSize;
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
			this.cookieEncoder = encoder;
			this.cookieDecoder = decoder;
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

			final ChannelHandler http2ServerHandler = new ChannelHandlerAdapter() {
				@Override
				public void handlerAdded(ChannelHandlerContext ctx) {
					ChannelPipeline pipeline = ctx.pipeline();
					pipeline.addAfter(ctx.name(), NettyPipeline.HttpCodec, upgrader.http2FrameCodec)
					        .addAfter(NettyPipeline.HttpCodec, null, new Http2MultiplexHandler(upgrader))
					        .remove(this);
					if (pipeline.get(NettyPipeline.AccessLogHandler) != null){
						pipeline.remove(NettyPipeline.AccessLogHandler);
					}
					if (pipeline.get(NettyPipeline.CompressionHandler) != null) {
						pipeline.remove(NettyPipeline.CompressionHandler);
					}
					pipeline.remove(NettyPipeline.HttpTrafficHandler);
					pipeline.remove(NettyPipeline.ReactiveBridge);
				}
			};
			final CleartextHttp2ServerUpgradeHandler h2cUpgradeHandler = new CleartextHttp2ServerUpgradeHandler(
					httpServerCodec,
					new HttpServerUpgradeHandler(httpServerCodec, upgrader),
					http2ServerHandler);

			p.addLast(h2cUpgradeHandler);

			if (ACCESS_LOG) {
				p.addLast(NettyPipeline.AccessLogHandler, new AccessLogHandler());
			}

			boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

			if (alwaysCompress) {
				p.addLast(NettyPipeline.CompressionHandler,
						new SimpleCompressionHandler());
			}

			p.addLast(NettyPipeline.HttpTrafficHandler,
					new HttpTrafficHandler(listener, forwarded, compressPredicate, cookieEncoder, cookieDecoder));

			ChannelHandler handler = p.get(NettyPipeline.ChannelMetricsHandler);
			if (handler != null) {
				ChannelMetricsRecorder channelMetricsRecorder = ((ChannelMetricsHandler) handler).recorder();
				if (channelMetricsRecorder instanceof HttpServerMetricsRecorder) {
					p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.HttpMetricsHandler,
							new HttpServerMetricsHandler((HttpServerMetricsRecorder) channelMetricsRecorder));
				}
			}
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
		final Http2FrameCodec               http2FrameCodec;

		Http1OrH2CleartextCodec(Http1OrH2CleartextInitializer parent, ConnectionObserver listener, boolean debug) {
			this.parent = parent;
			this.listener = listener;
			Http2FrameCodecBuilder http2FrameCodecBuilder =
					Http2FrameCodecBuilder.forServer()
					                      .validateHeaders(parent.validate)
					                      .initialSettings(Http2Settings.defaultSettings());

			if (debug) {
				http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(
						LogLevel.DEBUG,
						"reactor.netty.http.server.h2.cleartext"));
			}
			this.http2FrameCodec = http2FrameCodecBuilder.build();
		}

		/**
		 * Inline channel initializer
		 */
		@Override
		protected void initChannel(Channel ch) {
			addStreamHandlers(ch, listener, parent.forwarded, parent.cookieEncoder, parent.cookieDecoder);
		}

		@Override
		@Nullable
		public HttpServerUpgradeHandler.UpgradeCodec newUpgradeCodec(CharSequence protocol) {
			if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME,
					protocol)) {
				return new Http2ServerUpgradeCodec(http2FrameCodec, new Http2MultiplexHandler(this));
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
		final ServerCookieEncoder                                cookieEncoder;
		final ServerCookieDecoder                                cookieDecoder;

		H2CleartextInitializer(
				boolean validate,
				int minCompressionSize,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded,
				ServerCookieEncoder encoder,
				ServerCookieDecoder decoder) {
			this.validate = validate;
			this.minCompressionSize = minCompressionSize;
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
			this.cookieEncoder = encoder;
			this.cookieDecoder = decoder;
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			ChannelPipeline p = channel.pipeline();

			Http2FrameCodecBuilder http2FrameCodecBuilder =
					Http2FrameCodecBuilder.forServer()
					                      .validateHeaders(validate)
					                      .initialSettings(Http2Settings.defaultSettings());

			if (p.get(NettyPipeline.LoggingHandler) != null) {
				http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(
						LogLevel.DEBUG,
						"reactor.netty.http.server.h2.cleartext"));
			}

			p.addLast(NettyPipeline.HttpCodec, http2FrameCodecBuilder.build())
			 .addLast(new Http2MultiplexHandler(
			        new Http2StreamInitializer(listener, forwarded, cookieEncoder, cookieDecoder)));

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
		final ServerCookieEncoder                                cookieEncoder;
		final ServerCookieDecoder                                cookieDecoder;

		Http1OrH2Initializer(
				int line,
				int header,
				int chunk,
				boolean validate,
				int buffer,
				int minCompressionSize,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded,
				ServerCookieEncoder encoder,
				ServerCookieDecoder decoder) {
			this.line = line;
			this.header = header;
			this.chunk = chunk;
			this.validate = validate;
			this.buffer = buffer;
			this.minCompressionSize = minCompressionSize;
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
			this.cookieEncoder = encoder;
			this.cookieDecoder = decoder;
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

				Http2FrameCodecBuilder http2FrameCodecBuilder =
						Http2FrameCodecBuilder.forServer()
						                      .validateHeaders(true)
						                      .initialSettings(Http2Settings.defaultSettings());

				if (p.get(NettyPipeline.LoggingHandler) != null) {
					http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, "reactor.netty.http.server.h2.secure"));
				}

				p.addLast(NettyPipeline.HttpCodec, http2FrameCodecBuilder.build())
				 .addLast(new Http2MultiplexHandler(
				        new Http2StreamInitializer(listener, parent.forwarded,
				            parent.cookieEncoder, parent.cookieDecoder)));

				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {

				p.addBefore(NettyPipeline.ReactiveBridge,
						NettyPipeline.HttpCodec,
						new HttpServerCodec(parent.line, parent.header, parent.chunk, parent.validate, parent.buffer))
				 .addBefore(NettyPipeline.ReactiveBridge,
						 NettyPipeline.HttpTrafficHandler,
						 new HttpTrafficHandler(listener, parent.forwarded, parent.compressPredicate, parent.cookieEncoder, parent.cookieDecoder));

				if (ACCESS_LOG) {
					p.addAfter(NettyPipeline.HttpCodec,
							NettyPipeline.AccessLogHandler, new AccessLogHandler());
				}

				boolean alwaysCompress = parent.compressPredicate == null && parent.minCompressionSize == 0;

				if (alwaysCompress) {
					p.addBefore(NettyPipeline.HttpTrafficHandler,
							NettyPipeline.CompressionHandler,
							new SimpleCompressionHandler());
				}

				ChannelHandler handler = p.get(NettyPipeline.ChannelMetricsHandler);
				if (handler != null) {
					ChannelMetricsRecorder channelMetricsRecorder = ((ChannelMetricsHandler) handler).recorder();
					if (channelMetricsRecorder instanceof HttpServerMetricsRecorder) {
						p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.HttpMetricsHandler,
								new HttpServerMetricsHandler((HttpServerMetricsRecorder) channelMetricsRecorder));
					}
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
		final ServerCookieEncoder                                cookieEncoder;
		final ServerCookieDecoder                                cookieDecoder;

		H2Initializer(
				boolean validate,
				int minCompressionSize,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				boolean forwarded,
				ServerCookieEncoder encoder,
				ServerCookieDecoder decoder) {
			this.validate = validate;
			this.minCompressionSize = minCompressionSize;
			this.compressPredicate = compressPredicate;
			this.forwarded = forwarded;
			this.cookieEncoder = encoder;
			this.cookieDecoder = decoder;
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			ChannelPipeline p = channel.pipeline();

			Http2FrameCodecBuilder http2FrameCodecBuilder =
					Http2FrameCodecBuilder.forServer()
					                      .validateHeaders(validate)
					                      .initialSettings(Http2Settings.defaultSettings());

			if (p.get(NettyPipeline.LoggingHandler) != null) {
				http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG, "reactor.netty.http.server.h2.secured"));
			}

			p.addLast(NettyPipeline.HttpCodec, http2FrameCodecBuilder.build())
			 .addLast(new Http2MultiplexHandler(
			        new Http2StreamInitializer(listener, forwarded, cookieEncoder, cookieDecoder)));
		}
	}

	static final class Http2StreamInitializer extends ChannelInitializer<Channel> {

		final boolean             forwarded;
		final ConnectionObserver  listener;
		final ServerCookieEncoder cookieEncoder;
		final ServerCookieDecoder cookieDecoder;

		Http2StreamInitializer(ConnectionObserver listener, boolean forwarded, ServerCookieEncoder encoder, ServerCookieDecoder decoder) {
			this.forwarded = forwarded;
			this.listener = listener;
			this.cookieEncoder = encoder;
			this.cookieDecoder = decoder;
		}

		@Override
		protected void initChannel(Channel ch) {
			addStreamHandlers(ch, listener, forwarded, cookieEncoder, cookieDecoder);
		}
	}

}
