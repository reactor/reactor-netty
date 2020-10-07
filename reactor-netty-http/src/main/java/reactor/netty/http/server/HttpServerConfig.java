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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
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
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.util.AsciiString;
import reactor.core.publisher.Mono;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Http2SettingsSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.transport.ServerTransportConfig;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import static reactor.netty.ReactorNetty.ACCESS_LOG_ENABLED;
import static reactor.netty.ReactorNetty.format;

/**
 * Encapsulate all necessary configuration for HTTP server transport. The public API is read-only.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @author Andrey Shlykov
 */
public final class HttpServerConfig extends ServerTransportConfig<HttpServerConfig> {

	/**
	 * Return the configured compression predicate or null.
	 *
	 * @return the configured compression predicate or null
	 */
	@Nullable
	public BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate() {
		return compressPredicate;
	}

	/**
	 * Return the configured {@link ServerCookieDecoder} or the default {@link ServerCookieDecoder#STRICT}.
	 *
	 * @return the configured {@link ServerCookieDecoder} or the default {@link ServerCookieDecoder#STRICT}
	 */
	public ServerCookieDecoder cookieDecoder() {
		return cookieDecoder;
	}

	/**
	 * Return the configured {@link ServerCookieEncoder} or the default {@link ServerCookieEncoder#STRICT}.
	 *
	 * @return the configured {@link ServerCookieEncoder} or the default {@link ServerCookieEncoder#STRICT}
	 */
	public ServerCookieEncoder cookieEncoder() {
		return cookieEncoder;
	}

	/**
	 * Return the configured HTTP request decoder options or the default.
	 *
	 * @return the configured HTTP request decoder options or the default
	 */
	public HttpRequestDecoderSpec decoder() {
		return decoder;
	}

	/**
	 * Return the HTTP/2 configuration
	 *
	 * @return the HTTP/2 configuration
	 */
	public Http2SettingsSpec http2SettingsSpec() {
		return http2Settings;
	}

	/**
	 * Returns whether that {@link HttpServer} supports the {@code "Forwarded"} and {@code "X-Forwarded-*"}
	 * HTTP request headers for deriving information about the connection.
	 *
	 * @return true if that {@link HttpServer} supports the {@code "Forwarded"} and {@code "X-Forwarded-*"}
	 * HTTP request headers for deriving information about the connection
	 */
	public boolean isForwarded() {
		return forwardedHeaderHandler != null;
	}

	/**
	 * Returns true if that {@link HttpServer} secured via SSL transport
	 *
	 * @return true if that {@link HttpServer} secured via SSL transport
	 */
	public boolean isSecure() {
		return sslProvider != null;
	}

	/**
	 * Compression is performed once response size exceeds the minimum compression size in bytes.
	 *
	 * @return the minimum compression size in bytes
	 */
	public int minCompressionSize() {
		return minCompressionSize;
	}

	/**
	 * Return the HTTP protocol to support. Default is {@link HttpProtocol#HTTP11}.
	 *
	 * @return the HTTP protocol to support
	 */
	public HttpProtocol[] protocols() {
		return protocols;
	}

	/**
	 * Return the supported type for the {@code "HAProxy proxy protocol"}.
	 * The default is {@link ProxyProtocolSupportType#OFF}.
	 *
	 * @return the supported type for the {@code "HAProxy proxy protocol"}
	 */
	public ProxyProtocolSupportType proxyProtocolSupportType() {
		return proxyProtocolSupportType;
	}

	/**
	 * Returns the current {@link SslProvider} if that {@link HttpServer} secured via SSL
	 * transport or null.
	 *
	 * @return the current {@link SslProvider} if that {@link HttpServer} secured via SSL
	 * transport or null
	 */
	@Nullable
	public SslProvider sslProvider() {
		return sslProvider;
	}

	/**
	 * Returns the configured function that receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#URI} tag
	 *
	 * @return the configured function that receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#URI} tag
	 */
	@Nullable
	public Function<String, String> uriTagValue() {
		return uriTagValue;
	}


	// Protected/Package private write API

	BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
	ServerCookieDecoder                                     cookieDecoder;
	ServerCookieEncoder                                     cookieEncoder;
	HttpRequestDecoderSpec                                  decoder;
	BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	Http2SettingsSpec                                       http2Settings;
	BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle;
	int                                                     minCompressionSize;
	HttpProtocol[]                                          protocols;
	int                                                     _protocols;
	ProxyProtocolSupportType                                proxyProtocolSupportType;
	SslProvider                                             sslProvider;
	Function<String, String>                                uriTagValue;

