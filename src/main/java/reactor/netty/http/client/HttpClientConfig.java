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
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.logging.LoggingHandler;
import io.netty.resolver.AddressResolverGroup;
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
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;
import reactor.netty.tcp.SslProvider;
import reactor.netty.transport.ClientTransportConfig;
import reactor.netty.transport.ProxyProvider;
import reactor.util.annotation.Nullable;

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
		return Objects.hash(super.channelHash(), acceptGzip, decoder, protocols, sslProvider);
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
	public boolean isSecure(){
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
	public int protocols() {
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
		return uri;
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
	BiConsumer<? super HttpClientRequest, ? super Connection>  doAfterRequest;
	BiConsumer<? super HttpClientResponse, ? super Connection> doAfterResponseSuccess;
	BiConsumer<? super HttpClientResponse, ? super Connection> doOnRedirect;
	BiConsumer<? super HttpClientRequest, ? super Connection> doOnRequest;
	BiConsumer<? super HttpClientRequest, ? super Throwable>  doOnRequestError;
	BiConsumer<? super HttpClientResponse, ? super Connection> doOnResponse;
	BiConsumer<? super HttpClientResponse, ? super Throwable> doOnResponseError;
	BiPredicate<HttpClientRequest, HttpClientResponse> followRedirectPredicate;
	HttpHeaders headers;
	HttpMethod method;
	int protocols;
	Consumer<HttpClientRequest> redirectRequestConsumer;
	boolean retryDisabled;
	SslProvider sslProvider;
	String uri;
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
		this.protocols = h11;
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
		this.method = parent.method;
		this.protocols = parent.protocols;
		this.redirectRequestConsumer = parent.redirectRequestConsumer;
		this.retryDisabled = parent.retryDisabled;
		this.sslProvider = parent.sslProvider;
		this.uri = parent.uri;
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
	@SuppressWarnings("unchecked")
	protected ChannelPipelineConfigurer defaultOnChannelInit() {
		return super.defaultOnChannelInit()
		            .then(new HttpClientChannelInitializer(acceptGzip, decoder, metricsRecorder(), protocols, sslProvider, uriTagValue));
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
		if (deferredConf != null){
			deferredConf = deferredConf.andThen(deferredConf -> deferredConf.flatMap(deferrer));
		}
		else {
			deferredConf = deferredConf -> deferredConf.flatMap(deferrer);
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
			p.addAfter(NettyPipeline.HttpCodec,
					NettyPipeline.HttpDecompressor,
					new HttpContentDecompressor());
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

	static int protocols(HttpProtocol... protocols) {
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

		return _protocols;
	}

	static final Pattern FOLLOW_REDIRECT_CODES = Pattern.compile("30[1278]");

	static final BiPredicate<HttpClientRequest, HttpClientResponse> FOLLOW_REDIRECT_PREDICATE =
			(req, res) -> FOLLOW_REDIRECT_CODES.matcher(res.status()
			                                               .codeAsText())
			                                   .matches();

	static final int h11 = 0b100;

	static final int h2 = 0b010;

	static final int h2c = 0b001;

	static final int h11orH2c = h11 | h2c;

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(HttpClient.class);

	/**
	 * Default value whether the SSL debugging on the client side will be enabled/disabled,
	 * fallback to SSL debugging disabled
	 */
	static final boolean SSL_DEBUG = Boolean.parseBoolean(System.getProperty(ReactorNetty.SSL_CLIENT_DEBUG, "false"));

	static final class HttpClientChannelInitializer implements ChannelPipelineConfigurer {

		final boolean                                    acceptGzip;
		final HttpResponseDecoderSpec                    decoder;
		final Supplier<? extends ChannelMetricsRecorder> metricsRecorder;
		final int                                        protocols;
		final SslProvider                                sslProvider;
		final Function<String, String>                   uriTagValue;

		HttpClientChannelInitializer(
				boolean acceptGzip,
				HttpResponseDecoderSpec decoder,
				@Nullable Supplier<? extends ChannelMetricsRecorder> metricsRecorder,
				int protocols,
				@Nullable SslProvider sslProvider,
				@Nullable Function<String, String> uriTagValue) {
			this.acceptGzip = acceptGzip;
			this.decoder = decoder;
			this.metricsRecorder = metricsRecorder;
			this.protocols = protocols;
			this.sslProvider = sslProvider;
			this.uriTagValue = uriTagValue;
		}

		@Override
		public void onChannelInit(ConnectionObserver observer, Channel channel, @Nullable SocketAddress remoteAddress) {
			if (sslProvider != null) {
				sslProvider.addSslHandler(channel, remoteAddress, SSL_DEBUG);
			}

			if ((protocols & h11) == h11) {
				configureHttp11Pipeline(channel.pipeline(), acceptGzip, decoder, metricsRecorder, uriTagValue);
			}
		}
	}

	static final class HttpClientDoOn implements ConnectionObserver {

		final BiConsumer<? super HttpClientRequest, ? super Connection>  doAfterRequest;
		final BiConsumer<? super HttpClientResponse, ? super Connection> doAfterResponseSuccess;
		final BiConsumer<? super HttpClientResponse, ? super Connection> doOnRedirect;
		final BiConsumer<? super HttpClientRequest, ? super Connection> doOnRequest;
		final BiConsumer<? super HttpClientRequest, ? super Throwable>  doOnRequestError;
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
