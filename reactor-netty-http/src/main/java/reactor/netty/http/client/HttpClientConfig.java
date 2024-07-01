/*
 * Copyright (c) 2020-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Http2SettingsSpec;
import reactor.netty.http.Http3SettingsSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.HttpResources;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.netty.http.logging.ReactorNettyHttpMessageLogFactory;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.transport.ClientTransportConfig;
import reactor.netty.transport.ProxyProvider;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Incubating;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.setChannelContext;
import static reactor.netty.http.client.Http2ConnectionProvider.OWNER;
import static reactor.netty.http.client.Http3Codec.newHttp3ClientConnectionHandler;

/**
 * Encapsulate all necessary configuration for HTTP client transport. The public API is read-only.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public final class HttpClientConfig extends ClientTransportConfig<HttpClientConfig> {

	/**
	 * Return the configured base URL to use for this request/response or null.
	 *
	 * @return the configured base URL to use for this request/response or null
	 */
	@Nullable
	public String baseUrl() {
		return baseUrl;
	}

	@Override
	public int channelHash() {
		int result = super.channelHash();
		result = 31 * result + Boolean.hashCode(acceptGzip);
		result = 31 * result + Boolean.hashCode(acceptBrotli);
		result = 31 * result + Objects.hashCode(decoder);
		result = 31 * result + _protocols;
		result = 31 * result + Objects.hashCode(sslProvider);
		result = 31 * result + Objects.hashCode(uriTagValue);
		return result;
	}

	@Override
	public ChannelOperations.OnSetup channelOperationsProvider() {
		return (ch, c, msg) -> new HttpClientOperations(ch, c, cookieEncoder, cookieDecoder, httpMessageLogFactory);
	}

	@Override
	public ConnectionProvider connectionProvider() {
		return httpConnectionProvider().http1ConnectionProvider();
	}

	/**
	 * Return the configured {@link ClientCookieDecoder} or the default {@link ClientCookieDecoder#STRICT}.
	 *
	 * @return the configured {@link ClientCookieDecoder} or the default {@link ClientCookieDecoder#STRICT}
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 supports only strict validation.
	 */
	@Deprecated
	public ClientCookieDecoder cookieDecoder() {
		return cookieDecoder;
	}

	/**
	 * Return the configured {@link ClientCookieEncoder} or the default {@link ClientCookieEncoder#STRICT}.
	 *
	 * @return the configured {@link ClientCookieEncoder} or the default {@link ClientCookieEncoder#STRICT}
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 supports only strict validation.
	 */
	@Deprecated
	public ClientCookieEncoder cookieEncoder() {
		return cookieEncoder;
	}

	/**
	 * Return the configured HTTP response decoder options or the default.
	 *
	 * @return the configured HTTP request decoder options or the default
	 */
	public HttpResponseDecoderSpec decoder() {
		return decoder;
	}

	/**
	 * Return the configured follow redirect predicate or null.
	 *
	 * @return the configured follow redirect predicate or null
	 */
	@Nullable
	public BiPredicate<HttpClientRequest, HttpClientResponse> followRedirectPredicate() {
		return followRedirectPredicate;
	}

	/**
	 * Return a copy of the request headers.
	 *
	 * @return a copy of the request headers
	 */
	public HttpHeaders headers() {
		return headers.copy();
	}

	/**
	 * Return the HTTP/2 configuration.
	 *
	 * @return the HTTP/2 configuration
	 */
	public Http2SettingsSpec http2SettingsSpec() {
		return http2Settings;
	}

	/**
	 * Return the HTTP/3 configuration.
	 *
	 * @return the HTTP/3 configuration
	 * @since 1.2.0
	 */
	@Incubating
	@Nullable
	public Http3SettingsSpec http3SettingsSpec() {
		return http3Settings;
	}

	/**
	 * Return whether GZip compression is enabled.
	 *
	 * @return whether GZip compression is enabled
	 */
	public boolean isAcceptGzip() {
		return acceptGzip;
	}

	/**
	 * Return whether Brotli compression is enabled.
	 *
	 * @return whether Brotli compression is enabled
	 */
	public boolean isAcceptBrotli() {
		return acceptBrotli;
	}

	/**
	 * Return true if {@code retry once} is disabled, false otherwise.
	 *
	 * @return true if {@code retry once} is disabled, false otherwise
	 */
	public boolean isRetryDisabled() {
		return retryDisabled;
	}

	/**
	 * Returns true if that {@link HttpClient} secured via SSL transport.
	 *
	 * @return true if that {@link HttpClient} secured via SSL transport
	 */
	public boolean isSecure() {
		return sslProvider != null;
	}

	/**
	 * Return the configured request method.
	 *
	 * @return the configured request method
	 */
	public HttpMethod method() {
		return method;
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
	 * Return the configured redirect request {@link BiConsumer} or null.
	 *
	 * @return the configured redirect request {@link BiConsumer} or null
	 */
	@Nullable
	public BiConsumer<HttpHeaders, HttpClientRequest> redirectRequestBiConsumer() {
		return redirectRequestBiConsumer;
	}

	/**
	 * Return the configured redirect request consumer or null.
	 *
	 * @return the configured redirect request consumer or null
	 */
	@Nullable
	public Consumer<HttpClientRequest> redirectRequestConsumer() {
		return redirectRequestConsumer;
	}

	/**
	 * Return the configured response timeout or null.
	 *
	 * @return the configured response timeout or null
	 */
	@Nullable
	public Duration responseTimeout() {
		return responseTimeout;
	}

	/**
	 * Returns the current {@link SslProvider} if that {@link HttpClient} secured via SSL
	 * transport or null.
	 *
	 * @return the current {@link SslProvider} if that {@link HttpClient} secured via SSL
	 * transport or null
	 */
	@Nullable
	public SslProvider sslProvider() {
		return sslProvider;
	}

	/**
	 * Return the configured request uri.
	 *
	 * @return the configured request uri
	 */
	public String uri() {
		return uri == null ? uriStr : uri.toString();
	}

	/**
	 * Returns the configured function that receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#URI} tag.
	 *
	 * @return the configured function that receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#URI} tag
	 */
	@Nullable
	public Function<String, String> uriTagValue() {
		return uriTagValue;
	}

	/**
	 * Return the configured websocket client configuration.
	 *
	 * @return the configured websocket client configuration
	 */
	public WebsocketClientSpec websocketClientSpec() {
		return websocketClientSpec;
	}


	// Protected/Package private write API

	boolean acceptGzip;
	boolean acceptBrotli;
	String baseUrl;
	BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>> body;
	Function<? super Mono<? extends Connection>, ? extends Mono<? extends Connection>> connector;
	ClientCookieDecoder cookieDecoder;
	ClientCookieEncoder cookieEncoder;
	HttpResponseDecoderSpec decoder;
	Function<Mono<HttpClientConfig>, Mono<HttpClientConfig>> deferredConf;
	BiConsumer<? super HttpClientRequest, ? super Connection> doAfterRequest;
	BiConsumer<? super HttpClientResponse, ? super Connection> doAfterResponseSuccess;
	BiConsumer<? super HttpClientResponse, ? super Connection> doOnRedirect;
	BiConsumer<? super HttpClientRequest, ? super Connection> doOnRequest;
	BiConsumer<? super HttpClientRequest, ? super Throwable> doOnRequestError;
	BiConsumer<? super HttpClientResponse, ? super Connection> doOnResponse;
	BiConsumer<? super HttpClientResponse, ? super Throwable> doOnResponseError;
	BiPredicate<HttpClientRequest, HttpClientResponse> followRedirectPredicate;
	HttpHeaders headers;
	Http2SettingsSpec http2Settings;
	Http3SettingsSpec http3Settings;
	HttpMessageLogFactory httpMessageLogFactory;
	HttpMethod method;
	HttpProtocol[] protocols;
	int _protocols;
	BiConsumer<HttpHeaders, HttpClientRequest> redirectRequestBiConsumer;
	Consumer<HttpClientRequest> redirectRequestConsumer;
	Duration responseTimeout;
	boolean retryDisabled;
	SslProvider sslProvider;
	URI uri;
	String uriStr;
	Function<String, String> uriTagValue;
	WebsocketClientSpec websocketClientSpec;

	HttpClientConfig(HttpConnectionProvider connectionProvider, Map<ChannelOption<?>, ?> options,
			Supplier<? extends SocketAddress> remoteAddress) {
		super(connectionProvider, options, remoteAddress);
		this.acceptGzip = false;
		this.acceptBrotli = false;
		this.cookieDecoder = ClientCookieDecoder.STRICT;
		this.cookieEncoder = ClientCookieEncoder.STRICT;
		this.decoder = new HttpResponseDecoderSpec();
		this.headers = new DefaultHttpHeaders();
		this.httpMessageLogFactory = ReactorNettyHttpMessageLogFactory.INSTANCE;
		this.method = HttpMethod.GET;
		this.protocols = new HttpProtocol[]{HttpProtocol.HTTP11};
		this._protocols = h11;
		this.retryDisabled = false;
	}

	HttpClientConfig(HttpClientConfig parent) {
		super(parent);
		this.acceptGzip = parent.acceptGzip;
		this.acceptBrotli = parent.acceptBrotli;
		this.baseUrl = parent.baseUrl;
		this.body = parent.body;
		this.connector = parent.connector;
		this.cookieDecoder = parent.cookieDecoder;
		this.cookieEncoder = parent.cookieEncoder;
		this.decoder = parent.decoder;
		this.deferredConf = parent.deferredConf;
		this.doAfterRequest = parent.doAfterRequest;
		this.doAfterResponseSuccess = parent.doAfterResponseSuccess;
		this.doOnRedirect = parent.doOnRedirect;
		this.doOnRequest = parent.doOnRequest;
		this.doOnRequestError = parent.doOnRequestError;
		this.doOnResponse = parent.doOnResponse;
		this.doOnResponseError = parent.doOnResponseError;
		this.followRedirectPredicate = parent.followRedirectPredicate;
		this.headers = parent.headers;
		this.http2Settings = parent.http2Settings;
		this.http3Settings = parent.http3Settings;
		this.httpMessageLogFactory = parent.httpMessageLogFactory;
		this.method = parent.method;
		this.protocols = parent.protocols;
		this._protocols = parent._protocols;
		this.redirectRequestBiConsumer = parent.redirectRequestBiConsumer;
		this.redirectRequestConsumer = parent.redirectRequestConsumer;
		this.responseTimeout = parent.responseTimeout;
		this.retryDisabled = parent.retryDisabled;
		this.sslProvider = parent.sslProvider;
		this.uri = parent.uri;
		this.uriStr = parent.uriStr;
		this.uriTagValue = parent.uriTagValue;
		this.websocketClientSpec = parent.websocketClientSpec;
	}

	@Override
	public ChannelInitializer<Channel> channelInitializer(ConnectionObserver connectionObserver,
			@Nullable SocketAddress remoteAddress, boolean onServer) {
		ChannelInitializer<Channel> channelInitializer = super.channelInitializer(connectionObserver, remoteAddress, onServer);
		return (_protocols & h3) == h3 ? new Http3ChannelInitializer(this, channelInitializer, connectionObserver) : channelInitializer;
	}

	/**
	 * Provides a global {@link AddressResolverGroup} from {@link HttpResources}
	 * that is shared amongst all HTTP clients. {@link AddressResolverGroup} uses the global
	 * {@link LoopResources} from {@link HttpResources}.
	 *
	 * @return the global {@link AddressResolverGroup}
	 */
	@Override
	public AddressResolverGroup<?> defaultAddressResolverGroup() {
		return HttpResources.get().getOrCreateDefaultResolver();
	}

	@Override
	protected void bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		super.bindAddress(bindAddressSupplier);
	}

	@Override
	protected Class<? extends Channel> channelType(boolean isDomainSocket) {
		return isDomainSocket ? DomainSocketChannel.class :
				(_protocols & h3) == h3 ? DatagramChannel.class : SocketChannel.class;
	}

	@Override
	protected ConnectionObserver defaultConnectionObserver() {
		if (doAfterRequest == null && doAfterResponseSuccess == null && doOnRedirect == null &&
					doOnRequest == null && doOnRequestError == null && doOnResponse == null && doOnResponseError == null) {
			return super.defaultConnectionObserver();
		}
		else {
			return super.defaultConnectionObserver()
			            .then(new HttpClientDoOn(doAfterRequest, doAfterResponseSuccess, doOnRedirect, doOnRequest,
			                doOnRequestError, doOnResponse, doOnResponseError));
		}
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
		return MicrometerHttpClientMetricsRecorder.INSTANCE;
	}

	@Override
	protected ChannelPipelineConfigurer defaultOnChannelInit() {
		return super.defaultOnChannelInit()
		            .then(new HttpClientChannelInitializer(this));
	}

	@Override
	protected void loggingHandler(LoggingHandler loggingHandler) {
		super.loggingHandler(loggingHandler);
	}

	@Override
	protected void metricsRecorder(@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder) {
		super.metricsRecorder(metricsRecorder);
	}

	@Override
	protected void proxyProvider(ProxyProvider proxyProvider) {
		super.proxyProvider(proxyProvider);
	}

	@Override
	protected AddressResolverGroup<?> resolverInternal() {
		return super.resolverInternal();
	}

	void deferredConf(Function<HttpClientConfig, Mono<HttpClientConfig>> deferrer) {
		if (deferredConf != null) {
			deferredConf = deferredConf.andThen(deferredConf -> deferredConf.flatMap(deferrer));
		}
		else {
			deferredConf = deferredConf -> deferredConf.flatMap(deferrer);
		}
	}

	HttpConnectionProvider httpConnectionProvider() {
		return (HttpConnectionProvider) super.connectionProvider();
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
			else if (p == HttpProtocol.HTTP3) {
				_protocols |= h3;
			}
		}

		this._protocols = _protocols;
	}

	boolean checkProtocol(int protocol) {
		return (_protocols & protocol) == protocol;
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

	static void addStreamHandlers(
			Channel ch,
			ConnectionObserver obs,
			ChannelOperations.OnSetup opsFactory,
			boolean acceptGzip,
			boolean acceptBrotli,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			@Nullable SocketAddress proxyAddress,
			SocketAddress remoteAddress,
			long responseTimeoutMillis,
			@Nullable Function<String, String> uriTagValue) {

		if (HttpClientOperations.log.isDebugEnabled()) {
			HttpClientOperations.log.debug(format(ch, "New HTTP/2 stream"));
		}

		ChannelPipeline pipeline = ch.pipeline();
		pipeline.addLast(NettyPipeline.H2ToHttp11Codec, HTTP2_STREAM_FRAME_TO_HTTP_OBJECT)
				.addLast(NettyPipeline.HttpTrafficHandler, HTTP_2_STREAM_BRIDGE_CLIENT_HANDLER);

		if (acceptGzip || acceptBrotli) {
			pipeline.addLast(NettyPipeline.HttpDecompressor, new HttpContentDecompressor());
		}

		ChannelOperations.addReactiveBridge(ch, opsFactory, obs);

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpClientMetricsRecorder) {
				ChannelHandler handler;
				Channel parent = ch.parent();
				ChannelHandler existingHandler = parent.pipeline().get(NettyPipeline.HttpMetricsHandler);
				if (existingHandler != null) {
					// This use case can happen only in HTTP/2 clear text connection upgrade
					parent.pipeline().remove(NettyPipeline.HttpMetricsHandler);
					if (metricsRecorder instanceof MicrometerHttpClientMetricsRecorder) {
						handler = new MicrometerHttpClientMetricsHandler((MicrometerHttpClientMetricsHandler) existingHandler);
					}
					else if (metricsRecorder instanceof ContextAwareHttpClientMetricsRecorder) {
						handler = new ContextAwareHttpClientMetricsHandler((ContextAwareHttpClientMetricsHandler) existingHandler);
					}
					else {
						handler = new HttpClientMetricsHandler((HttpClientMetricsHandler) existingHandler);
					}
				}
				else {
					if (metricsRecorder instanceof MicrometerHttpClientMetricsRecorder) {
						handler = new MicrometerHttpClientMetricsHandler((MicrometerHttpClientMetricsRecorder) metricsRecorder, remoteAddress, proxyAddress, uriTagValue);
					}
					else if (metricsRecorder instanceof ContextAwareHttpClientMetricsRecorder) {
						handler = new ContextAwareHttpClientMetricsHandler((ContextAwareHttpClientMetricsRecorder) metricsRecorder, remoteAddress, proxyAddress, uriTagValue);
					}
					else {
						handler = new HttpClientMetricsHandler((HttpClientMetricsRecorder) metricsRecorder, remoteAddress, proxyAddress, uriTagValue);
					}
				}
				pipeline.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpMetricsHandler, handler);
			}
		}

		if (responseTimeoutMillis > -1) {
			Connection conn = Connection.from(ch);
			if (ch.pipeline().get(NettyPipeline.HttpMetricsHandler) != null) {
				if (ch.pipeline().get(NettyPipeline.ResponseTimeoutHandler) == null) {
					ch.pipeline().addBefore(NettyPipeline.HttpMetricsHandler, NettyPipeline.ResponseTimeoutHandler,
							new ReadTimeoutHandler(responseTimeoutMillis, TimeUnit.MILLISECONDS));
					if (conn.isPersistent()) {
						conn.onTerminate().subscribe(null, null, () -> conn.removeHandler(NettyPipeline.ResponseTimeoutHandler));
					}
				}
			}
			else {
				conn.addHandlerFirst(NettyPipeline.ResponseTimeoutHandler,
						new ReadTimeoutHandler(responseTimeoutMillis, TimeUnit.MILLISECONDS));
			}
		}

		if (log.isDebugEnabled()) {
			log.debug(format(ch, "Initialized HTTP/2 stream pipeline {}"), ch.pipeline());
		}

		ChannelOperations<?, ?> ops = opsFactory.create(Connection.from(ch), obs, null);
		if (ops != null) {
			ops.bind();
		}
	}

	static void configureHttp2Pipeline(ChannelPipeline p, HttpResponseDecoderSpec decoder,
			Http2Settings http2Settings, ConnectionObserver observer) {
		Http2FrameCodecBuilder http2FrameCodecBuilder =
				Http2FrameCodecBuilder.forClient()
				                      .validateHeaders(decoder.validateHeaders())
				                      .initialSettings(http2Settings);

		if (p.get(NettyPipeline.LoggingHandler) != null) {
			http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG,
					"reactor.netty.http.client.h2"));
		}

		p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.H2Flush, new FlushConsolidationHandler(1024, true))
		 .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpCodec, http2FrameCodecBuilder.build())
		 .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.H2MultiplexHandler, new Http2MultiplexHandler(H2InboundStreamHandler.INSTANCE))
		 .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpTrafficHandler, new HttpTrafficHandler(observer));
	}

	static void configureHttp3Pipeline(ChannelPipeline p, boolean removeMetricsRecorder, boolean removeProxyProvider) {
		p.remove(NettyPipeline.ReactiveBridge);

		p.addLast(NettyPipeline.HttpCodec, newHttp3ClientConnectionHandler());

		if (removeMetricsRecorder) {
			// Connection metrics are not applicable
			p.remove(NettyPipeline.ChannelMetricsHandler);
		}

		if (removeProxyProvider) {
			p.remove(NettyPipeline.ProxyHandler);
		}
	}

	@SuppressWarnings("deprecation")
	static void configureHttp11OrH2CleartextPipeline(
			ChannelPipeline p,
			boolean acceptGzip,
			boolean acceptBrotli,
			HttpResponseDecoderSpec decoder,
			Http2Settings http2Settings,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			ConnectionObserver observer,
			ChannelOperations.OnSetup opsFactory,
			@Nullable SocketAddress proxyAddress,
			SocketAddress remoteAddress,
			@Nullable Function<String, String> uriTagValue) {
		HttpDecoderConfig decoderConfig = new HttpDecoderConfig();
		decoderConfig.setMaxInitialLineLength(decoder.maxInitialLineLength())
		             .setMaxHeaderSize(decoder.maxHeaderSize())
		             .setMaxChunkSize(decoder.maxChunkSize())
		             .setValidateHeaders(decoder.validateHeaders())
		             .setInitialBufferSize(decoder.initialBufferSize())
		             .setAllowDuplicateContentLengths(decoder.allowDuplicateContentLengths());
		HttpClientCodec httpClientCodec =
				new HttpClientCodec(decoderConfig, decoder.failOnMissingResponse, decoder.parseHttpAfterConnectRequest);

		Http2FrameCodecBuilder http2FrameCodecBuilder =
				Http2FrameCodecBuilder.forClient()
						.validateHeaders(decoder.validateHeaders())
						.initialSettings(http2Settings);

		if (p.get(NettyPipeline.LoggingHandler) != null) {
			http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG,
					"reactor.netty.http.client.h2"));
		}

		Http2FrameCodec http2FrameCodec = http2FrameCodecBuilder.build();

		Http2ClientUpgradeCodec upgradeCodec = new Http2ClientUpgradeCodec(http2FrameCodec,
				new H2CleartextCodec(http2FrameCodec, opsFactory, acceptGzip, acceptBrotli, metricsRecorder, proxyAddress, remoteAddress, uriTagValue));

		HttpClientUpgradeHandler upgradeHandler =
				new ReactorNettyHttpClientUpgradeHandler(httpClientCodec, upgradeCodec, decoder.h2cMaxContentLength());

		p.addBefore(NettyPipeline.ReactiveBridge, null, httpClientCodec)
		 .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.H2CUpgradeHandler, upgradeHandler)
		 .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpTrafficHandler, new HttpTrafficHandler(observer));

		if (acceptGzip || acceptBrotli) {
			p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpDecompressor, new HttpContentDecompressor());
		}

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpClientMetricsRecorder) {
				ChannelHandler handler;
				if (metricsRecorder instanceof MicrometerHttpClientMetricsRecorder) {
					handler = new MicrometerHttpClientMetricsHandler((MicrometerHttpClientMetricsRecorder) metricsRecorder, remoteAddress, proxyAddress, uriTagValue);
				}
				else if (metricsRecorder instanceof ContextAwareHttpClientMetricsRecorder) {
					handler = new ContextAwareHttpClientMetricsHandler((ContextAwareHttpClientMetricsRecorder) metricsRecorder, remoteAddress, proxyAddress, uriTagValue);
				}
				else {
					handler = new HttpClientMetricsHandler((HttpClientMetricsRecorder) metricsRecorder, remoteAddress, proxyAddress, uriTagValue);
				}
				p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpMetricsHandler, handler);
			}
		}

	}

	@SuppressWarnings("deprecation")
	static void configureHttp11Pipeline(ChannelPipeline p,
			boolean acceptGzip,
            boolean acceptBrotli,
			HttpResponseDecoderSpec decoder,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			@Nullable SocketAddress proxyAddress,
			SocketAddress remoteAddress,
			@Nullable Function<String, String> uriTagValue) {
		HttpDecoderConfig decoderConfig = new HttpDecoderConfig();
		decoderConfig.setMaxInitialLineLength(decoder.maxInitialLineLength())
		             .setMaxHeaderSize(decoder.maxHeaderSize())
		             .setMaxChunkSize(decoder.maxChunkSize())
		             .setValidateHeaders(decoder.validateHeaders())
		             .setInitialBufferSize(decoder.initialBufferSize())
		             .setAllowDuplicateContentLengths(decoder.allowDuplicateContentLengths());
		p.addBefore(NettyPipeline.ReactiveBridge,
				NettyPipeline.HttpCodec,
				new HttpClientCodec(decoderConfig, decoder.failOnMissingResponse, decoder.parseHttpAfterConnectRequest));

		if (acceptGzip || acceptBrotli) {
			p.addAfter(NettyPipeline.HttpCodec, NettyPipeline.HttpDecompressor, new HttpContentDecompressor());
		}

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpClientMetricsRecorder) {
				ChannelHandler handler;
				if (metricsRecorder instanceof MicrometerHttpClientMetricsRecorder) {
					handler = new MicrometerHttpClientMetricsHandler((MicrometerHttpClientMetricsRecorder) metricsRecorder, remoteAddress, proxyAddress, uriTagValue);
				}
				else if (metricsRecorder instanceof ContextAwareHttpClientMetricsRecorder) {
					handler = new ContextAwareHttpClientMetricsHandler((ContextAwareHttpClientMetricsRecorder) metricsRecorder, remoteAddress, proxyAddress, uriTagValue);
				}
				else {
					handler = new HttpClientMetricsHandler((HttpClientMetricsRecorder) metricsRecorder, remoteAddress, proxyAddress, uriTagValue);
				}
				p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpMetricsHandler, handler);
			}
		}
	}

	static final Pattern FOLLOW_REDIRECT_CODES = Pattern.compile("30[12378]");

	static final BiPredicate<HttpClientRequest, HttpClientResponse> FOLLOW_REDIRECT_PREDICATE =
			(req, res) -> FOLLOW_REDIRECT_CODES.matcher(res.status()
			                                               .codeAsText())
			                                   .matches();

	static final int h3 = 0b1000;

	static final int h2 = 0b010;

	static final int h2c = 0b001;

	static final int h11 = 0b100;

	static final int h11orH2 = h11 | h2;

	static final int h11orH2C = h11 | h2c;

	static final Http2StreamFrameToHttpObjectCodec HTTP2_STREAM_FRAME_TO_HTTP_OBJECT =
			new Http2StreamFrameToHttpObjectCodec(false);

	static final Http2StreamBridgeClientHandler HTTP_2_STREAM_BRIDGE_CLIENT_HANDLER =
			new Http2StreamBridgeClientHandler();

	static final Logger log = Loggers.getLogger(HttpClientConfig.class);

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedByteBufFormat.HEX_DUMP
					.toLoggingHandler(HttpClient.class.getName(), LogLevel.DEBUG, Charset.defaultCharset());

	/**
	 * Default value whether the SSL debugging on the client side will be enabled/disabled,
	 * fallback to SSL debugging disabled.
	 */
	static final boolean SSL_DEBUG = Boolean.parseBoolean(System.getProperty(ReactorNetty.SSL_CLIENT_DEBUG, "false"));

	static final class H2CleartextCodec extends ChannelHandlerAdapter {

		final boolean acceptGzip;
		final boolean acceptBrotli;
		final Http2FrameCodec http2FrameCodec;
		final ChannelMetricsRecorder metricsRecorder;
		final ChannelOperations.OnSetup opsFactory;
		final SocketAddress proxyAddress;
		final SocketAddress remoteAddress;
		final Function<String, String> uriTagValue;

		H2CleartextCodec(
				Http2FrameCodec http2FrameCodec,
				ChannelOperations.OnSetup opsFactory,
				boolean acceptGzip,
				boolean acceptBrotli,
				@Nullable ChannelMetricsRecorder metricsRecorder,
				@Nullable SocketAddress proxyAddress,
				SocketAddress remoteAddress,
				@Nullable Function<String, String> uriTagValue) {
			this.acceptGzip = acceptGzip;
			this.acceptBrotli = acceptBrotli;
			this.http2FrameCodec = http2FrameCodec;
			this.metricsRecorder = metricsRecorder;
			this.opsFactory = opsFactory;
			this.proxyAddress = proxyAddress;
			this.remoteAddress = remoteAddress;
			this.uriTagValue = uriTagValue;
		}

		@Override
		public void handlerAdded(ChannelHandlerContext ctx) {
			ChannelPipeline pipeline = ctx.pipeline();
			ReadTimeoutHandler responseTimeoutHandler =
					(ReadTimeoutHandler) pipeline.get(NettyPipeline.ResponseTimeoutHandler);
			Http2MultiplexHandler http2MultiplexHandler;
			ConnectionObserver channelOwner = ctx.channel().attr(OWNER).get();
			Http2ConnectionProvider.DisposableAcquire owner = null;
			ConnectionObserver obs = null;
			if (channelOwner instanceof Http2ConnectionProvider.DisposableAcquire) {
				owner = (Http2ConnectionProvider.DisposableAcquire) channelOwner;
				obs = owner.obs;
			}
			if (responseTimeoutHandler != null) {
				pipeline.remove(NettyPipeline.ResponseTimeoutHandler);
				http2MultiplexHandler = new Http2MultiplexHandler(H2InboundStreamHandler.INSTANCE,
						new H2Codec(owner, obs, opsFactory, acceptGzip, acceptBrotli, metricsRecorder, proxyAddress,
								remoteAddress, responseTimeoutHandler.getReaderIdleTimeInMillis(), uriTagValue));
			}
			else {
				http2MultiplexHandler = new Http2MultiplexHandler(H2InboundStreamHandler.INSTANCE,
						new H2Codec(owner, obs, opsFactory, acceptGzip, acceptBrotli, metricsRecorder, proxyAddress, remoteAddress, uriTagValue));
			}
			pipeline.addAfter(ctx.name(), NettyPipeline.HttpCodec, http2FrameCodec)
			        .addAfter(NettyPipeline.HttpCodec, NettyPipeline.H2MultiplexHandler, http2MultiplexHandler);
			if (pipeline.get(NettyPipeline.HttpDecompressor) != null) {
				pipeline.remove(NettyPipeline.HttpDecompressor);
			}
			pipeline.remove(NettyPipeline.ReactiveBridge);
			pipeline.remove(this);
		}
	}

	static final class H2Codec extends ChannelInitializer<Channel> {

		final boolean acceptGzip;
		final boolean acceptBrotli;
		final ChannelMetricsRecorder metricsRecorder;
		final ConnectionObserver observer;
		final ChannelOperations.OnSetup opsFactory;
		final Http2ConnectionProvider.DisposableAcquire owner;
		final long responseTimeoutMillis;
		final SocketAddress proxyAddress;
		final SocketAddress remoteAddress;
		final Function<String, String> uriTagValue;

		H2Codec(
				@Nullable Http2ConnectionProvider.DisposableAcquire owner,
				@Nullable ConnectionObserver observer,
				ChannelOperations.OnSetup opsFactory,
				boolean acceptGzip,
				boolean acceptBrotli,
				@Nullable ChannelMetricsRecorder metricsRecorder,
				@Nullable SocketAddress proxyAddress,
				SocketAddress remoteAddress,
				@Nullable Function<String, String> uriTagValue) {
			// Handle outbound and upgrade streams
			this(owner, observer, opsFactory, acceptGzip, acceptBrotli, metricsRecorder, proxyAddress, remoteAddress, -1, uriTagValue);
		}

		H2Codec(
				@Nullable Http2ConnectionProvider.DisposableAcquire owner,
				@Nullable ConnectionObserver observer,
				ChannelOperations.OnSetup opsFactory,
				boolean acceptGzip,
				boolean acceptBrotli,
				@Nullable ChannelMetricsRecorder metricsRecorder,
				@Nullable SocketAddress proxyAddress,
				SocketAddress remoteAddress,
				long responseTimeoutMillis,
				@Nullable Function<String, String> uriTagValue) {
			// Handle outbound and upgrade streams
			this.acceptGzip = acceptGzip;
			this.acceptBrotli = acceptBrotli;
			this.metricsRecorder = metricsRecorder;
			this.observer = observer;
			this.opsFactory = opsFactory;
			this.owner = owner;
			this.responseTimeoutMillis = responseTimeoutMillis;
			this.proxyAddress = proxyAddress;
			this.remoteAddress = remoteAddress;
			this.uriTagValue = uriTagValue;
		}

		@Override
		protected void initChannel(Channel ch) {
			if (observer != null && opsFactory != null && owner != null) {
				Http2ConnectionProvider.registerClose(ch, owner);
				if (!owner.currentContext().isEmpty()) {
					setChannelContext(ch, owner.currentContext());
				}
				addStreamHandlers(ch, observer.then(new StreamConnectionObserver(owner.currentContext())), opsFactory,
						acceptGzip, acceptBrotli, metricsRecorder, proxyAddress, remoteAddress, responseTimeoutMillis, uriTagValue);
			}
			else {
				// Handle server pushes (inbound streams)
				// TODO this is not supported
			}
		}
	}

	/**
	 * Handle inbound streams (server pushes).
	 * This feature is not supported and disabled.
	 */
	static final class H2InboundStreamHandler extends ChannelHandlerAdapter {
		static final ChannelHandler INSTANCE = new H2InboundStreamHandler();

		@Override
		public boolean isSharable() {
			return true;
		}
	}

	static final class H2OrHttp11Codec extends ChannelInboundHandlerAdapter {
		final boolean                                    acceptGzip;
		final boolean                                    acceptBrotli;
		final HttpResponseDecoderSpec                    decoder;
		final Http2Settings                              http2Settings;
		final ChannelMetricsRecorder                     metricsRecorder;
		final ConnectionObserver                         observer;
		final SocketAddress                              proxyAddress;
		final SocketAddress                              remoteAddress;
		final Function<String, String>                   uriTagValue;

		H2OrHttp11Codec(HttpClientChannelInitializer initializer, ConnectionObserver observer, SocketAddress remoteAddress) {
			this.acceptGzip = initializer.acceptGzip;
			this.acceptBrotli = initializer.acceptBrotli;
			this.decoder = initializer.decoder;
			this.http2Settings = initializer.http2Settings;
			this.metricsRecorder = initializer.metricsRecorder;
			this.observer = observer;
			this.proxyAddress = initializer.proxyAddress;
			this.remoteAddress = remoteAddress;
			this.uriTagValue = initializer.uriTagValue;
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			ChannelHandler handler = ctx.pipeline().get(NettyPipeline.SslHandler);
			if (handler instanceof SslHandler) {
				SslHandler sslHandler = (SslHandler) handler;

				String protocol = sslHandler.applicationProtocol() != null ? sslHandler.applicationProtocol() : ApplicationProtocolNames.HTTP_1_1;
				if (log.isDebugEnabled()) {
					log.debug(format(ctx.channel(), "Negotiated application-level protocol [" + protocol + "]"));
				}
				if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
					configureHttp2Pipeline(ctx.channel().pipeline(), decoder, http2Settings, observer);
				}
				else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
					configureHttp11Pipeline(ctx.channel().pipeline(), acceptGzip, acceptBrotli, decoder, metricsRecorder, proxyAddress, remoteAddress, uriTagValue);
				}
				else {
					throw new IllegalStateException("unknown protocol: " + protocol);
				}

				ctx.fireChannelActive();

				ctx.channel().pipeline().remove(this);
			}
			else {
				throw new IllegalStateException("Cannot determine negotiated application-level protocol.");
			}
		}
	}

	static final class HttpClientChannelInitializer implements ChannelPipelineConfigurer {

		final boolean                                    acceptGzip;
		final boolean                                    acceptBrotli;
		final HttpResponseDecoderSpec                    decoder;
		final Http2Settings                              http2Settings;
		final ChannelMetricsRecorder                     metricsRecorder;
		final ChannelOperations.OnSetup                  opsFactory;
		final int                                        protocols;
		final SocketAddress                              proxyAddress;
		final SslProvider                                sslProvider;
		final Function<String, String>                   uriTagValue;

		HttpClientChannelInitializer(HttpClientConfig config) {
			this.acceptGzip = config.acceptGzip;
			this.acceptBrotli = config.acceptBrotli;
			this.decoder = config.decoder;
			this.http2Settings = config.http2Settings();
			this.metricsRecorder = config.metricsRecorderInternal();
			this.opsFactory = config.channelOperationsProvider();
			this.protocols = config._protocols;
			this.proxyAddress = config.proxyProvider() != null ? config.proxyProvider().getSocketAddress().get() : null;
			this.sslProvider = config.sslProvider;
			this.uriTagValue = config.uriTagValue;
		}

		@Override
		public void onChannelInit(ConnectionObserver observer, Channel channel, @Nullable SocketAddress remoteAddress) {
			if (sslProvider != null) {
				if ((protocols & h3) != h3) {
					sslProvider.addSslHandler(channel, remoteAddress, SSL_DEBUG);
				}

				if ((protocols & h11orH2) == h11orH2) {
					channel.pipeline()
					       .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.H2OrHttp11Codec,
					               new H2OrHttp11Codec(this, observer, remoteAddress));
				}
				else if ((protocols & h11) == h11) {
					configureHttp11Pipeline(channel.pipeline(), acceptGzip, acceptBrotli, decoder, metricsRecorder, proxyAddress, remoteAddress, uriTagValue);
				}
				else if ((protocols & h2) == h2) {
					configureHttp2Pipeline(channel.pipeline(), decoder, http2Settings, observer);
				}
				else if ((protocols & h3) == h3) {
					configureHttp3Pipeline(channel.pipeline(), metricsRecorder != null, proxyAddress != null);
				}
			}
			else {
				if ((protocols & h11orH2C) == h11orH2C) {
					configureHttp11OrH2CleartextPipeline(channel.pipeline(), acceptGzip, acceptBrotli, decoder, http2Settings, metricsRecorder, observer, opsFactory, proxyAddress, remoteAddress, uriTagValue);
				}
				else if ((protocols & h11) == h11) {
					configureHttp11Pipeline(channel.pipeline(), acceptGzip, acceptBrotli, decoder, metricsRecorder, proxyAddress, remoteAddress, uriTagValue);
				}
				else if ((protocols & h2c) == h2c) {
					configureHttp2Pipeline(channel.pipeline(), decoder, http2Settings, observer);
				}
			}
		}
	}

	static final class HttpClientDoOn implements ConnectionObserver {

		final BiConsumer<? super HttpClientRequest, ? super Connection> doAfterRequest;
		final BiConsumer<? super HttpClientResponse, ? super Connection> doAfterResponseSuccess;
		final BiConsumer<? super HttpClientResponse, ? super Connection> doOnRedirect;
		final BiConsumer<? super HttpClientRequest, ? super Connection> doOnRequest;
		final BiConsumer<? super HttpClientRequest, ? super Throwable> doOnRequestError;
		final BiConsumer<? super HttpClientResponse, ? super Connection> doOnResponse;
		final BiConsumer<? super HttpClientResponse, ? super Throwable> doOnResponseError;

		HttpClientDoOn(@Nullable BiConsumer<? super HttpClientRequest,  ? super Connection> doAfterRequest,
				@Nullable BiConsumer<? super HttpClientResponse,  ? super Connection> doAfterResponseSuccess,
				@Nullable BiConsumer<? super HttpClientResponse, ? super Connection> doOnRedirect,
				@Nullable BiConsumer<? super HttpClientRequest,  ? super Connection> doOnRequest,
				@Nullable BiConsumer<? super HttpClientRequest,  ? super Throwable> doOnRequestError,
				@Nullable BiConsumer<? super HttpClientResponse, ? super Connection> doOnResponse,
				@Nullable BiConsumer<? super HttpClientResponse, ? super Throwable> doOnResponseError) {
			this.doAfterRequest = doAfterRequest;
			this.doAfterResponseSuccess = doAfterResponseSuccess;
			this.doOnRedirect = doOnRedirect;
			this.doOnRequest = doOnRequest;
			this.doOnRequestError = doOnRequestError;
			this.doOnResponse = doOnResponse;
			this.doOnResponseError = doOnResponseError;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (doOnRequest != null && newState == HttpClientState.REQUEST_PREPARED) {
				doOnRequest.accept(connection.as(HttpClientOperations.class), connection);
				return;
			}
			if (doAfterResponseSuccess != null && newState == HttpClientState.RESPONSE_COMPLETED) {
				doAfterResponseSuccess.accept(connection.as(HttpClientOperations.class), connection);
				return;
			}
			if (doAfterRequest != null && newState == HttpClientState.REQUEST_SENT) {
				doAfterRequest.accept(connection.as(HttpClientOperations.class), connection);
				return;
			}
			if (doOnResponse != null && newState == HttpClientState.RESPONSE_RECEIVED) {
				doOnResponse.accept(connection.as(HttpClientOperations.class), connection);
				return;
			}
			if (doOnResponseError != null && newState == HttpClientState.RESPONSE_INCOMPLETE) {
				HttpClientOperations ops = connection.as(HttpClientOperations.class);
				if (ops == null) {
					return;
				}
				if (ops.responseState != null) {
					doOnResponseError.accept(ops, new PrematureCloseException("Connection prematurely closed DURING response"));
				}
			}
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			if (doOnRedirect != null && error instanceof RedirectClientException) {
				doOnRedirect.accept(connection.as(HttpClientOperations.class), connection);
				return;
			}
			HttpClientOperations ops = connection.as(HttpClientOperations.class);
			if (ops == null) {
				return;
			}
			if (doOnRequestError != null && ops.retrying && ops.responseState == null) {
				doOnRequestError.accept(connection.as(HttpClientOperations.class), error);
				return;
			}
			if (doOnResponseError != null && ops.responseState != null &&
					!(error instanceof RedirectClientException)) {
				doOnResponseError.accept(connection.as(HttpClientOperations.class), error);
			}
		}
	}

	static final class ReactorNettyHttpClientUpgradeHandler extends HttpClientUpgradeHandler {

		boolean is100Continue;

		ReactorNettyHttpClientUpgradeHandler(SourceCodec sourceCodec, UpgradeCodec upgradeCodec, int maxContentLength) {
			super(sourceCodec, upgradeCodec, maxContentLength);
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			if (msg instanceof HttpResponse) {
				if (HttpResponseStatus.CONTINUE.equals(((HttpResponse) msg).status())) {
					is100Continue = true;
					ReferenceCountUtil.release(msg);
					ctx.read();
					return;
				}
				is100Continue = false;
			}

			if (is100Continue && msg instanceof LastHttpContent) {
				is100Continue = false;
				((LastHttpContent) msg).release();
				return;
			}

			super.channelRead(ctx, msg);
		}
	}

	static final class StreamConnectionObserver implements ConnectionObserver {

		final Context context;

		StreamConnectionObserver(Context context) {
			this.context = context;
		}

		@Override
		public Context currentContext() {
			return context;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void onStateChange(Connection connection, State state) {
			if (state == State.DISCONNECTING) {
				if (!connection.isPersistent() && connection.channel().isActive()) {
					// Will be released by closeFuture
					// "FutureReturnValueIgnored" this is deliberate
					connection.channel().close();
				}
			}
		}
	}
}
