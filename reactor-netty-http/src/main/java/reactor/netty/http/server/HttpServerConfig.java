/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
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
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
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
import reactor.netty.channel.AbstractChannelMetricsHandler;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Http2SettingsSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.HttpResources;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.netty.http.logging.ReactorNettyHttpMessageLogFactory;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.http.server.logging.AccessLogArgProvider;
import reactor.netty.http.server.logging.AccessLogHandlerFactory;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.transport.ServerTransportConfig;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import static reactor.netty.ReactorNetty.ACCESS_LOG_ENABLED;
import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.server.HttpServerFormDecoderProvider.DEFAULT_FORM_DECODER_SPEC;

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
	 * Return the configured HTTP form decoder or the default.
	 *
	 * @return the configured HTTP form decoder or the default
	 * @since 1.0.11
	 */
	public HttpServerFormDecoderProvider formDecoderProvider() {
		return formDecoderProvider;
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
	 * The configured maximum number of HTTP/1.1 requests which can be served until the connection is closed by the server.
	 *
	 * @return the configured maximum number of HTTP/1.1 requests which can be served until the connection is closed by the server.
	 * @see HttpServer#maxKeepAliveRequests(int)
	 * @since 1.0.13
	 */
	public int maxKeepAliveRequests() {
		return maxKeepAliveRequests;
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
	 * Returns true if that {@link HttpServer} will redirect HTTP to HTTPS by changing
	 * the scheme only but otherwise leaving the port the same when SSL is enabled.
	 * This configuration is applicable only for HTTP/1.x.
	 *
	 * @return true if that {@link HttpServer} will redirect HTTP to HTTPS by changing
	 * the scheme only but otherwise leaving the port the same when SSL is enabled.
	 * This configuration is applicable only for HTTP/1.x.
	 */
	public boolean redirectHttpToHttps() {
		return redirectHttpToHttps;
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
	HttpServerFormDecoderProvider                           formDecoderProvider;
	BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	Http2SettingsSpec                                       http2Settings;
	HttpMessageLogFactory                                   httpMessageLogFactory;
	Duration                                                idleTimeout;
	BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
	                                                        mapHandle;
	int                                                     maxKeepAliveRequests;
	int                                                     minCompressionSize;
	HttpProtocol[]                                          protocols;
	int                                                     _protocols;
	ProxyProtocolSupportType                                proxyProtocolSupportType;
	boolean                                                 redirectHttpToHttps;
	SslProvider                                             sslProvider;
	Function<String, String>                                uriTagValue;

	HttpServerConfig(Map<ChannelOption<?>, ?> options, Map<ChannelOption<?>, ?> childOptions, Supplier<? extends SocketAddress> localAddress) {
		super(options, childOptions, localAddress);
		this.cookieDecoder = ServerCookieDecoder.STRICT;
		this.cookieEncoder = ServerCookieEncoder.STRICT;
		this.decoder = new HttpRequestDecoderSpec();
		this.formDecoderProvider = DEFAULT_FORM_DECODER_SPEC;
		this.httpMessageLogFactory = ReactorNettyHttpMessageLogFactory.INSTANCE;
		this.maxKeepAliveRequests = -1;
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
		this.formDecoderProvider = parent.formDecoderProvider;
		this.forwardedHeaderHandler = parent.forwardedHeaderHandler;
		this.http2Settings = parent.http2Settings;
		this.httpMessageLogFactory = parent.httpMessageLogFactory;
		this.idleTimeout = parent.idleTimeout;
		this.mapHandle = parent.mapHandle;
		this.maxKeepAliveRequests = parent.maxKeepAliveRequests;
		this.minCompressionSize = parent.minCompressionSize;
		this.protocols = parent.protocols;
		this._protocols = parent._protocols;
		this.proxyProtocolSupportType = parent.proxyProtocolSupportType;
		this.redirectHttpToHttps = parent.redirectHttpToHttps;
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

	static Http2Settings http2Settings(@Nullable Http2SettingsSpec http2Settings) {
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

	static void addStreamHandlers(Channel ch,
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Function<String, String> uriTagValue) {
		ChannelPipeline pipeline = ch.pipeline();
		if (accessLogEnabled) {
			pipeline.addLast(NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H2.create(accessLog));
		}
		pipeline.addLast(NettyPipeline.H2ToHttp11Codec, HTTP2_STREAM_FRAME_TO_HTTP_OBJECT)
		        .addLast(NettyPipeline.HttpTrafficHandler,
		                 new Http2StreamBridgeServerHandler(compressPredicate, decoder, encoder, formDecoderProvider,
		                         forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle));

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			pipeline.addLast(NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
		}

		ChannelOperations.addReactiveBridge(ch, opsFactory, listener);

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpServerMetricsRecorder) {
				ChannelHandler handler;
				Channel parent = ch.parent();
				ChannelHandler existingHandler = parent.pipeline().get(NettyPipeline.HttpMetricsHandler);
				if (existingHandler != null) {
					// This use case can happen only in HTTP/2 clear text connection upgrade
					if (metricsRecorder instanceof MicrometerHttpServerMetricsRecorder) {
						parent.pipeline().replace(NettyPipeline.HttpMetricsHandler, NettyPipeline.ChannelMetricsHandler,
								new H2ChannelMetricsHandler(metricsRecorder));
					}
					else {
						parent.pipeline().remove(NettyPipeline.HttpMetricsHandler);
					}
					handler = metricsRecorder instanceof ContextAwareHttpServerMetricsRecorder ?
							new ContextAwareHttpServerMetricsHandler((ContextAwareHttpServerMetricsHandler) existingHandler) :
							new HttpServerMetricsHandler((HttpServerMetricsHandler) existingHandler);
				}
				else {
					handler = metricsRecorder instanceof ContextAwareHttpServerMetricsRecorder ?
							new ContextAwareHttpServerMetricsHandler((ContextAwareHttpServerMetricsRecorder) metricsRecorder, uriTagValue) :
							new HttpServerMetricsHandler((HttpServerMetricsRecorder) metricsRecorder, uriTagValue);
				}
				pipeline.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpMetricsHandler, handler);
			}
		}

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
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder cookieDecoder,
			ServerCookieEncoder cookieEncoder,
			boolean enableGracefulShutdown,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			@Nullable Http2SettingsSpec http2SettingsSpec,
			HttpMessageLogFactory httpMessageLogFactory,
			@Nullable Duration idleTimeout,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Function<String, String> uriTagValue,
			boolean validate) {
		p.remove(NettyPipeline.ReactiveBridge);

		Http2FrameCodecBuilder http2FrameCodecBuilder =
				Http2FrameCodecBuilder.forServer()
				                      .validateHeaders(validate)
				                      .initialSettings(http2Settings(http2SettingsSpec));

		Long maxStreams = http2SettingsSpec != null ? http2SettingsSpec.maxStreams() : null;
		if (enableGracefulShutdown || maxStreams != null) {
			// 1. Configure the graceful shutdown with indefinite timeout as Reactor Netty controls the timeout
			// when disposeNow(timeout) is invoked
			// 2. When 'maxStreams' is configured, the graceful shutdown is enabled.
			// The graceful shutdown is configured with indefinite timeout because
			// the response time is controlled by the user and might be different
			// for the different requests.
			http2FrameCodecBuilder.gracefulShutdownTimeoutMillis(-1);
		}

		if (p.get(NettyPipeline.LoggingHandler) != null) {
			http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG,
					"reactor.netty.http.server.h2"));
		}

		Http2FrameCodec http2FrameCodec = http2FrameCodecBuilder.build();
		if (maxStreams != null) {
			http2FrameCodec.connection().addListener(new H2ConnectionListener(p.channel(), maxStreams));
		}
		p.addLast(NettyPipeline.HttpCodec, http2FrameCodec)
		 .addLast(NettyPipeline.H2MultiplexHandler,
		          new Http2MultiplexHandler(new H2Codec(accessLogEnabled, accessLog, compressPredicate, cookieDecoder,
		                  cookieEncoder, formDecoderProvider, forwardedHeaderHandler, httpMessageLogFactory, listener,
		                  mapHandle, metricsRecorder, minCompressionSize, opsFactory, uriTagValue)));

		IdleTimeoutHandler.addIdleTimeoutHandler(p, idleTimeout);

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof MicrometerHttpServerMetricsRecorder) {
				// For sake of performance, we can replace the ChannelMetricsHandler because the MicrometerHttpServerMetricsRecorder
				// is interested only in connections metrics .
				p.replace(NettyPipeline.ChannelMetricsHandler, NettyPipeline.ChannelMetricsHandler,
						new H2ChannelMetricsHandler(metricsRecorder));
			}
		}
	}

	static void configureHttp11OrH2CleartextPipeline(ChannelPipeline p,
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder cookieDecoder,
			ServerCookieEncoder cookieEncoder,
			HttpRequestDecoderSpec decoder,
			boolean enableGracefulShutdown,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			@Nullable Http2SettingsSpec http2SettingsSpec,
			HttpMessageLogFactory httpMessageLogFactory,
			@Nullable Duration idleTimeout,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			int maxKeepAliveRequests,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Function<String, String> uriTagValue) {
		HttpServerCodec httpServerCodec =
				new HttpServerCodec(decoder.maxInitialLineLength(), decoder.maxHeaderSize(),
						decoder.maxChunkSize(), decoder.validateHeaders(), decoder.initialBufferSize(),
						decoder.allowDuplicateContentLengths());

		Http11OrH2CleartextCodec upgrader = new Http11OrH2CleartextCodec(accessLogEnabled, accessLog, compressPredicate,
				cookieDecoder, cookieEncoder, p.get(NettyPipeline.LoggingHandler) != null, enableGracefulShutdown, formDecoderProvider,
				forwardedHeaderHandler, http2SettingsSpec, httpMessageLogFactory, listener, mapHandle, metricsRecorder,
				minCompressionSize, opsFactory, uriTagValue, decoder.validateHeaders());

		ChannelHandler http2ServerHandler = new H2CleartextCodec(upgrader, http2SettingsSpec != null ? http2SettingsSpec.maxStreams() : null);
		CleartextHttp2ServerUpgradeHandler h2cUpgradeHandler = new CleartextHttp2ServerUpgradeHandler(
				httpServerCodec,
				new HttpServerUpgradeHandler(httpServerCodec, upgrader, decoder.h2cMaxContentLength()),
				http2ServerHandler);

		p.addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.H2CUpgradeHandler, h2cUpgradeHandler)
		 .addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpTrafficHandler,
		            new HttpTrafficHandler(compressPredicate, cookieDecoder, cookieEncoder, formDecoderProvider,
		                    forwardedHeaderHandler, httpMessageLogFactory, idleTimeout, listener, mapHandle, maxKeepAliveRequests));

		if (accessLogEnabled) {
			p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H1.create(accessLog));
		}

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			p.addBefore(NettyPipeline.HttpTrafficHandler, NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
		}

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpServerMetricsRecorder) {
				ChannelHandler handler = metricsRecorder instanceof ContextAwareHttpServerMetricsRecorder ?
						new ContextAwareHttpServerMetricsHandler((ContextAwareHttpServerMetricsRecorder) metricsRecorder, uriTagValue) :
						new HttpServerMetricsHandler((HttpServerMetricsRecorder) metricsRecorder, uriTagValue);
				p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.HttpMetricsHandler, handler);
				if (metricsRecorder instanceof MicrometerHttpServerMetricsRecorder) {
					// For sake of performance, we can remove the ChannelMetricsHandler because the MicrometerHttpServerMetricsRecorder
					// does not implement metrics on TCP protocol level.
					p.remove(NettyPipeline.ChannelMetricsHandler);
				}
			}
		}
	}

	static void configureHttp11Pipeline(ChannelPipeline p,
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder cookieDecoder,
			ServerCookieEncoder cookieEncoder,
			HttpRequestDecoderSpec decoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			@Nullable Duration idleTimeout,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			int maxKeepAliveRequests,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			int minCompressionSize,
			@Nullable Function<String, String> uriTagValue) {
		p.addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpCodec,
		            new HttpServerCodec(decoder.maxInitialLineLength(), decoder.maxHeaderSize(),
		                    decoder.maxChunkSize(), decoder.validateHeaders(), decoder.initialBufferSize(),
		                    decoder.allowDuplicateContentLengths()))
		 .addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpTrafficHandler,
		            new HttpTrafficHandler(compressPredicate, cookieDecoder, cookieEncoder, formDecoderProvider,
		                    forwardedHeaderHandler, httpMessageLogFactory, idleTimeout, listener, mapHandle, maxKeepAliveRequests));

		if (accessLogEnabled) {
			p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H1.create(accessLog));
		}

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			p.addBefore(NettyPipeline.HttpTrafficHandler, NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
		}

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpServerMetricsRecorder) {
				ChannelHandler handler = metricsRecorder instanceof ContextAwareHttpServerMetricsRecorder ?
						new ContextAwareHttpServerMetricsHandler((ContextAwareHttpServerMetricsRecorder) metricsRecorder, uriTagValue) :
						new HttpServerMetricsHandler((HttpServerMetricsRecorder) metricsRecorder, uriTagValue);
				p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.HttpMetricsHandler, handler);
				if (metricsRecorder instanceof MicrometerHttpServerMetricsRecorder) {
					// For sake of performance, we can remove the ChannelMetricsHandler because the MicrometerHttpServerMetricsRecorder
					// does not implement metrics on TCP protocol level.
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

	static final Http2StreamFrameToHttpObjectCodec HTTP2_STREAM_FRAME_TO_HTTP_OBJECT =
			new Http2StreamFrameToHttpObjectCodec(true);

	static final Logger log = Loggers.getLogger(HttpServerConfig.class);

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedByteBufFormat.HEX_DUMP
					.toLoggingHandler(HttpServer.class.getName(), LogLevel.DEBUG, Charset.defaultCharset());

	/**
	 * Default value whether the SSL debugging on the server side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	static final boolean SSL_DEBUG = Boolean.parseBoolean(System.getProperty(ReactorNetty.SSL_SERVER_DEBUG, "false"));

	static final class H2ChannelMetricsHandler extends AbstractChannelMetricsHandler {

		final ChannelMetricsRecorder recorder;

		H2ChannelMetricsHandler(ChannelMetricsRecorder recorder) {
			super(null, true);
			this.recorder = recorder;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			ctx.fireChannelRead(msg);
		}

		@Override
		public void channelRegistered(ChannelHandlerContext ctx) {
			ctx.fireChannelRegistered();
		}

		@Override
		public ChannelHandler connectMetricsHandler() {
			return null;
		}

		@Override
		public ChannelHandler tlsMetricsHandler() {
			return null;
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ctx.fireExceptionCaught(cause);
		}

		@Override
		public ChannelMetricsRecorder recorder() {
			return recorder;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}
	}

	static final class H2CleartextCodec extends ChannelHandlerAdapter {

		final Http11OrH2CleartextCodec upgrader;
		final boolean addHttp2FrameCodec;
		final boolean removeMetricsHandler;
		final Long maxStreams;

		/**
		 * Used when full H2 preface is received
		 */
		H2CleartextCodec(Http11OrH2CleartextCodec upgrader, @Nullable Long maxStreams) {
			this(upgrader, true, true, maxStreams);
		}

		/**
		 * Used when upgrading from HTTP/1.1 to H2. When an upgrade happens {@link Http2FrameCodec}
		 * is added by {@link Http2ServerUpgradeCodec}
		 */
		H2CleartextCodec(Http11OrH2CleartextCodec upgrader, boolean addHttp2FrameCodec, boolean removeMetricsHandler,
				@Nullable Long maxStreams) {
			this.upgrader = upgrader;
			this.addHttp2FrameCodec = addHttp2FrameCodec;
			this.removeMetricsHandler = removeMetricsHandler;
			this.maxStreams = maxStreams;
		}

		@Override
		public void handlerAdded(ChannelHandlerContext ctx) {
			ChannelPipeline pipeline = ctx.pipeline();
			if (maxStreams != null) {
				upgrader.http2FrameCodec.connection().addListener(new H2ConnectionListener(ctx.channel(), maxStreams));
			}
			if (addHttp2FrameCodec) {
				pipeline.addAfter(ctx.name(), NettyPipeline.HttpCodec, upgrader.http2FrameCodec);
			}

			// Add this handler at the end of the pipeline as it does not forward all channelRead events
			pipeline.addLast(NettyPipeline.H2MultiplexHandler, new Http2MultiplexHandler(upgrader));

			pipeline.remove(this);

			if (pipeline.get(NettyPipeline.AccessLogHandler) != null) {
				pipeline.remove(NettyPipeline.AccessLogHandler);
			}
			if (pipeline.get(NettyPipeline.CompressionHandler) != null) {
				pipeline.remove(NettyPipeline.CompressionHandler);
			}
			if (removeMetricsHandler && pipeline.get(NettyPipeline.HttpMetricsHandler) != null) {
				AbstractHttpServerMetricsHandler handler =
						(AbstractHttpServerMetricsHandler) pipeline.get(NettyPipeline.HttpMetricsHandler);
				if (handler.recorder() instanceof MicrometerHttpServerMetricsRecorder) {
					pipeline.replace(NettyPipeline.HttpMetricsHandler, NettyPipeline.ChannelMetricsHandler,
							new H2ChannelMetricsHandler(handler.recorder()));
				}
				else {
					pipeline.remove(NettyPipeline.HttpMetricsHandler);
				}
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
		final HttpServerFormDecoderProvider                           formDecoderProvider;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final HttpMessageLogFactory                                   httpMessageLogFactory;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
		                                                              mapHandle;
		final ChannelMetricsRecorder                                  metricsRecorder;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;
		final Function<String, String>                                uriTagValue;

		H2Codec(
				boolean accessLogEnabled,
				@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				ServerCookieDecoder decoder,
				ServerCookieEncoder encoder,
				HttpServerFormDecoderProvider formDecoderProvider,
				@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
				HttpMessageLogFactory httpMessageLogFactory,
				ConnectionObserver listener,
				@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
				@Nullable ChannelMetricsRecorder metricsRecorder,
				int minCompressionSize,
				ChannelOperations.OnSetup opsFactory,
				@Nullable Function<String, String> uriTagValue) {
			this.accessLogEnabled = accessLogEnabled;
			this.accessLog = accessLog;
			this.compressPredicate = compressPredicate;
			this.cookieDecoder = decoder;
			this.cookieEncoder = encoder;
			this.formDecoderProvider = formDecoderProvider;
			this.forwardedHeaderHandler = forwardedHeaderHandler;
			this.httpMessageLogFactory = httpMessageLogFactory;
			this.listener = listener;
			this.mapHandle = mapHandle;
			this.metricsRecorder = metricsRecorder;
			this.minCompressionSize = minCompressionSize;
			this.opsFactory = opsFactory;
			this.uriTagValue = uriTagValue;
		}

		@Override
		protected void initChannel(Channel ch) {
			ch.pipeline().remove(this);
			addStreamHandlers(ch, accessLogEnabled, accessLog, compressPredicate, cookieDecoder, cookieEncoder,
					formDecoderProvider, forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle, metricsRecorder,
					minCompressionSize, opsFactory, uriTagValue);
		}
	}

	static final class Http11OrH2CleartextCodec extends ChannelInitializer<Channel>
			implements HttpServerUpgradeHandler.UpgradeCodecFactory {

		final boolean                                                 accessLogEnabled;
		final Function<AccessLogArgProvider, AccessLog>               accessLog;
		final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
		final ServerCookieDecoder                                     cookieDecoder;
		final ServerCookieEncoder                                     cookieEncoder;
		final HttpServerFormDecoderProvider                           formDecoderProvider;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2FrameCodec                                         http2FrameCodec;
		final HttpMessageLogFactory                                   httpMessageLogFactory;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
		                                                              mapHandle;
		final Long                                                    maxStreams;
		final ChannelMetricsRecorder                                  metricsRecorder;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;
		final Function<String, String>                                uriTagValue;

		Http11OrH2CleartextCodec(
				boolean accessLogEnabled,
				@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				ServerCookieDecoder cookieDecoder,
				ServerCookieEncoder cookieEncoder,
				boolean debug,
				boolean enableGracefulShutdown,
				HttpServerFormDecoderProvider formDecoderProvider,
				@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
				@Nullable Http2SettingsSpec http2SettingsSpec,
				HttpMessageLogFactory httpMessageLogFactory,
				ConnectionObserver listener,
				@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
				@Nullable ChannelMetricsRecorder metricsRecorder,
				int minCompressionSize,
				ChannelOperations.OnSetup opsFactory,
				@Nullable Function<String, String> uriTagValue,
				boolean validate) {
			this.accessLogEnabled = accessLogEnabled;
			this.accessLog = accessLog;
			this.compressPredicate = compressPredicate;
			this.cookieDecoder = cookieDecoder;
			this.cookieEncoder = cookieEncoder;
			this.formDecoderProvider = formDecoderProvider;
			this.forwardedHeaderHandler = forwardedHeaderHandler;
			Http2FrameCodecBuilder http2FrameCodecBuilder =
					Http2FrameCodecBuilder.forServer()
					                      .validateHeaders(validate)
					                      .initialSettings(http2Settings(http2SettingsSpec));

			this.maxStreams = http2SettingsSpec != null ? http2SettingsSpec.maxStreams() : null;
			if (enableGracefulShutdown || maxStreams != null) {
				// 1. Configure the graceful shutdown with indefinite timeout as Reactor Netty controls the timeout
				// when disposeNow(timeout) is invoked
				// 2. When 'maxStreams' is configured, the graceful shutdown is enabled.
				// The graceful shutdown is configured with indefinite timeout because
				// the response time is controlled by the user and might be different
				// for the different requests.
				http2FrameCodecBuilder.gracefulShutdownTimeoutMillis(-1);
			}

			if (debug) {
				http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(
						LogLevel.DEBUG,
						"reactor.netty.http.server.h2"));
			}
			this.http2FrameCodec = http2FrameCodecBuilder.build();
			this.httpMessageLogFactory = httpMessageLogFactory;
			this.listener = listener;
			this.mapHandle = mapHandle;
			this.metricsRecorder = metricsRecorder;
			this.minCompressionSize = minCompressionSize;
			this.opsFactory = opsFactory;
			this.uriTagValue = uriTagValue;
		}

		/**
		 * Inline channel initializer
		 */
		@Override
		protected void initChannel(Channel ch) {
			ch.pipeline().remove(this);
			addStreamHandlers(ch, accessLogEnabled, accessLog, compressPredicate, cookieDecoder, cookieEncoder,
					formDecoderProvider, forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle, metricsRecorder,
					minCompressionSize, opsFactory, uriTagValue);
		}

		@Override
		@Nullable
		public HttpServerUpgradeHandler.UpgradeCodec newUpgradeCodec(CharSequence protocol) {
			if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
				return new Http2ServerUpgradeCodec(http2FrameCodec, new H2CleartextCodec(this, false, false, maxStreams));
			}
			else {
				return null;
			}
		}
	}

	static final class H2ConnectionListener extends Http2ConnectionAdapter {

		final Channel channel;
		final long maxStreams;

		long numStreams;

		H2ConnectionListener(Channel channel, long maxStreams) {
			this.channel = channel;
			this.maxStreams = maxStreams;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void onStreamActive(Http2Stream stream) {
			assert channel.eventLoop().inEventLoop();
			if (++numStreams == maxStreams) {
				//"FutureReturnValueIgnored" this is deliberate
				channel.close();
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
		final boolean                                                 enableGracefulShutdown;
		final HttpServerFormDecoderProvider                           formDecoderProvider;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2SettingsSpec                                       http2SettingsSpec;
		final HttpMessageLogFactory                                   httpMessageLogFactory;
		final Duration                                                idleTimeout;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
		                                                              mapHandle;
		final int                                                     maxKeepAliveRequests;
		final ChannelMetricsRecorder                                  metricsRecorder;
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
			this.enableGracefulShutdown = initializer.enableGracefulShutdown;
			this.formDecoderProvider = initializer.formDecoderProvider;
			this.forwardedHeaderHandler = initializer.forwardedHeaderHandler;
			this.http2SettingsSpec = initializer.http2SettingsSpec;
			this.httpMessageLogFactory = initializer.httpMessageLogFactory;
			this.idleTimeout = initializer.idleTimeout;
			this.listener = listener;
			this.mapHandle = initializer.mapHandle;
			this.maxKeepAliveRequests = initializer.maxKeepAliveRequests;
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
				configureH2Pipeline(p, accessLogEnabled, accessLog, compressPredicate, cookieDecoder, cookieEncoder,
						enableGracefulShutdown, formDecoderProvider, forwardedHeaderHandler, http2SettingsSpec, httpMessageLogFactory, idleTimeout,
						listener, mapHandle, metricsRecorder, minCompressionSize, opsFactory, uriTagValue, decoder.validateHeaders());
				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				configureHttp11Pipeline(p, accessLogEnabled, accessLog, compressPredicate, cookieDecoder, cookieEncoder,
						decoder, formDecoderProvider, forwardedHeaderHandler, httpMessageLogFactory, idleTimeout, listener,
						mapHandle, maxKeepAliveRequests, metricsRecorder, minCompressionSize, uriTagValue);

				// When the server is configured with HTTP/1.1 and H2 and HTTP/1.1 is negotiated,
				// when channelActive event happens, this HttpTrafficHandler is still not in the pipeline,
				// and will not be able to add IdleTimeoutHandler. So in this use case add IdleTimeoutHandler here.
				IdleTimeoutHandler.addIdleTimeoutHandler(ctx.pipeline(), idleTimeout);
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
		final boolean                                                 enableGracefulShutdown;
		final HttpServerFormDecoderProvider                           formDecoderProvider;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2SettingsSpec                                       http2SettingsSpec;
		final HttpMessageLogFactory                                   httpMessageLogFactory;
		final Duration                                                idleTimeout;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
		                                                              mapHandle;
		final int                                                     maxKeepAliveRequests;
		final ChannelMetricsRecorder                                  metricsRecorder;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;
		final int                                                     protocols;
		final ProxyProtocolSupportType                                proxyProtocolSupportType;
		final boolean                                                 redirectHttpToHttps;
		final SslProvider                                             sslProvider;
		final Function<String, String>                                uriTagValue;

		HttpServerChannelInitializer(HttpServerConfig config) {
			this.accessLogEnabled = config.accessLogEnabled;
			this.accessLog = config.accessLog;
			this.compressPredicate = config.compressPredicate;
			this.cookieDecoder = config.cookieDecoder;
			this.cookieEncoder = config.cookieEncoder;
			this.decoder = config.decoder;
			this.enableGracefulShutdown = config.channelGroup() != null;
			this.formDecoderProvider = config.formDecoderProvider;
			this.forwardedHeaderHandler = config.forwardedHeaderHandler;
			this.http2SettingsSpec = config.http2Settings;
			this.httpMessageLogFactory = config.httpMessageLogFactory;
			this.idleTimeout = config.idleTimeout;
			this.mapHandle = config.mapHandle;
			this.maxKeepAliveRequests = config.maxKeepAliveRequests;
			this.metricsRecorder = config.metricsRecorderInternal();
			this.minCompressionSize = config.minCompressionSize;
			this.opsFactory = config.channelOperationsProvider();
			this.protocols = config._protocols;
			this.proxyProtocolSupportType = config.proxyProtocolSupportType;
			this.redirectHttpToHttps = config.redirectHttpToHttps;
			this.sslProvider = config.sslProvider;
			this.uriTagValue = config.uriTagValue;
		}

		@Override
		public void onChannelInit(ConnectionObserver observer, Channel channel, @Nullable SocketAddress remoteAddress) {
			boolean needRead = false;

			if (sslProvider != null) {
				ChannelPipeline pipeline = channel.pipeline();
				if (redirectHttpToHttps && (protocols & h2) != h2) {
					NonSslRedirectDetector nonSslRedirectDetector = new NonSslRedirectDetector(sslProvider,
							remoteAddress,
							SSL_DEBUG);
					pipeline.addFirst(NettyPipeline.NonSslRedirectDetector, nonSslRedirectDetector);
				}
				else {
					sslProvider.addSslHandler(channel, remoteAddress, SSL_DEBUG);
				}

				if ((protocols & h11orH2) == h11orH2) {
					channel.pipeline()
					       .addBefore(NettyPipeline.ReactiveBridge,
					                  NettyPipeline.H2OrHttp11Codec,
					                  new H2OrHttp11Codec(this, observer));
				}
				else if ((protocols & h11) == h11) {
					configureHttp11Pipeline(
							channel.pipeline(),
							accessLogEnabled,
							accessLog,
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							decoder,
							formDecoderProvider,
							forwardedHeaderHandler,
							httpMessageLogFactory,
							idleTimeout,
							observer,
							mapHandle,
							maxKeepAliveRequests,
							metricsRecorder,
							minCompressionSize,
							uriTagValue);
				}
				else if ((protocols & h2) == h2) {
					configureH2Pipeline(
							channel.pipeline(),
							accessLogEnabled,
							accessLog,
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							enableGracefulShutdown,
							formDecoderProvider,
							forwardedHeaderHandler,
							http2SettingsSpec,
							httpMessageLogFactory,
							idleTimeout,
							observer,
							mapHandle,
							metricsRecorder,
							minCompressionSize,
							opsFactory,
							uriTagValue,
							decoder.validateHeaders());
				}
			}
			else {
				if ((protocols & h11orH2C) == h11orH2C) {
					configureHttp11OrH2CleartextPipeline(
							channel.pipeline(),
							accessLogEnabled,
							accessLog,
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							decoder,
							enableGracefulShutdown,
							formDecoderProvider,
							forwardedHeaderHandler,
							http2SettingsSpec,
							httpMessageLogFactory,
							idleTimeout,
							observer,
							mapHandle,
							maxKeepAliveRequests,
							metricsRecorder,
							minCompressionSize,
							opsFactory,
							uriTagValue);
				}
				else if ((protocols & h11) == h11) {
					configureHttp11Pipeline(
							channel.pipeline(),
							accessLogEnabled,
							accessLog,
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							decoder,
							formDecoderProvider,
							forwardedHeaderHandler,
							httpMessageLogFactory,
							idleTimeout,
							observer,
							mapHandle,
							maxKeepAliveRequests,
							metricsRecorder,
							minCompressionSize,
							uriTagValue);
				}
				else if ((protocols & h2c) == h2c) {
					configureH2Pipeline(
							channel.pipeline(),
							accessLogEnabled,
							accessLog,
							compressPredicate(compressPredicate, minCompressionSize),
							cookieDecoder,
							cookieEncoder,
							enableGracefulShutdown,
							formDecoderProvider,
							forwardedHeaderHandler,
							http2SettingsSpec,
							httpMessageLogFactory,
							idleTimeout,
							observer,
							mapHandle,
							metricsRecorder,
							minCompressionSize,
							opsFactory,
							uriTagValue,
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