	HttpServerConfig(Map<ChannelOption<?>, ?> options, Map<ChannelOption<?>, ?> childOptions, Supplier<? extends SocketAddress> localAddress) {
		super(options, childOptions, localAddress);
		this.cookieDecoder = ServerCookieDecoder.STRICT;
		this.cookieEncoder = ServerCookieEncoder.STRICT;
		this.decoder = new HttpRequestDecoderSpec();
		this.forwardedHeaderHandler = null;
		this.minCompressionSize = -1;
		this.protocols = new HttpProtocol[]{HttpProtocol.HTTP11};
		this._protocols = h11;
		this.proxyProtocolSupportType = ProxyProtocolSupportType.OFF;
	}

	HttpServerConfig(HttpServerConfig parent) {
		super(parent);
		this.compressPredicate = parent.compressPredicate;
		this.cookieDecoder = parent.cookieDecoder;
		this.cookieEncoder = parent.cookieEncoder;
		this.decoder = parent.decoder;
		this.forwardedHeaderHandler = parent.forwardedHeaderHandler;
		this.http2Settings = parent.http2Settings;
		this.mapHandle = parent.mapHandle;
		this.minCompressionSize = parent.minCompressionSize;
		this.protocols = parent.protocols;
		this._protocols = parent._protocols;
		this.proxyProtocolSupportType = parent.proxyProtocolSupportType;
		this.sslProvider = parent.sslProvider;
		this.uriTagValue = parent.uriTagValue;
	}

	@Override
	protected LoggingHandler defaultLoggingHandler() {
		return LOGGING_HANDLER;
	}

	@Override
	protected LoopResources defaultLoopResources() {
		return HttpResources.get();
	}

	@Override
	protected ChannelMetricsRecorder defaultMetricsRecorder() {
		return MicrometerHttpServerMetricsRecorder.INSTANCE;
	}

	@Override
	protected ChannelPipelineConfigurer defaultOnChannelInit() {
		return super.defaultOnChannelInit()
		            .then(new HttpServerChannelInitializer(compressPredicate, cookieDecoder, cookieEncoder,
		                decoder, forwardedHeaderHandler, http2Settings(), mapHandle, metricsRecorder(), minCompressionSize, channelOperationsProvider(),
		                    _protocols, proxyProtocolSupportType, sslProvider, uriTagValue));
	}

	@Override
	protected void loggingHandler(LoggingHandler loggingHandler) {
		super.loggingHandler(loggingHandler);
	}

	@Override
	protected void metricsRecorder(@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder) {
		super.metricsRecorder(metricsRecorder);
	}

	void protocols(HttpProtocol... protocols) {
		this.protocols = protocols;
		int _protocols = 0;

		for (HttpProtocol p : protocols) {
			if (p == HttpProtocol.HTTP11) {
				_protocols |= h11;
			}
			else if (p == HttpProtocol.H2) {
				_protocols |= h2;
			}
			else if (p == HttpProtocol.H2C) {
				_protocols |= h2c;
			}
		}
		this._protocols = _protocols;
	}

	Http2Settings http2Settings() {
		Http2Settings settings = Http2Settings.defaultSettings();

		if (http2Settings != null) {
			Long headerTableSize = http2Settings.headerTableSize();
			if (headerTableSize != null) {
				settings.headerTableSize(headerTableSize);
			}

			Integer initialWindowSize = http2Settings.initialWindowSize();
			if (initialWindowSize != null) {
				settings.initialWindowSize(initialWindowSize);
			}

			Long maxConcurrentStreams = http2Settings.maxConcurrentStreams();
			if (maxConcurrentStreams != null) {
				settings.maxConcurrentStreams(maxConcurrentStreams);
			}

			Integer maxFrameSize = http2Settings.maxFrameSize();
			if (maxFrameSize != null) {
				settings.maxFrameSize(maxFrameSize);
			}

			settings.maxHeaderListSize(http2Settings.maxHeaderListSize());

			Boolean pushEnabled = http2Settings.pushEnabled();
			if (pushEnabled != null) {
				settings.pushEnabled(pushEnabled);
			}
		}

		return settings;
	}

