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
package reactor.netty5.http.server;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.ChannelPipeline;
import io.netty.contrib.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty5.channel.internal.DelegatingChannelHandlerContext;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpObject;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpServerCodec;
import io.netty5.handler.codec.http.HttpServerUpgradeHandler;
import io.netty5.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty5.handler.codec.http2.Http2CodecUtil;
import io.netty5.handler.codec.http2.Http2ConnectionAdapter;
import io.netty5.handler.codec.http2.Http2FrameCodec;
import io.netty5.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty5.handler.codec.http2.Http2FrameLogger;
import io.netty5.handler.codec.http2.Http2MultiplexHandler;
import io.netty5.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty5.handler.codec.http2.Http2Settings;
import io.netty5.handler.codec.http2.Http2Stream;
import io.netty5.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.logging.LoggingHandler;
import io.netty5.handler.ssl.ApplicationProtocolNames;
import io.netty5.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty5.handler.timeout.ReadTimeoutHandler;
import io.netty5.util.AsciiString;
import io.netty5.util.concurrent.Future;
import reactor.core.publisher.Mono;
import reactor.netty5.ChannelPipelineConfigurer;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.NettyPipeline;
import reactor.netty5.ReactorNetty;
import reactor.netty5.channel.AbstractChannelMetricsHandler;
import reactor.netty5.channel.ChannelMetricsRecorder;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.http.Http2SettingsSpec;
import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.HttpResources;
import reactor.netty5.http.logging.HttpMessageLogFactory;
import reactor.netty5.http.logging.ReactorNettyHttpMessageLogFactory;
import reactor.netty5.http.server.logging.AccessLog;
import reactor.netty5.http.server.logging.AccessLogArgProvider;
import reactor.netty5.http.server.logging.AccessLogHandlerFactory;
import reactor.netty5.resources.LoopResources;
import reactor.netty5.tcp.SslProvider;
import reactor.netty5.transport.ServerTransportConfig;
import reactor.netty5.transport.logging.AdvancedBufferFormat;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import static reactor.netty5.ReactorNetty.ACCESS_LOG_ENABLED;
import static reactor.netty5.ReactorNetty.format;
import static reactor.netty5.http.server.HttpServerFormDecoderProvider.DEFAULT_FORM_DECODER_SPEC;

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
	 * Return the configured read timeout for the request or null.
	 *
	 * @return the configured read timeout for the request or null
	 * @since 1.1.9
	 */
	@Nullable
	public Duration readTimeout() {
		return readTimeout;
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
	 * Return the configured request timeout for the request or null.
	 *
	 * @return the configured request timeout for the request or null
	 * @since 1.1.9
	 */
	@Nullable
	public Duration requestTimeout() {
		return requestTimeout;
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
	 * that will be used for the metrics with {@link reactor.netty5.Metrics#URI} tag
	 *
	 * @return the configured function that receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty5.Metrics#URI} tag
	 */
	@Nullable
	public Function<String, String> uriTagValue() {
		return uriTagValue;
	}


	// Protected/Package private write API

	boolean                                                 accessLogEnabled;
	Function<AccessLogArgProvider, AccessLog>               accessLog;
	BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
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
	Duration                                                readTimeout;
	boolean                                                 redirectHttpToHttps;
	Duration                                                requestTimeout;
	SslProvider                                             sslProvider;
	Function<String, String>                                uriTagValue;

	HttpServerConfig(Map<ChannelOption<?>, ?> options, Map<ChannelOption<?>, ?> childOptions, Supplier<? extends SocketAddress> localAddress) {
		super(options, childOptions, localAddress);
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
		this.readTimeout = parent.readTimeout;
		this.redirectHttpToHttps = parent.redirectHttpToHttps;
		this.requestTimeout = parent.requestTimeout;
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
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			@Nullable Function<String, String> uriTagValue) {
		ChannelPipeline pipeline = ch.pipeline();
		if (accessLogEnabled) {
			pipeline.addLast(NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H2.create(accessLog));
		}
		pipeline.addLast(NettyPipeline.H2ToHttp11Codec, HTTP2_STREAM_FRAME_TO_HTTP_OBJECT)
		        .addLast(NettyPipeline.HttpTrafficHandler,
		                 new Http2StreamBridgeServerHandler(compressPredicate, formDecoderProvider,
		                         forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle,
		                         readTimeout, requestTimeout));

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
					if (metricsRecorder instanceof MicrometerHttpServerMetricsRecorder) {
						handler = new MicrometerHttpServerMetricsHandler((MicrometerHttpServerMetricsHandler) existingHandler);
					}
					else if (metricsRecorder instanceof ContextAwareHttpServerMetricsRecorder) {
						handler = new ContextAwareHttpServerMetricsHandler((ContextAwareHttpServerMetricsHandler) existingHandler);
					}
					else {
						handler = new HttpServerMetricsHandler((HttpServerMetricsHandler) existingHandler);
					}
				}
				else {
					if (metricsRecorder instanceof MicrometerHttpServerMetricsRecorder micrometerHttpServerMetricsRecorder) {
						handler = new MicrometerHttpServerMetricsHandler(micrometerHttpServerMetricsRecorder, uriTagValue);
					}
					else if (metricsRecorder instanceof ContextAwareHttpServerMetricsRecorder contextAwareHttpServerMetricsRecorder) {
						handler = new ContextAwareHttpServerMetricsHandler(contextAwareHttpServerMetricsRecorder, uriTagValue);
					}
					else {
						handler = new HttpServerMetricsHandler((HttpServerMetricsRecorder) metricsRecorder, uriTagValue);
					}
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
					CharSequence length = res.responseHeaders()
					                   .get(HttpHeaderNames.CONTENT_LENGTH);

					if (length == null) {
						return true;
					}

					try {
						return Long.parseLong(length.toString()) >= minResponseSize;
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
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
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
					"reactor.netty5.http.server.h2"));
		}

		Http2FrameCodec http2FrameCodec = http2FrameCodecBuilder.build();
		if (maxStreams != null) {
			http2FrameCodec.connection().addListener(new H2ConnectionListener(p.channel(), maxStreams));
		}
		p.addLast(NettyPipeline.HttpCodec, http2FrameCodec)
		 .addLast(NettyPipeline.H2MultiplexHandler,
		          new Http2MultiplexHandler(new H2Codec(accessLogEnabled, accessLog, compressPredicate,
		                  formDecoderProvider, forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle,
		                  metricsRecorder, minCompressionSize, opsFactory, readTimeout, requestTimeout, uriTagValue)));

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
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			@Nullable Function<String, String> uriTagValue) {
		HttpServerCodec httpServerCodec =
				new HttpServerCodec(decoder.maxInitialLineLength(), decoder.maxHeaderSize(),
						decoder.validateHeaders(), decoder.initialBufferSize(),
						decoder.allowDuplicateContentLengths());

		Http11OrH2CleartextCodec upgrader = new Http11OrH2CleartextCodec(accessLogEnabled, accessLog, compressPredicate,
				 p.get(NettyPipeline.LoggingHandler) != null, enableGracefulShutdown, formDecoderProvider,
				forwardedHeaderHandler, http2SettingsSpec, httpMessageLogFactory, listener, mapHandle, metricsRecorder,
				minCompressionSize, opsFactory, readTimeout, requestTimeout, uriTagValue, decoder.validateHeaders());

		ChannelHandler http2ServerHandler = new H2CleartextCodec(upgrader, http2SettingsSpec != null ? http2SettingsSpec.maxStreams() : null);

		HttpServerUpgradeHandler<DefaultHttpContent> httpServerUpgradeHandler = readTimeout == null && requestTimeout == null ?
				new HttpServerUpgradeHandler<>(httpServerCodec, upgrader, decoder.h2cMaxContentLength()) :
				new ReactorNettyHttpServerUpgradeHandler(httpServerCodec, upgrader, decoder.h2cMaxContentLength(), readTimeout, requestTimeout);

		CleartextHttp2ServerUpgradeHandler h2cUpgradeHandler = new CleartextHttp2ServerUpgradeHandler(
				httpServerCodec, httpServerUpgradeHandler, http2ServerHandler);

		p.addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.H2CUpgradeHandler, h2cUpgradeHandler)
		 .addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpTrafficHandler,
		            new HttpTrafficHandler(compressPredicate, formDecoderProvider,
		                    forwardedHeaderHandler, httpMessageLogFactory, idleTimeout, listener, mapHandle, maxKeepAliveRequests,
		                    readTimeout, requestTimeout));

		if (accessLogEnabled) {
			p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H1.create(accessLog));
		}

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			p.addBefore(NettyPipeline.HttpTrafficHandler, NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
		}

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpServerMetricsRecorder) {
				ChannelHandler handler;
				if (metricsRecorder instanceof MicrometerHttpServerMetricsRecorder micrometerHttpServerMetricsRecorder) {
					handler = new MicrometerHttpServerMetricsHandler(micrometerHttpServerMetricsRecorder, uriTagValue);
				}
				else if (metricsRecorder instanceof ContextAwareHttpServerMetricsRecorder contextAwareHttpServerMetricsRecorder) {
					handler = new ContextAwareHttpServerMetricsHandler(contextAwareHttpServerMetricsRecorder, uriTagValue);
				}
				else {
					handler = new HttpServerMetricsHandler((HttpServerMetricsRecorder) metricsRecorder, uriTagValue);
				}
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
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			@Nullable Function<String, String> uriTagValue) {
		p.addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpCodec,
		            new HttpServerCodec(decoder.maxInitialLineLength(), decoder.maxHeaderSize(),
		                    decoder.validateHeaders(), decoder.initialBufferSize(),
		                    decoder.allowDuplicateContentLengths()))
		 .addBefore(NettyPipeline.ReactiveBridge,
		            NettyPipeline.HttpTrafficHandler,
		            new HttpTrafficHandler(compressPredicate, formDecoderProvider,
		                    forwardedHeaderHandler, httpMessageLogFactory, idleTimeout, listener, mapHandle, maxKeepAliveRequests,
		                    readTimeout, requestTimeout));

		if (accessLogEnabled) {
			p.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H1.create(accessLog));
		}

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			p.addBefore(NettyPipeline.HttpTrafficHandler, NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
		}

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpServerMetricsRecorder) {
				ChannelHandler handler;
				if (metricsRecorder instanceof MicrometerHttpServerMetricsRecorder micrometerHttpServerMetricsRecorder) {
					handler = new MicrometerHttpServerMetricsHandler(micrometerHttpServerMetricsRecorder, uriTagValue);
				}
				else if (metricsRecorder instanceof ContextAwareHttpServerMetricsRecorder contextAwareHttpServerMetricsRecorder) {
					handler = new ContextAwareHttpServerMetricsHandler(contextAwareHttpServerMetricsRecorder, uriTagValue);
				}
				else {
					handler = new HttpServerMetricsHandler((HttpServerMetricsRecorder) metricsRecorder, uriTagValue);
				}
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
			AdvancedBufferFormat.HEX_DUMP
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
		public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ctx.fireChannelExceptionCaught(cause);
		}

		@Override
		public ChannelMetricsRecorder recorder() {
			return recorder;
		}

		@Override
		public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
			return ctx.write(msg);
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
			if (addHttp2FrameCodec) {
				Http2FrameCodec http2FrameCodec = upgrader.http2FrameCodecBuilder.build();
				if (maxStreams != null) {
					http2FrameCodec.connection().addListener(new H2ConnectionListener(ctx.channel(), maxStreams));
				}
				pipeline.addAfter(ctx.name(), NettyPipeline.HttpCodec, http2FrameCodec);
			}
			else if (maxStreams != null) {
				pipeline.get(Http2FrameCodec.class).connection().addListener(new H2ConnectionListener(ctx.channel(), maxStreams));
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

	static final class H2CleartextReadContextHandler extends ChannelHandlerAdapter {
		static final H2CleartextReadContextHandler INSTANCE = new H2CleartextReadContextHandler();

		@Override
		public void channelRegistered(ChannelHandlerContext ctx) {
			ctx.read();
			ctx.fireChannelRegistered();
			ctx.pipeline().remove(this);
		}

		@Override
		public boolean isSharable() {
			return true;
		}
	}

	static final class H2Codec extends ChannelInitializer<Channel> {

		final boolean                                                 accessLogEnabled;
		final Function<AccessLogArgProvider, AccessLog>               accessLog;
		final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
		final HttpServerFormDecoderProvider                           formDecoderProvider;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final HttpMessageLogFactory                                   httpMessageLogFactory;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
		                                                              mapHandle;
		final ChannelMetricsRecorder                                  metricsRecorder;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;
		final Duration                                                readTimeout;
		final Duration                                                requestTimeout;
		final Function<String, String>                                uriTagValue;

		H2Codec(
				boolean accessLogEnabled,
				@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
				HttpServerFormDecoderProvider formDecoderProvider,
				@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
				HttpMessageLogFactory httpMessageLogFactory,
				ConnectionObserver listener,
				@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
				@Nullable ChannelMetricsRecorder metricsRecorder,
				int minCompressionSize,
				ChannelOperations.OnSetup opsFactory,
				@Nullable Duration readTimeout,
				@Nullable Duration requestTimeout,
				@Nullable Function<String, String> uriTagValue) {
			this.accessLogEnabled = accessLogEnabled;
			this.accessLog = accessLog;
			this.compressPredicate = compressPredicate;
			this.formDecoderProvider = formDecoderProvider;
			this.forwardedHeaderHandler = forwardedHeaderHandler;
			this.httpMessageLogFactory = httpMessageLogFactory;
			this.listener = listener;
			this.mapHandle = mapHandle;
			this.metricsRecorder = metricsRecorder;
			this.minCompressionSize = minCompressionSize;
			this.opsFactory = opsFactory;
			this.readTimeout = readTimeout;
			this.requestTimeout = requestTimeout;
			this.uriTagValue = uriTagValue;
		}

		@Override
		protected void initChannel(Channel ch) {
			ch.pipeline().remove(this);
			addStreamHandlers(ch, accessLogEnabled, accessLog, compressPredicate,
					formDecoderProvider, forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle, metricsRecorder,
					minCompressionSize, opsFactory, readTimeout, requestTimeout, uriTagValue);
		}
	}

	static final class Http11OrH2CleartextCodec extends ChannelInitializer<Channel>
			implements HttpServerUpgradeHandler.UpgradeCodecFactory {

		final boolean                                                 accessLogEnabled;
		final Function<AccessLogArgProvider, AccessLog>               accessLog;
		final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
		final HttpServerFormDecoderProvider                           formDecoderProvider;
		final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
		final Http2FrameCodecBuilder                                  http2FrameCodecBuilder;
		final HttpMessageLogFactory                                   httpMessageLogFactory;
		final ConnectionObserver                                      listener;
		final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
		                                                              mapHandle;
		final Long                                                    maxStreams;
		final ChannelMetricsRecorder                                  metricsRecorder;
		final int                                                     minCompressionSize;
		final ChannelOperations.OnSetup                               opsFactory;
		final Duration                                                readTimeout;
		final Duration                                                requestTimeout;
		final Function<String, String>                                uriTagValue;

		Http11OrH2CleartextCodec(
				boolean accessLogEnabled,
				@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
				@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
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
				@Nullable Duration readTimeout,
				@Nullable Duration requestTimeout,
				@Nullable Function<String, String> uriTagValue,
				boolean validate) {
			this.accessLogEnabled = accessLogEnabled;
			this.accessLog = accessLog;
			this.compressPredicate = compressPredicate;
			this.formDecoderProvider = formDecoderProvider;
			this.forwardedHeaderHandler = forwardedHeaderHandler;
			this.http2FrameCodecBuilder =
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
						"reactor.netty5.http.server.h2"));
			}
			this.httpMessageLogFactory = httpMessageLogFactory;
			this.listener = listener;
			this.mapHandle = mapHandle;
			this.metricsRecorder = metricsRecorder;
			this.minCompressionSize = minCompressionSize;
			this.opsFactory = opsFactory;
			this.readTimeout = readTimeout;
			this.requestTimeout = requestTimeout;
			this.uriTagValue = uriTagValue;
		}

		/**
		 * Inline channel initializer
		 */
		@Override
		protected void initChannel(Channel ch) {
			ch.pipeline().remove(this);
			addStreamHandlers(ch, accessLogEnabled, accessLog, compressPredicate,
					formDecoderProvider, forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle, metricsRecorder,
					minCompressionSize, opsFactory, readTimeout, requestTimeout, uriTagValue);
		}

		@Override
		@Nullable
		public HttpServerUpgradeHandler.UpgradeCodec newUpgradeCodec(CharSequence protocol) {
			if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
				return new Http2ServerUpgradeCodec(http2FrameCodecBuilder.build(), new H2CleartextCodec(this, false, false, maxStreams));
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
			assert channel.executor().inEventLoop();
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
		final Duration                                                readTimeout;
		final Duration                                                requestTimeout;
		final Function<String, String>                                uriTagValue;

		H2OrHttp11Codec(HttpServerChannelInitializer initializer, ConnectionObserver listener) {
			super(ApplicationProtocolNames.HTTP_1_1);
			this.accessLogEnabled = initializer.accessLogEnabled;
			this.accessLog = initializer.accessLog;
			this.compressPredicate = compressPredicate(initializer.compressPredicate, initializer.minCompressionSize);
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
			this.readTimeout = initializer.readTimeout;
			this.requestTimeout = initializer.requestTimeout;
			this.uriTagValue = initializer.uriTagValue;
		}

		@Override
		protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(), "Negotiated application-level protocol [" + protocol + "]"));
			}

			ChannelPipeline p = ctx.pipeline();

			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				configureH2Pipeline(p, accessLogEnabled, accessLog, compressPredicate,
						enableGracefulShutdown, formDecoderProvider, forwardedHeaderHandler, http2SettingsSpec, httpMessageLogFactory, idleTimeout, listener, mapHandle,
						metricsRecorder, minCompressionSize, opsFactory, readTimeout, requestTimeout, uriTagValue, decoder.validateHeaders());
				return;
			}

			if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				configureHttp11Pipeline(p, accessLogEnabled, accessLog, compressPredicate,
						decoder, formDecoderProvider, forwardedHeaderHandler, httpMessageLogFactory, idleTimeout, listener, mapHandle,
						maxKeepAliveRequests, metricsRecorder, minCompressionSize, readTimeout, requestTimeout, uriTagValue);

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
		final Duration                                                readTimeout;
		final Duration                                                requestTimeout;
		final Function<String, String>                                uriTagValue;

		HttpServerChannelInitializer(HttpServerConfig config) {
			this.accessLogEnabled = config.accessLogEnabled;
			this.accessLog = config.accessLog;
			this.compressPredicate = config.compressPredicate;
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
			this.readTimeout = config.readTimeout;
			this.redirectHttpToHttps = config.redirectHttpToHttps;
			this.requestTimeout = config.requestTimeout;
			this.sslProvider = config.sslProvider;
			this.uriTagValue = config.uriTagValue;
		}

		@Override
		public void onChannelInit(ConnectionObserver observer, Channel channel, @Nullable SocketAddress remoteAddress) {
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
							readTimeout,
							requestTimeout,
							uriTagValue);
				}
				else if ((protocols & h2) == h2) {
					configureH2Pipeline(
							channel.pipeline(),
							accessLogEnabled,
							accessLog,
							compressPredicate(compressPredicate, minCompressionSize),
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
							readTimeout,
							requestTimeout,
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
							readTimeout,
							requestTimeout,
							uriTagValue);
				}
				else if ((protocols & h11) == h11) {
					configureHttp11Pipeline(
							channel.pipeline(),
							accessLogEnabled,
							accessLog,
							compressPredicate(compressPredicate, minCompressionSize),
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
							readTimeout,
							requestTimeout,
							uriTagValue);
				}
				else if ((protocols & h2c) == h2c) {
					configureH2Pipeline(
							channel.pipeline(),
							accessLogEnabled,
							accessLog,
							compressPredicate(compressPredicate, minCompressionSize),
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
							readTimeout,
							requestTimeout,
							uriTagValue,
							decoder.validateHeaders());
					channel.pipeline().addLast(H2CleartextReadContextHandler.INSTANCE);
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
		}
	}

	static final class ReactorNettyHttpServerUpgradeHandler extends HttpServerUpgradeHandler<DefaultHttpContent> {

		final Duration readTimeout;
		final Duration requestTimeout;

		boolean requestAvailable;
		Future<Void> requestTimeoutFuture;

		ReactorNettyHttpServerUpgradeHandler(
				SourceCodec sourceCodec,
				UpgradeCodecFactory upgradeCodecFactory,
				int maxContentLength,
				@Nullable Duration readTimeout,
				@Nullable Duration requestTimeout) {
			super(sourceCodec, upgradeCodecFactory, maxContentLength);
			this.readTimeout = readTimeout;
			this.requestTimeout = requestTimeout;
		}

		@Override
		public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
			// The upgrade succeeded, the handler is about to be removed from the pipeline, stop all timeouts
			requestAvailable = true;
			stopTimeouts(ctx);
			super.handlerRemoved(ctx);
		}

		@Override
		protected void decodeAndClose(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
			if (msg instanceof HttpRequest req) {
				if (req.headers().contains(HttpHeaderNames.UPGRADE)) {
					if (readTimeout != null) {
						ctx.channel().pipeline().addFirst(NettyPipeline.ReadTimeoutHandler,
								new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS));
					}
					if (requestTimeout != null) {
						requestTimeoutFuture =
								ctx.executor().schedule(new RequestTimeoutTask(ctx), Math.max(requestTimeout.toMillis(), 1), TimeUnit.MILLISECONDS);
					}
				}
			}

			super.decodeAndClose(new DelegatingChannelHandlerContext(ctx) {

				@Override
				public ChannelHandlerContext fireChannelRead(Object msg) {
					// The upgrade did not succeed, the full request was created, stop all timeouts
					requestAvailable = true;
					stopTimeouts(ctx);
					ctx.fireChannelRead(msg);
					return this;
				}
			}, msg);
		}

		void stopTimeouts(ChannelHandlerContext ctx) {
			ChannelHandler handler = ctx.channel().pipeline().get(NettyPipeline.ReadTimeoutHandler);
			if (handler != null) {
				ctx.channel().pipeline().remove(handler);
			}
			if (requestTimeoutFuture != null) {
				requestTimeoutFuture.cancel();
				requestTimeoutFuture = null;
			}
		}

		final class RequestTimeoutTask implements Runnable {

			final ChannelHandlerContext ctx;

			RequestTimeoutTask(ChannelHandlerContext ctx) {
				this.ctx = ctx;
			}

			@Override
			public void run() {
				if (!requestAvailable) {
					ctx.fireChannelInboundEvent(RequestTimeoutException.INSTANCE);
					ctx.close();
				}
			}
		}
	}
}
