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

package reactor.netty.http.client;

import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslHandler;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.Future;
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
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.transport.ClientTransportConfig;
import reactor.netty.transport.NameResolverProvider;
import reactor.netty.transport.ProxyProvider;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.client.Http2ConnectionProvider.OWNER;

/**
 * Encapsulate all necessary configuration for HTTP client transport. The public API is read-only.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public final class HttpClientConfig extends ClientTransportConfig<HttpClientConfig> {

	/**
	 * Return the configure base URL to use for this request/response or null.
	 *
	 * @return the configure base URL to use for this request/response or null
	 */
	@Nullable
	public String baseUrl() {
		return baseUrl;
	}

	@Override
	public int channelHash() {
		return Objects.hash(super.channelHash(), acceptGzip, decoder, _protocols, sslProvider);
	}

	@Override
	public ChannelOperations.OnSetup channelOperationsProvider() {
		return (ch, c, msg) -> new HttpClientOperations(ch, c, cookieEncoder, cookieDecoder);
	}

	/**
	 * Return the configured {@link ClientCookieDecoder} or the default {@link ClientCookieDecoder#STRICT}.
	 *
	 * @return the configured {@link ClientCookieDecoder} or the default {@link ClientCookieDecoder#STRICT}
	 */
	public ClientCookieDecoder cookieDecoder() {
		return cookieDecoder;
	}

	/**
	 * Return the configured {@link ClientCookieEncoder} or the default {@link ClientCookieEncoder#STRICT}.
	 *
	 * @return the configured {@link ClientCookieEncoder} or the default {@link ClientCookieEncoder#STRICT}
	 */
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
	 * Return the HTTP/2 configuration
	 *
	 * @return the HTTP/2 configuration
	 */
	public Http2SettingsSpec http2SettingsSpec() {
		return http2Settings;
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
	 * Return true if {@code retry once} is disabled, false otherwise.
	 *
	 * @return true if {@code retry once} is disabled, false otherwise
	 */
	public boolean isRetryDisabled() {
		return retryDisabled;
	}

	/**
	 * Returns true if that {@link HttpClient} secured via SSL transport
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
	 * Return the configured redirect request consumer or null.
	 *
	 * @return the configured redirect request consumer or null
	 */
	@Nullable
	public Consumer<HttpClientRequest> redirectRequestConsumer() {
		return redirectRequestConsumer;
	}

	/**
	 * Return the configured response timeout or null
	 *
	 * @return the configured response timeout or null
	 */
	@Nullable
	public Duration responseTimeout() {
		return responseTimeout;
	}

	/**
	 * Returns the current {@link SslProvider} if that {@link HttpClient} secured via SSL
	 * transport or null
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
	 * that will be used for the metrics with {@link reactor.netty.Metrics#URI} tag
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
	HttpMethod method;
	HttpProtocol[] protocols;
	int _protocols;
	Consumer<HttpClientRequest> redirectRequestConsumer;
	Duration responseTimeout;
	boolean retryDisabled;
	SslProvider sslProvider;
	URI uri;
	String uriStr;
	Function<String, String> uriTagValue;
	WebsocketClientSpec websocketClientSpec;

	HttpClientConfig(ConnectionProvider connectionProvider, Map<ChannelOption<?>, ?> options,
			Supplier<? extends SocketAddress> remoteAddress) {
		super(connectionProvider, options, remoteAddress);
		this.acceptGzip = false;
		this.cookieDecoder = ClientCookieDecoder.STRICT;
		this.cookieEncoder = ClientCookieEncoder.STRICT;
		this.decoder = new HttpResponseDecoderSpec();
		this.headers = new DefaultHttpHeaders();
		this.method = HttpMethod.GET;
		this.protocols = new HttpProtocol[]{HttpProtocol.HTTP11};
		this._protocols = h11;
		this.retryDisabled = false;
	}

	HttpClientConfig(HttpClientConfig parent) {
		super(parent);
		this.acceptGzip = parent.acceptGzip;
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
		this.method = parent.method;
		this.protocols = parent.protocols;
		this._protocols = parent._protocols;
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
		            .then(new HttpClientChannelInitializer(acceptGzip, decoder, http2Settings(),
		                metricsRecorder(), channelOperationsProvider(), _protocols, sslProvider, uriTagValue));
	}

	@Override
	protected AddressResolverGroup<?> defaultResolver() {
		return DEFAULT_RESOLVER;
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

	static void configureHttp2Pipeline(ChannelPipeline p, HttpResponseDecoderSpec decoder, Http2Settings http2Settings,
			ConnectionObserver observer) {
		Http2FrameCodecBuilder http2FrameCodecBuilder =
				Http2FrameCodecBuilder.forClient()
				                      .validateHeaders(decoder.validateHeaders())
				                      .initialSettings(http2Settings);

		if (p.get(NettyPipeline.LoggingHandler) != null) {
			http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG,
					"reactor.netty.http.client.h2"));
		}

		p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpCodec, http2FrameCodecBuilder.build())
		 .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.H2MultiplexHandler, new Http2MultiplexHandler(new H2Codec()))
		 .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpTrafficHandler, new HttpTrafficHandler(observer));
	}

	static void configureHttp11OrH2CleartextPipeline(
			ChannelPipeline p,
			boolean acceptGzip,
			HttpResponseDecoderSpec decoder,
			Http2Settings http2Settings,
			@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder,
			ConnectionObserver observer,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Function<String, String> uriTagValue) {
		HttpClientCodec httpClientCodec =
				new HttpClientCodec(
						decoder.maxInitialLineLength(),
						decoder.maxHeaderSize(),
						decoder.maxChunkSize(),
						decoder.failOnMissingResponse,
						decoder.validateHeaders(),
						decoder.initialBufferSize(),
						decoder.parseHttpAfterConnectRequest);

		Http2FrameCodecBuilder http2FrameCodecBuilder =
				Http2FrameCodecBuilder.forClient()
						.validateHeaders(decoder.validateHeaders())
						.initialSettings(http2Settings);

		if (p.get(NettyPipeline.LoggingHandler) != null) {
			http2FrameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.DEBUG,
					"reactor.netty.http.client.h2"));
		}

		Http2FrameCodec http2FrameCodec = http2FrameCodecBuilder.build();

		Http2ClientUpgradeCodec upgradeCodec = new Http2ClientUpgradeCodec(http2FrameCodec, new H2CleartextCodec(http2FrameCodec, opsFactory));

		HttpClientUpgradeHandler upgradeHandler = new HttpClientUpgradeHandler(httpClientCodec, upgradeCodec, decoder.h2cMaxContentLength());

		p.addBefore(NettyPipeline.ReactiveBridge, null, httpClientCodec)
		 .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.H2CUpgradeHandler, upgradeHandler)
		 .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpTrafficHandler, new HttpTrafficHandler(observer));

		if (acceptGzip) {
			p.addAfter(NettyPipeline.HttpCodec, NettyPipeline.HttpDecompressor, new HttpContentDecompressor());
		}

		if (metricsRecorder != null) {
			ChannelMetricsRecorder channelMetricsRecorder = metricsRecorder.get();
			if (channelMetricsRecorder instanceof HttpClientMetricsRecorder) {
				p.addBefore(NettyPipeline.ReactiveBridge,
						NettyPipeline.HttpMetricsHandler,
						new HttpClientMetricsHandler((HttpClientMetricsRecorder) channelMetricsRecorder, uriTagValue));
			}
		}

	}

	static void configureHttp11Pipeline(ChannelPipeline p,
			boolean acceptGzip,
			HttpResponseDecoderSpec decoder,
			@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder,
			@Nullable Function<String, String> uriTagValue) {
		p.addBefore(NettyPipeline.ReactiveBridge,
				NettyPipeline.HttpCodec,
				new HttpClientCodec(
						decoder.maxInitialLineLength(),
						decoder.maxHeaderSize(),
						decoder.maxChunkSize(),
						decoder.failOnMissingResponse,
						decoder.validateHeaders(),
						decoder.initialBufferSize(),
						decoder.parseHttpAfterConnectRequest));

		if (acceptGzip) {
			p.addAfter(NettyPipeline.HttpCodec, NettyPipeline.HttpDecompressor, new HttpContentDecompressor());
		}

		if (metricsRecorder != null) {
			ChannelMetricsRecorder channelMetricsRecorder = metricsRecorder.get();
			if (channelMetricsRecorder instanceof HttpClientMetricsRecorder) {
				p.addBefore(NettyPipeline.ReactiveBridge,
						NettyPipeline.HttpMetricsHandler,
						new HttpClientMetricsHandler((HttpClientMetricsRecorder) channelMetricsRecorder, uriTagValue));
			}
		}
	}

	static Future<Http2StreamChannel> openStream(Channel channel, ConnectionObserver observer, ChannelOperations.OnSetup opsFactory) {
		Http2StreamChannelBootstrap bootstrap = new Http2StreamChannelBootstrap(channel);
		bootstrap.option(ChannelOption.AUTO_READ, false);
		bootstrap.handler(new H2Codec(observer, opsFactory));
		return bootstrap.open();
	}

	static final AddressResolverGroup<?> DEFAULT_RESOLVER =
			NameResolverProvider.builder().build().newNameResolverGroup(HttpResources.get());

	static final Pattern FOLLOW_REDIRECT_CODES = Pattern.compile("30[1278]");

	static final BiPredicate<HttpClientRequest, HttpClientResponse> FOLLOW_REDIRECT_PREDICATE =
			(req, res) -> FOLLOW_REDIRECT_CODES.matcher(res.status()
			                                               .codeAsText())
			                                   .matches();

	static final int h2 = 0b010;

	static final int h2c = 0b001;

	static final int h11 = 0b100;

	static final int h11orH2 = h11 | h2;

	static final int h11orH2C = h11 | h2c;

	static final Logger log = Loggers.getLogger(HttpClientConfig.class);

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(HttpClient.class);

	/**
	 * Default value whether the SSL debugging on the client side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	static final boolean SSL_DEBUG = Boolean.parseBoolean(System.getProperty(ReactorNetty.SSL_CLIENT_DEBUG, "false"));

	static final class H2CleartextCodec extends ChannelHandlerAdapter {

		final Http2FrameCodec http2FrameCodec;
		final ChannelOperations.OnSetup opsFactory;

		H2CleartextCodec(Http2FrameCodec http2FrameCodec, ChannelOperations.OnSetup opsFactory) {
			this.http2FrameCodec = http2FrameCodec;
			this.opsFactory = opsFactory;
		}

		@Override
		public void handlerAdded(ChannelHandlerContext ctx) {
			ChannelPipeline pipeline = ctx.pipeline();
			pipeline.addAfter(ctx.name(), NettyPipeline.HttpCodec, http2FrameCodec)
			        .addAfter(NettyPipeline.HttpCodec, NettyPipeline.H2MultiplexHandler,
			                new Http2MultiplexHandler(new H2Codec(opsFactory), new H2Codec(opsFactory)));
			if (pipeline.get(NettyPipeline.HttpDecompressor) != null) {
				pipeline.remove(NettyPipeline.HttpDecompressor);
			}
			pipeline.remove(NettyPipeline.ReactiveBridge);
			pipeline.remove(this);
		}
	}

	static final class H2Codec extends ChannelInitializer<Channel> {
		final ConnectionObserver observer;
		final ChannelOperations.OnSetup opsFactory;

		H2Codec() {
			this(null, null);
		}

		H2Codec(@Nullable ChannelOperations.OnSetup opsFactory) {
			this(null, opsFactory);
		}

		H2Codec(@Nullable ConnectionObserver observer, @Nullable ChannelOperations.OnSetup opsFactory) {
			this.observer = observer;
			this.opsFactory = opsFactory;
		}

		@Override
		protected void initChannel(Channel ch) {
			ConnectionObserver obs = this.observer;
			if (obs == null) {
				ConnectionObserver owner = ch.parent().attr(OWNER).get();
				if (owner instanceof Http2ConnectionProvider.DisposableAcquire) {
					obs = ((Http2ConnectionProvider.DisposableAcquire) owner).obs;
				}
			}
			if (obs != null && opsFactory != null) {
				addStreamHandlers(ch, obs, opsFactory);
			}
			else {
				// TODO handle server pushes
			}
		}

		static void addStreamHandlers(Channel ch, ConnectionObserver obs, ChannelOperations.OnSetup opsFactory) {
			ch.pipeline()
			  .addLast(NettyPipeline.H2ToHttp11Codec, new Http2StreamFrameToHttpObjectCodec(false))
			  .addLast(NettyPipeline.HttpTrafficHandler, new Http2StreamBridgeClientHandler(obs, opsFactory));

			ChannelOperations.addReactiveBridge(ch, opsFactory, obs);

			if (log.isDebugEnabled()) {
				log.debug(format(ch, "Initialized HTTP/2 stream pipeline {}"), ch.pipeline());
			}
		}
	}

	static final class H2OrHttp11Codec extends ChannelInboundHandlerAdapter {
		final boolean                                    acceptGzip;
		final HttpResponseDecoderSpec                    decoder;
		final Http2Settings                              http2Settings;
		final Supplier<? extends ChannelMetricsRecorder> metricsRecorder;
		final ConnectionObserver                         observer;
		final Function<String, String>                   uriTagValue;

		H2OrHttp11Codec(
				boolean acceptGzip,
				HttpResponseDecoderSpec decoder,
				Http2Settings http2Settings,
				@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder,
				ConnectionObserver observer,
				@Nullable Function<String, String> uriTagValue) {
			this.acceptGzip = acceptGzip;
			this.decoder = decoder;
			this.http2Settings = http2Settings;
			this.metricsRecorder = metricsRecorder;
			this.observer = observer;
			this.uriTagValue = uriTagValue;
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
			if (sslHandler == null) {
				throw new IllegalStateException("Cannot determine negotiated application-level protocol.");
			}
			String protocol = sslHandler.applicationProtocol() != null ? sslHandler.applicationProtocol() : ApplicationProtocolNames.HTTP_1_1;
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(), "Negotiated application-level protocol [" + protocol + "]"));
			}
			if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
				configureHttp2Pipeline(ctx.channel().pipeline(), decoder, http2Settings, observer);
			}
			else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				configureHttp11Pipeline(ctx.channel().pipeline(), acceptGzip, decoder, metricsRecorder, uriTagValue);
			}
			else {
				throw new IllegalStateException("unknown protocol: " + protocol);
			}

			ctx.fireChannelActive();

			ctx.channel().pipeline().remove(this);
		}
	}

	static final class HttpClientChannelInitializer implements ChannelPipelineConfigurer {

		final boolean                                    acceptGzip;
		final HttpResponseDecoderSpec                    decoder;
		final Http2Settings                              http2Settings;
		final Supplier<? extends ChannelMetricsRecorder> metricsRecorder;
		final ChannelOperations.OnSetup                  opsFactory;
		final int                                        protocols;
		final SslProvider                                sslProvider;
		final Function<String, String>                   uriTagValue;

		HttpClientChannelInitializer(
				boolean acceptGzip,
				HttpResponseDecoderSpec decoder,
				Http2Settings http2Settings,
				@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder,
				ChannelOperations.OnSetup opsFactory,
				int protocols,
				@Nullable SslProvider sslProvider,
				@Nullable Function<String, String> uriTagValue) {
			this.acceptGzip = acceptGzip;
			this.decoder = decoder;
			this.http2Settings = http2Settings;
			this.metricsRecorder = metricsRecorder;
			this.opsFactory = opsFactory;
			this.protocols = protocols;
			this.sslProvider = sslProvider;
			this.uriTagValue = uriTagValue;
		}

		@Override
		public void onChannelInit(ConnectionObserver observer, Channel channel, @Nullable SocketAddress remoteAddress) {
			if (sslProvider != null) {
				sslProvider.addSslHandler(channel, remoteAddress, SSL_DEBUG);

				if ((protocols & h11orH2) == h11orH2) {
					channel.pipeline()
					       .addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.H2OrHttp11Codec,
					               new H2OrHttp11Codec(acceptGzip, decoder, http2Settings, metricsRecorder, observer, uriTagValue));
				}
				else if ((protocols & h11) == h11) {
					configureHttp11Pipeline(channel.pipeline(), acceptGzip, decoder, metricsRecorder, uriTagValue);
				}
				else if ((protocols & h2) == h2) {
					configureHttp2Pipeline(channel.pipeline(), decoder, http2Settings, observer);
				}
			}
			else {
				if ((protocols & h11orH2C) == h11orH2C) {
					configureHttp11OrH2CleartextPipeline(channel.pipeline(), acceptGzip, decoder, http2Settings, metricsRecorder, observer, opsFactory, uriTagValue);
				}
				else if ((protocols & h11) == h11) {
					configureHttp11Pipeline(channel.pipeline(), acceptGzip, decoder, metricsRecorder, uriTagValue);
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
			if (doOnResponseError != null && (ops.responseState != null) && !(error instanceof RedirectClientException)) {
				doOnResponseError.accept(connection.as(HttpClientOperations.class), error);
			}
		}
	}
}