	static void addStreamHandlers(Channel ch, ChannelOperations.OnSetup opsFactory, ConnectionObserver listener,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			ServerCookieEncoder encoder, ServerCookieDecoder decoder,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle) {
		if (ACCESS_LOG) {
			ch.pipeline()
			  .addLast(NettyPipeline.AccessLogHandler, new AccessLogHandlerH2());
		}
		ch.pipeline()
		  .addLast(NettyPipeline.H2ToHttp11Codec, new Http2StreamFrameToHttpObjectCodec(true))
		  .addLast(NettyPipeline.HttpTrafficHandler,
		           new Http2StreamBridgeServerHandler(listener, forwardedHeaderHandler, encoder, decoder, mapHandle));

		ChannelOperations.addReactiveBridge(ch, opsFactory, listener);

		if (log.isDebugEnabled()) {
			log.debug(format(ch, "Initialized HTTP/2 stream pipeline {}"), ch.pipeline());
		}
	}

	@Nullable
	static BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate(
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate,
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

	static void configureH2Pipeline(ChannelPipeline p,
			ServerCookieDecoder cookieDecoder,
			ServerCookieEncoder cookieEncoder,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			Http2Settings http2Settings,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			ChannelOperations.OnSetup opsFactory,
			boolean validate) {
		p.remove(NettyPipeline.ReactiveBridge);

		Http2FrameCodecBuilder http2FrameCodecBuilder =
				Http2FrameCodecBuilder.forServer()
				                      .validateHeaders(validate)
				                      .initialSettings(http2Settings);

		if (p.get(NettyPipeline.LoggingHandler) != null) {
			http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG,
					"reactor.netty.http.server.h2"));
		}

