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
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.http.server.logging.AccessLogArgProvider;
import reactor.netty.http.server.logging.AccessLogHandlerFactory;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.transport.ServerTransportConfig;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
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
	 * Return the configured idle timeout for the connection when it is waiting for an HTTP request or null.
	 *
	 * @return the configured idle timeout for the connection when it is waiting for an HTTP request or null
	 */
	@Nullable
	public Duration idleTimeout() {
		return idleTimeout;
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

	boolean                                                 accessLogEnabled;
	Function<AccessLogArgProvider, AccessLog>               accessLog;
	BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
	ServerCookieDecoder                                     cookieDecoder;
	ServerCookieEncoder                                     cookieEncoder;
	HttpRequestDecoderSpec                                  decoder;
	BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	Http2SettingsSpec                                       http2Settings;
	Duration                                                idleTimeout;
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
		this.minCompressionSize = -1;
		this.protocols = new HttpProtocol[]{HttpProtocol.HTTP11};
		this._protocols = h11;
		this.proxyProtocolSupportType = ProxyProtocolSupportType.OFF;
		this.accessLogEnabled = ACCESS_LOG;
	}

	HttpServerConfig(HttpServerConfig parent) {
		super(parent);
		this.accessLogEnabled = parent.accessLogEnabled;
		this.accessLog = parent.accessLog;
		this.compressPredicate = parent.compressPredicate;
		this.cookieDecoder = parent.cookieDecoder;
		this.cookieEncoder = parent.cookieEncoder;
		this.decoder = parent.decoder;
		this.forwardedHeaderHandler = parent.forwardedHeaderHandler;
		this.http2Settings = parent.http2Settings;
		this.idleTimeout = parent.idleTimeout;
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
		            .then(new HttpServerChannelInitializer(this));
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
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			ServerCookieEncoder encoder, ServerCookieDecoder decoder,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			int minCompressionSize,
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog) {
		ChannelPipeline pipeline = ch.pipeline();
		if (accessLogEnabled) {
			pipeline.addLast(NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H2.create(accessLog));
		}
		pipeline.addLast(NettyPipeline.H2ToHttp11Codec, new Http2StreamFrameToHttpObjectCodec(true))
		        .addLast(NettyPipeline.HttpTrafficHandler,
		                 new Http2StreamBridgeServerHandler(listener, compressPredicate, forwardedHeaderHandler,
		                         encoder, decoder, mapHandle));

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			pipeline.addLast(NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
		}

		ChannelOperations.addReactiveBridge(ch, opsFactory, listener);

		if (log.isDebugEnabled()) {
			log.debug(format(ch, "Initialized HTTP/2 stream pipeline {}"), pipeline);
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
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder cookieDecoder,
			ServerCookieEncoder cookieEncoder,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			Http2Settings http2Settings,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			boolean validate,
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog) {
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
		          new Http2MultiplexHandler(new H2Codec(opsFactory, listener, compressPredicate, forwardedHeaderHandler,
		                  cookieEncoder, cookieDecoder, mapHandle, minCompressionSize, accessLogEnabled, accessLog)));
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
			@Nullable Function<String, String> uriTagValue,
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
			@Nullable Duration idleTimeout) {
		HttpServerCodec httpServerCodec =
				new HttpServerCodec(decoder.maxInitialLineLength(), decoder.maxHeaderSize(),
						decoder.maxChunkSize(), decoder.validateHeaders(), decoder.initialBufferSize());

		Http11OrH2CleartextCodec
				upgrader = new Http11OrH2CleartextCodec(compressPredicate, cookieDecoder, cookieEncoder,
						p.get(NettyPipeline.LoggingHandler) != null, forwardedHeaderHandler, http2Settings, listener,
						mapHandle, minCompressionSize, opsFactory, decoder.validateHeaders(), accessLogEnabled, accessLog);

		ChannelHandler http2ServerHandler = new H2CleartextCodec(upgrader);
		CleartextHttp2ServerUpgradeHandler h2cUpgradeHandler = new CleartextHttp2ServerUpgradeHandler(
				httpServerCodec,
				new HttpServerUpgradeHandler(httpServerCodec, upgrader, decoder.h2cMaxContentLength()),
				http2ServerHandler);

		p.addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.H2CUpgradeHandler, h2cUpgradeHandler)
		 .addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpTrafficHandler,
		            new HttpTrafficHandler(listener, forwardedHeaderHandler, compressPredicate, cookieEncoder,
		                    cookieDecoder, mapHandle, idleTimeout));

		if (accessLogEnabled) {
			p.addAfter(NettyPipeline.H2CUpgradeHandler, NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H1.create(accessLog));
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
			@Nullable Function<String, String> uriTagValue,
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
			@Nullable Duration idleTimeout) {
		p.addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpCodec,
		            new HttpServerCodec(decoder.maxInitialLineLength(), decoder.maxHeaderSize(),
		                    decoder.maxChunkSize(), decoder.validateHeaders(), decoder.initialBufferSize()))
		 .addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpTrafficHandler,
		            new HttpTrafficHandler(listener, forwardedHeaderHandler, compressPredicate, cookieEncoder,
		                    cookieDecoder, mapHandle, idleTimeout));

		if (accessLogEnabled) {
			p.addAfter(NettyPipeline.HttpCodec, NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H1.create(accessLog));
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
			if (pipeline.get(NettyPipeline.AccessLogHandler) != null) {
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

		final boolean                                                 accessLogEnabled;
		final Function<AccessLogArgProvider, AccessLog>               accessLog;
		final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
		final ServerCookieDecoder                                     cookieDecoder;
		final ServerCookieEncoder                                     cookieEncoder;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>      mapHandle;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;

		H2Codec(ChannelOperations.OnSetup opsFactory, ConnectionObserver listener,
		        @Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
		        @Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
		        ServerCookieEncoder encoder, ServerCookieDecoder decoder,
		        @Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
		        int minCompressionSize, boolean accessLogEnabled,
		        @Nullable Function<AccessLogArgProvider, AccessLog> accessLog) {
			this.accessLogEnabled = accessLogEnabled;
			this.accessLog = accessLog;
			this.compressPredicate = compressPredicate;
			this.cookieDecoder = decoder;
			this.cookieEncoder = encoder;
			this.forwardedHeaderHandler = forwardedHeaderHandler;
			this.listener = listener;
			this.mapHandle = mapHandle;
			this.minCompressionSize = minCompressionSize;
			this.opsFactory = opsFactory;
		}

		@Override
		protected void initChannel(Channel ch) {
			ch.pipeline().remove(this);
			addStreamHandlers(ch, opsFactory, listener, compressPredicate, forwardedHeaderHandler,
					cookieEncoder, cookieDecoder, mapHandle, minCompressionSize, accessLogEnabled, accessLog);
		}
	}

	static final class Http11OrH2CleartextCodec extends ChannelInitializer<Channel>
			implements HttpServerUpgradeHandler.UpgradeCodecFactory {

		final boolean                                                 accessLogEnabled;
		final Function<AccessLogArgProvider, AccessLog>               accessLog;
		final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
		final ServerCookieDecoder                                     cookieDecoder;
		final ServerCookieEncoder                                     cookieEncoder;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2FrameCodec                                         http2FrameCodec;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>      mapHandle;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;

		Http11OrH2CleartextCodec(
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				ServerCookieDecoder cookieDecoder,
				ServerCookieEncoder cookieEncoder,
				boolean debug,
				@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
				Http2Settings http2Settings,
				ConnectionObserver listener,
				@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
				int minCompressionSize,
				ChannelOperations.OnSetup opsFactory,
				boolean validate,
				boolean accessLogEnabled,
				@Nullable Function<AccessLogArgProvider, AccessLog> accessLog) {
			this.accessLogEnabled = accessLogEnabled;
			this.accessLog = accessLog;
			this.compressPredicate = compressPredicate;
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
			this.minCompressionSize = minCompressionSize;
			this.opsFactory = opsFactory;
		}

		/**
		 * Inline channel initializer
		 */
		@Override
		protected void initChannel(Channel ch) {
			ch.pipeline().remove(this);
			addStreamHandlers(ch, opsFactory, listener, compressPredicate, forwardedHeaderHandler, cookieEncoder,
					cookieDecoder, mapHandle, minCompressionSize, accessLogEnabled, accessLog);
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

		final boolean                                                 accessLogEnabled;
		final Function<AccessLogArgProvider, AccessLog>               accessLog;
		final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
		final ServerCookieDecoder                                     cookieDecoder;
		final ServerCookieEncoder                                     cookieEncoder;
		final HttpRequestDecoderSpec                                  decoder;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2Settings                                           http2Settings;
		final Duration                                                idleTimeout;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>      mapHandle;
		final Supplier<? extends ChannelMetricsRecorder>              metricsRecorder;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;
		final Function<String, String>                                uriTagValue;

		H2OrHttp11Codec(HttpServerChannelInitializer initializer, ConnectionObserver listener) {
			super(ApplicationProtocolNames.HTTP_1_1);
			this.accessLogEnabled = initializer.accessLogEnabled;
			this.accessLog = initializer.accessLog;
			this.compressPredicate = compressPredicate(initializer.compressPredicate, initializer.minCompressionSize);
			this.cookieDecoder = initializer.cookieDecoder;
			this.cookieEncoder = initializer.cookieEncoder;
			this.decoder = initializer.decoder;
			this.forwardedHeaderHandler = initializer.forwardedHeaderHandler;
			this.http2Settings = initializer.http2Settings;
			this.idleTimeout = initializer.idleTimeout;
			this.listener = listener;
			this.mapHandle = initializer.mapHandle;
			this.metricsRecorder = initializer.metricsRecorder;
			this.minCompressionSize = initializer.minCompressionSize;
			this.opsFactory = initializer.opsFactory;
			this.uriTagValue = initializer.uriTagValue;
		}

		@Override
		protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(), "Negotiated application-level protocol [" + protocol + "]"));
			}

			ChannelPipeline p = ctx.pipeline();

			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				configureH2Pipeline(p, compressPredicate, cookieDecoder, cookieEncoder, forwardedHeaderHandler, http2Settings,
						listener, mapHandle, minCompressionSize, opsFactory, decoder.validateHeaders(), accessLogEnabled,
						accessLog);
				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				configureHttp11Pipeline(p, compressPredicate, cookieDecoder, cookieEncoder, decoder, forwardedHeaderHandler,
						listener, mapHandle, metricsRecorder, minCompressionSize, uriTagValue, accessLogEnabled,
						accessLog, idleTimeout);
				return;
			}

			throw new IllegalStateException("unknown protocol: " + protocol);
		}
	}

	static final class HttpServerChannelInitializer implements ChannelPipelineConfigurer {

		final boolean                                                 accessLogEnabled;
		final Function<AccessLogArgProvider, AccessLog>               accessLog;
		final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
		final ServerCookieDecoder                                     cookieDecoder;
		final ServerCookieEncoder                                     cookieEncoder;
		final HttpRequestDecoderSpec                                  decoder;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2Settings                                           http2Settings;
		final Duration                                                idleTimeout;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>      mapHandle;
		final Supplier<? extends ChannelMetricsRecorder>              metricsRecorder;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;
		final int                                                     protocols;
		final ProxyProtocolSupportType                                proxyProtocolSupportType;
		final SslProvider                                             sslProvider;
		final Function<String, String>                                uriTagValue;

		HttpServerChannelInitializer(HttpServerConfig config) {
			this.accessLogEnabled = config.accessLogEnabled;
			this.accessLog = config.accessLog;
			this.compressPredicate = config.compressPredicate;
			this.cookieDecoder = config.cookieDecoder;
			this.cookieEncoder = config.cookieEncoder;
			this.decoder = config.decoder;
			this.forwardedHeaderHandler = config.forwardedHeaderHandler;
			this.http2Settings = config.http2Settings();
			this.idleTimeout = config.idleTimeout;
			this.mapHandle = config.mapHandle;
			this.metricsRecorder = config.metricsRecorder();
			this.minCompressionSize = config.minCompressionSize;
			this.opsFactory = config.channelOperationsProvider();
			this.protocols = config._protocols;
			this.proxyProtocolSupportType = config.proxyProtocolSupportType;
			this.sslProvider = config.sslProvider;
			this.uriTagValue = config.uriTagValue;
		}

		@Override
		public void onChannelInit(ConnectionObserver observer, Channel channel, @Nullable SocketAddress remoteAddress) {
			boolean needRead = false;

			if (sslProvider != null) {
				sslProvider.addSslHandler(channel, remoteAddress, SSL_DEBUG);

				if ((protocols & h11orH2) == h11orH2) {
					channel.pipeline()
					       .addBefore(NettyPipeline.ReactiveBridge,
					                  NettyPipeline.H2OrHttp11Codec,
					                  new H2OrHttp11Codec(this, observer));
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
							uriTagValue,
							accessLogEnabled,
							accessLog,
							idleTimeout);
				}
				else if ((protocols & h2) == h2) {
					configureH2Pipeline(
							channel.pipeline(),
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							forwardedHeaderHandler,
							http2Settings,
							observer,
							mapHandle,
							minCompressionSize,
							opsFactory,
							decoder.validateHeaders(),
							accessLogEnabled,
							accessLog);
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
							uriTagValue,
							accessLogEnabled,
							accessLog,
							idleTimeout);
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
							uriTagValue,
							accessLogEnabled,
							accessLog,
							idleTimeout);
				}
				else if ((protocols & h2c) == h2c) {
					configureH2Pipeline(
							channel.pipeline(),
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							forwardedHeaderHandler,
							http2Settings,
							observer,
							mapHandle,
							minCompressionSize,
							opsFactory,
							decoder.validateHeaders(),
							accessLogEnabled,
							accessLog);
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