		p.addLast(NettyPipeline.HttpCodec, http2FrameCodecBuilder.build())
		 .addLast(NettyPipeline.H2MultiplexHandler,
		          new Http2MultiplexHandler(new H2Codec(opsFactory, listener, forwardedHeaderHandler, cookieEncoder, cookieDecoder, mapHandle)));
	}

	static void configureHttp11OrH2CleartextPipeline(ChannelPipeline p,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder cookieDecoder,
			ServerCookieEncoder cookieEncoder,
			HttpRequestDecoderSpec decoder,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			Http2Settings http2Settings,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Function<String, String> uriTagValue) {
		HttpServerCodec httpServerCodec =
				new HttpServerCodec(decoder.maxInitialLineLength(), decoder.maxHeaderSize(),
						decoder.maxChunkSize(), decoder.validateHeaders(), decoder.initialBufferSize());

		Http11OrH2CleartextCodec
				upgrader = new Http11OrH2CleartextCodec(cookieDecoder, cookieEncoder, p.get(NettyPipeline.LoggingHandler) != null,
					forwardedHeaderHandler, http2Settings, listener, mapHandle, opsFactory, decoder.validateHeaders());

		ChannelHandler http2ServerHandler = new H2CleartextCodec(upgrader);
		CleartextHttp2ServerUpgradeHandler h2cUpgradeHandler = new CleartextHttp2ServerUpgradeHandler(
				httpServerCodec,
				new HttpServerUpgradeHandler(httpServerCodec, upgrader, decoder.h2cMaxContentLength()),
				http2ServerHandler);

		p.addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.H2CUpgradeHandler, h2cUpgradeHandler)
		 .addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpTrafficHandler,
		            new HttpTrafficHandler(listener, forwardedHeaderHandler, compressPredicate, cookieEncoder, cookieDecoder, mapHandle));

		if (ACCESS_LOG) {
			p.addAfter(NettyPipeline.H2CUpgradeHandler, NettyPipeline.AccessLogHandler, new AccessLogHandler());
		}

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			p.addBefore(NettyPipeline.HttpTrafficHandler, NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
		}

		if (metricsRecorder != null) {
			ChannelMetricsRecorder channelMetricsRecorder = metricsRecorder.get();
			if (channelMetricsRecorder instanceof HttpServerMetricsRecorder) {
				p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.HttpMetricsHandler,
				           new HttpServerMetricsHandler((HttpServerMetricsRecorder) channelMetricsRecorder, uriTagValue));
				if (channelMetricsRecorder instanceof MicrometerHttpServerMetricsRecorder) {
					// MicrometerHttpServerMetricsRecorder does not implement metrics on protocol level
					// ChannelMetricsHandler will be removed from the pipeline
					p.remove(NettyPipeline.ChannelMetricsHandler);
				}
			}
		}
	}

	static void configureHttp11Pipeline(ChannelPipeline p,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder cookieDecoder,
			ServerCookieEncoder cookieEncoder,
			HttpRequestDecoderSpec decoder,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder,
			int minCompressionSize,
			@Nullable Function<String, String> uriTagValue) {
		p.addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpCodec,
		            new HttpServerCodec(decoder.maxInitialLineLength(), decoder.maxHeaderSize(),
		                    decoder.maxChunkSize(), decoder.validateHeaders(), decoder.initialBufferSize()))
		 .addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpTrafficHandler,
		            new HttpTrafficHandler(listener, forwardedHeaderHandler, compressPredicate, cookieEncoder, cookieDecoder, mapHandle));

		if (ACCESS_LOG) {
			p.addAfter(NettyPipeline.HttpCodec, NettyPipeline.AccessLogHandler, new AccessLogHandler());
		}

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			p.addBefore(NettyPipeline.HttpTrafficHandler, NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
		}

		if (metricsRecorder != null) {
			ChannelMetricsRecorder channelMetricsRecorder = metricsRecorder.get();
			if (channelMetricsRecorder instanceof HttpServerMetricsRecorder) {
				p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.HttpMetricsHandler,
				           new HttpServerMetricsHandler((HttpServerMetricsRecorder) channelMetricsRecorder, uriTagValue));
				if (channelMetricsRecorder instanceof MicrometerHttpServerMetricsRecorder) {
					// MicrometerHttpServerMetricsRecorder does not implement metrics on protocol level
					// ChannelMetricsHandler will be removed from the pipeline
					p.remove(NettyPipeline.ChannelMetricsHandler);
				}
			}
		}
	}

	static final boolean ACCESS_LOG = Boolean.parseBoolean(System.getProperty(ACCESS_LOG_ENABLED, "false"));

	static final int h2 = 0b010;

	static final int h2c = 0b001;

	static final int h11 = 0b100;

	static final int h11orH2 = h11 | h2;

	static final int h11orH2C = h11 | h2c;

	static final Logger log = Loggers.getLogger(HttpServerConfig.class);

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(HttpServer.class);

	/**
	 * Default value whether the SSL debugging on the server side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	static final boolean SSL_DEBUG = Boolean.parseBoolean(System.getProperty(ReactorNetty.SSL_SERVER_DEBUG, "false"));

	static final class H2CleartextCodec extends ChannelHandlerAdapter {

		final Http11OrH2CleartextCodec upgrader;

		H2CleartextCodec(Http11OrH2CleartextCodec upgrader) {
			this.upgrader = upgrader;
		}

		@Override
		public void handlerAdded(ChannelHandlerContext ctx) {
			ChannelPipeline pipeline = ctx.pipeline();
			pipeline.addAfter(ctx.name(), NettyPipeline.HttpCodec, upgrader.http2FrameCodec)
			        .addAfter(NettyPipeline.HttpCodec, NettyPipeline.H2MultiplexHandler, new Http2MultiplexHandler(upgrader))
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
	}

	static final class H2Codec extends ChannelInitializer<Channel> {

		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final ConnectionObserver                                      listener;
		final ServerCookieEncoder                                     cookieEncoder;
		final ServerCookieDecoder                                     cookieDecoder;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>      mapHandle;
		final ChannelOperations.OnSetup                               opsFactory;

		H2Codec(ChannelOperations.OnSetup opsFactory, ConnectionObserver listener,
				@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
				ServerCookieEncoder encoder, ServerCookieDecoder decoder,
				@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle) {
			this.forwardedHeaderHandler = forwardedHeaderHandler;
			this.listener = listener;
			this.cookieEncoder = encoder;
			this.cookieDecoder = decoder;
			this.mapHandle = mapHandle;
			this.opsFactory = opsFactory;
		}

		@Override
		protected void initChannel(Channel ch) {
			ch.pipeline().remove(this);
			addStreamHandlers(ch, opsFactory, listener, forwardedHeaderHandler, cookieEncoder, cookieDecoder, mapHandle);
		}
	}

	static final class Http11OrH2CleartextCodec extends ChannelInitializer<Channel>
			implements HttpServerUpgradeHandler.UpgradeCodecFactory {

		final ServerCookieDecoder                                     cookieDecoder;
		final ServerCookieEncoder                                     cookieEncoder;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2FrameCodec                                         http2FrameCodec;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>      mapHandle;
		final ChannelOperations.OnSetup                               opsFactory;

		Http11OrH2CleartextCodec(
				ServerCookieDecoder cookieDecoder,
				ServerCookieEncoder cookieEncoder,
				boolean debug,
				@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
				Http2Settings http2Settings,
				ConnectionObserver listener,
				@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
				ChannelOperations.OnSetup opsFactory,
				boolean validate) {
			this.cookieDecoder = cookieDecoder;
			this.cookieEncoder = cookieEncoder;
			this.forwardedHeaderHandler = forwardedHeaderHandler;
			Http2FrameCodecBuilder http2FrameCodecBuilder =
					Http2FrameCodecBuilder.forServer()
					                      .validateHeaders(validate)
					                      .initialSettings(http2Settings);

			if (debug) {
				http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(
						LogLevel.DEBUG,
						"reactor.netty.http.server.h2"));
			}
			this.http2FrameCodec = http2FrameCodecBuilder.build();
			this.listener = listener;
			this.mapHandle = mapHandle;
			this.opsFactory = opsFactory;
		}

		/**
		 * Inline channel initializer
		 */
		@Override
		protected void initChannel(Channel ch) {
			ch.pipeline().remove(this);
			addStreamHandlers(ch, opsFactory, listener, forwardedHeaderHandler, cookieEncoder, cookieDecoder, mapHandle);
		}

		@Override
		@Nullable
		public HttpServerUpgradeHandler.UpgradeCodec newUpgradeCodec(CharSequence protocol) {
			if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
				return new Http2ServerUpgradeCodec(http2FrameCodec, new Http2MultiplexHandler(this));
			}
			else {
				return null;
			}
		}
	}

	static final class H2OrHttp11Codec extends ApplicationProtocolNegotiationHandler {

		final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
		final ServerCookieDecoder                                     cookieDecoder;
		final ServerCookieEncoder                                     cookieEncoder;
		final HttpRequestDecoderSpec                                  decoder;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2Settings                                           http2Settings;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>      mapHandle;
		final Supplier<? extends ChannelMetricsRecorder>              metricsRecorder;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;
		final Function<String, String>                                uriTagValue;

		H2OrHttp11Codec(
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				ServerCookieDecoder cookieDecoder,
				ServerCookieEncoder cookieEncoder,
				HttpRequestDecoderSpec decoder,
				@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
				Http2Settings http2Settings,
				ConnectionObserver listener,
				@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
				@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder,
				int minCompressionSize,
				ChannelOperations.OnSetup opsFactory,
				@Nullable Function<String, String> uriTagValue) {
			super(ApplicationProtocolNames.HTTP_1_1);
			this.compressPredicate = compressPredicate;
			this.cookieDecoder = cookieDecoder;
			this.cookieEncoder = cookieEncoder;
			this.decoder = decoder;
			this.forwardedHeaderHandler = forwardedHeaderHandler;
			this.http2Settings = http2Settings;
			this.listener = listener;
			this.mapHandle = mapHandle;
			this.metricsRecorder = metricsRecorder;
			this.minCompressionSize = minCompressionSize;
			this.opsFactory = opsFactory;
			this.uriTagValue = uriTagValue;
		}

		@Override
		protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(), "Negotiated application-level protocol [" + protocol + "]"));
			}

			ChannelPipeline p = ctx.pipeline();

			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				configureH2Pipeline(p, cookieDecoder, cookieEncoder, forwardedHeaderHandler, http2Settings,
						listener, mapHandle, opsFactory, decoder.validateHeaders());
				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				configureHttp11Pipeline(p, compressPredicate, cookieDecoder, cookieEncoder, decoder, forwardedHeaderHandler,
						listener, mapHandle, metricsRecorder, minCompressionSize, uriTagValue);
				return;
			}

			throw new IllegalStateException("unknown protocol: " + protocol);
		}
	}

	static final class HttpServerChannelInitializer implements ChannelPipelineConfigurer {

		final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
		final ServerCookieDecoder                                     cookieDecoder;
		final ServerCookieEncoder                                     cookieEncoder;
		final HttpRequestDecoderSpec                                  decoder;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2Settings                                           http2Settings;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>      mapHandle;
		final Supplier<? extends ChannelMetricsRecorder>              metricsRecorder;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;
		final int                                                     protocols;
		final ProxyProtocolSupportType                                proxyProtocolSupportType;
		final SslProvider                                             sslProvider;
		final Function<String, String>                                uriTagValue;

		HttpServerChannelInitializer(
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				ServerCookieDecoder cookieDecoder,
				ServerCookieEncoder cookieEncoder,
				HttpRequestDecoderSpec decoder,
				@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
				Http2Settings http2Settings,
				@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
				@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder,
				int minCompressionSize,
				ChannelOperations.OnSetup opsFactory,
				int protocols,
				ProxyProtocolSupportType proxyProtocolSupportType,
				@Nullable SslProvider sslProvider,
				@Nullable Function<String, String> uriTagValue) {
			this.compressPredicate = compressPredicate;
			this.cookieDecoder = cookieDecoder;
			this.cookieEncoder = cookieEncoder;
			this.decoder = decoder;
			this.forwardedHeaderHandler = forwardedHeaderHandler;
			this.http2Settings = http2Settings;
			this.mapHandle = mapHandle;
			this.metricsRecorder = metricsRecorder;
			this.minCompressionSize = minCompressionSize;
			this.opsFactory = opsFactory;
			this.protocols = protocols;
			this.proxyProtocolSupportType = proxyProtocolSupportType;
			this.sslProvider = sslProvider;
			this.uriTagValue = uriTagValue;
		}

		@Override
		public void onChannelInit(ConnectionObserver observer, Channel channel, @Nullable SocketAddress remoteAddress) {
			boolean needRead = false;

			if (sslProvider != null) {
				sslProvider.addSslHandler(channel, remoteAddress, SSL_DEBUG);

				if ((protocols & h11orH2) == h11orH2) {
					channel.pipeline()
					       .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.H2OrHttp11Codec, new H2OrHttp11Codec(
					               compressPredicate(compressPredicate, minCompressionSize),
					               cookieDecoder,
					               cookieEncoder,
					               decoder,
					               forwardedHeaderHandler,
					               http2Settings,
					               observer,
					               mapHandle,
					               metricsRecorder,
					               minCompressionSize,
					               opsFactory,
					               uriTagValue));
				}
				else if ((protocols & h11) == h11) {
					configureHttp11Pipeline(
							channel.pipeline(),
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							decoder,
							forwardedHeaderHandler,
							observer,
							mapHandle,
							metricsRecorder,
							minCompressionSize,
							uriTagValue);
				}
				else if ((protocols & h2) == h2) {
					configureH2Pipeline(
							channel.pipeline(),
							cookieDecoder,
							cookieEncoder,
							forwardedHeaderHandler,
							http2Settings,
							observer,
							mapHandle,
							opsFactory,
							decoder.validateHeaders());
				}
			}
			else {
				if ((protocols & h11orH2C) == h11orH2C) {
					configureHttp11OrH2CleartextPipeline(
							channel.pipeline(),
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							decoder,
							forwardedHeaderHandler,
							http2Settings,
							observer,
							mapHandle,
							metricsRecorder,
							minCompressionSize,
							opsFactory,
							uriTagValue);
				}
				else if ((protocols & h11) == h11) {
					configureHttp11Pipeline(
							channel.pipeline(),
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							decoder,
							forwardedHeaderHandler,
							observer,
							mapHandle,
							metricsRecorder,
							minCompressionSize,
							uriTagValue);
				}
				else if ((protocols & h2c) == h2c) {
					configureH2Pipeline(
							channel.pipeline(),
							cookieDecoder,
							cookieEncoder,
							forwardedHeaderHandler,
							http2Settings,
							observer,
							mapHandle,
							opsFactory,
							decoder.validateHeaders());
					needRead = true;
				}
			}

			if (proxyProtocolSupportType == ProxyProtocolSupportType.ON) {
				channel.pipeline()
				       .addFirst(NettyPipeline.ProxyProtocolDecoder, new HAProxyMessageDecoder())
				       .addAfter(NettyPipeline.ProxyProtocolDecoder,
				                 NettyPipeline.ProxyProtocolReader,
				                 new HAProxyMessageReader());
			}
			else if (proxyProtocolSupportType == ProxyProtocolSupportType.AUTO) {
				channel.pipeline()
				       .addFirst(NettyPipeline.ProxyProtocolDecoder, new HAProxyMessageDetector());
			}

			if (needRead) {
				channel.read();
			}
		}
	}
}
