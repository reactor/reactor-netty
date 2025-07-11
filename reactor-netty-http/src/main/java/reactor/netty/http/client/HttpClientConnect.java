/*
 * Copyright (c) 2017-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import io.netty.handler.ssl.SslClosedEngineException;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyOutbound;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.HttpProtocol;
import reactor.netty.tcp.TcpClientConfig;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.ProxyProvider;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;
import reactor.util.retry.Retry;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.client.HttpClientOperations.H2;
import static reactor.netty.http.client.HttpClientState.STREAM_CONFIGURED;

/**
 * Provides the actual {@link HttpClient} instance.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
class HttpClientConnect extends HttpClient {

	final HttpClientConfig config;

	HttpClientConnect(HttpConnectionProvider provider) {
		this.config = new HttpClientConfig(
				provider,
				Collections.singletonMap(ChannelOption.AUTO_READ, false),
				() -> AddressUtils.createUnresolved(NetUtil.LOCALHOST.getHostAddress(), DEFAULT_PORT));
	}

	HttpClientConnect(HttpClientConfig config) {
		this.config = config;
	}

	@Override
	public HttpClientConfig configuration() {
		return config;
	}

	@Override
	public String toString() {
		return "HttpClient{" +
				"protocols=" + Arrays.asList(configuration().protocols) +
				", secure=" + configuration().isSecure() +
				'}';
	}

	@Override
	protected Mono<? extends Connection> connect() {
		HttpClientConfig config = configuration();

		Mono<? extends Connection> mono;
		if (config.deferredConf != null) {
			mono = config.deferredConf.apply(Mono.just(config))
			           .flatMap(MonoHttpConnect::new);
		}
		else {
			mono = new MonoHttpConnect(config);
		}

		Consumer<? super HttpClientConfig> doOnConnect = config.doOnConnect();
		if (doOnConnect != null) {
			mono = mono.doOnSubscribe(s -> doOnConnect.accept(config));
		}

		BiConsumer<? super HttpClientRequest, ? super Throwable> doOnRequestError = config.doOnRequestError;
		if (doOnRequestError != null) {
			mono = mono.onErrorResume(error ->
					Mono.deferContextual(Mono::just)
					    .doOnNext(ctx -> doOnRequestError.accept(new FailedHttpClientRequest(ctx, config), error))
					    .then(Mono.error(error)));
		}

		if (config.connector != null) {
			mono = config.connector.apply(mono);
		}
		return mono;
	}

	@Override
	protected HttpClient duplicate() {
		return new HttpClientConnect(new HttpClientConfig(config));
	}

	@SuppressWarnings("unchecked")
	static HttpClient applyTcpClientConfig(TcpClientConfig config) {
		HttpClient httpClient =
				create(config.connectionProvider()).doOnChannelInit(config.doOnChannelInit())
				                                   .observe(config.connectionObserver())
				                                   .remoteAddress(config.remoteAddress())
				                                   .runOn(config.loopResources(), config.isPreferNative());

		if (config.resolver() != null) {
			httpClient = httpClient.resolver(config.resolver());
		}

		for (Map.Entry<AttributeKey<?>, ?> entry : config.attributes().entrySet()) {
			httpClient = httpClient.attr((AttributeKey<Object>) entry.getKey(), entry.getValue());
		}

		if (config.bindAddress() != null) {
			httpClient = httpClient.bindAddress(config.bindAddress());
		}

		if (config.channelGroup() != null) {
			httpClient = httpClient.channelGroup(config.channelGroup());
		}

		if (config.doOnConnected() != null) {
			httpClient = httpClient.doOnConnected(config.doOnConnected());
		}

		if (config.doOnDisconnected() != null) {
			httpClient = httpClient.doOnDisconnected(config.doOnDisconnected());
		}

		if (config.loggingHandler() != null) {
			httpClient.configuration().loggingHandler(config.loggingHandler());
		}

		if (config.metricsRecorder() != null) {
			httpClient = httpClient.metrics(true, config.metricsRecorder());
		}

		for (Map.Entry<ChannelOption<?>, ?> entry : config.options().entrySet()) {
			httpClient = httpClient.option((ChannelOption<Object>) entry.getKey(), entry.getValue());
		}

		ProxyProvider proxyProvider = config.proxyProvider();
		if (proxyProvider != null) {
			httpClient.configuration().proxyProvider(proxyProvider);
		}

		Supplier<ProxyProvider> proxyProviderSupplier = config.proxyProviderSupplier();
		if (proxyProviderSupplier != null) {
			httpClient.configuration().proxyProviderSupplier(proxyProviderSupplier);
		}

		if (config.sslProvider() != null) {
			httpClient = httpClient.secure(config.sslProvider());
		}

		return httpClient;
	}

	static final class MonoHttpConnect extends Mono<Connection> {

		final HttpClientConfig config;

		MonoHttpConnect(HttpClientConfig config) {
			this.config = config;
		}

		@Override
		public void subscribe(CoreSubscriber<? super Connection> actual) {
			HttpClientHandler handler = new HttpClientHandler(config);

			Mono.<Connection>create(sink -> {
				boolean configCopied = false;
				HttpClientConfig _config = config;

				//append a secure handler if needed
				if (handler.toURI.isSecure()) {
					if (_config.sslProvider == null) {
						configCopied = true;
						_config = new HttpClientConfig(config);
						_config.sslProvider = HttpClientSecure.defaultSslProvider(_config);
					}

					if (_config.checkProtocol(HttpClientConfig.h2c)) {
						if (_config.protocols.length > 1) {
							removeIncompatibleProtocol(_config, HttpProtocol.H2C);
						}
						else {
							sink.error(new IllegalArgumentException(
									"Configured H2 Clear-Text protocol with TLS. " +
											"Use the non Clear-Text H2 protocol via HttpClient#protocol or disable TLS " +
											"via HttpClient#noSSL()"));
							return;
						}
					}
				}
				else {
					if (_config.sslProvider != null) {
						configCopied = true;
						_config = new HttpClientConfig(config);
						_config.sslProvider = null;
					}

					if (_config.checkProtocol(HttpClientConfig.h2)) {
						if (_config.protocols.length > 1) {
							removeIncompatibleProtocol(_config, HttpProtocol.H2);
						}
						else {
							sink.error(new IllegalArgumentException(
									"Configured H2 protocol without TLS. Use H2 Clear-Text " +
											"protocol via HttpClient#protocol or configure TLS via HttpClient#secure"));
							return;
						}
					}
					else if (_config.checkProtocol(HttpClientConfig.h3)) {
						sink.error(new IllegalArgumentException("Configured HTTP/3 protocol without TLS. Check URL scheme"));
						return;
					}
				}

				if (_config.proxyProvider() == null) {
					Supplier<ProxyProvider> proxyProviderSupplier = _config.proxyProviderSupplier();
					if (proxyProviderSupplier != null) {
						if (!configCopied) {
							configCopied = true;
							_config = new HttpClientConfig(config);
						}
						ProxyProvider proxyProvider = proxyProviderSupplier.get();
						_config.proxyProvider(proxyProvider);
						handler.proxyProvider = proxyProvider;
					}
				}

				ConnectionObserver observer =
						new HttpObserver(sink, handler)
						        .then(_config.defaultConnectionObserver())
						        .then(_config.connectionObserver())
						        .then(new HttpIOHandlerObserver(sink, handler));

				AddressResolverGroup<?> resolver = _config.resolverInternal();

				_config.httpConnectionProvider()
						.acquire(_config, observer, handler, resolver)
						.subscribe(new ClientTransportSubscriber(sink));

			}).retryWhen(Retry.indefinitely().filter(handler))
			  .subscribe(actual);
		}

		private static void removeIncompatibleProtocol(HttpClientConfig config, HttpProtocol protocol) {
			List<HttpProtocol> newProtocols = new ArrayList<>();
			for (int i = 0; i < config.protocols.length; i++) {
				if (config.protocols[i] != protocol) {
					newProtocols.add(config.protocols[i]);
				}
			}
			config.protocols(newProtocols.toArray(new HttpProtocol[0]));
		}

		static final class ClientTransportSubscriber implements CoreSubscriber<Connection> {

			final MonoSink<Connection> sink;
			final Context currentContext;

			ClientTransportSubscriber(MonoSink<Connection> sink) {
				this.sink = sink;
				this.currentContext = Context.of(sink.contextView());
			}

			@Override
			public void onSubscribe(Subscription s) {
				s.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(Connection connection) {
				sink.onCancel(connection);
			}

			@Override
			public void onError(Throwable throwable) {
				sink.error(throwable);
			}

			@Override
			public void onComplete() {
			}

			@Override
			public Context currentContext() {
				return currentContext;
			}
		}
	}

	static final class HttpObserver implements ConnectionObserver {

		final MonoSink<Connection> sink;
		final Context currentContext;
		final HttpClientHandler handler;

		HttpObserver(MonoSink<Connection> sink, HttpClientHandler handler) {
			this.sink = sink;
			this.currentContext = Context.of(sink.contextView());
			this.handler = handler;
		}

		@Override
		public Context currentContext() {
			return currentContext;
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			if (error instanceof RedirectClientException) {
				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "The request will be redirected"));
				}
				HttpClientOperations ops = connection.as(HttpClientOperations.class);
				if (ops != null && handler.redirectRequestBiConsumer != null) {
					handler.previousRequestHeaders = ops.requestHeaders;
				}
			}
			else if (handler.shouldRetry && AbortedException.isConnectionReset(error)) {
				HttpClientOperations ops = connection.as(HttpClientOperations.class);
				if (ops != null && ops.hasSentHeaders()) {
					// In some cases the channel close event may be delayed and thus the connection to be
					// returned to the pool and later the eviction functionality to remove it from the pool.
					// In some rare cases the connection might be acquired immediately, before the channel close
					// event and the eviction functionality is able to remove it from the pool; this may lead to I/O
					// errors.
					// Mark the connection as non-persistent here so that it is never returned to the pool and leave
					// the channel close event to invalidate it.
					ops.markPersistent(false);
					// Disable retry if the headers or/and the body were sent
					handler.shouldRetry = false;
					if (log.isWarnEnabled()) {
						log.warn(format(connection.channel(),
								"The connection observed an error, the request cannot be " +
										"retried as the headers/body were sent"), error);
					}
				}
				else {
					if (ops != null) {
						// In some cases the channel close event may be delayed and thus the connection to be
						// returned to the pool and later the eviction functionality to remove it from the pool.
						// In some rare cases the connection might be acquired immediately, before the channel close
						// event and the eviction functionality is able to remove it from the pool; this may lead to I/O
						// errors.
						// Mark the connection as non-persistent here so that it is never returned to the pool and leave
						// the channel close event to invalidate it.
						ops.markPersistent(false);
						ops.retrying = true;
					}
					if (log.isDebugEnabled()) {
						log.debug(format(connection.channel(),
								"The connection observed an error, the request will be retried"), error);
					}
				}
			}
			else if (error instanceof SslClosedEngineException) {
				if (log.isWarnEnabled()) {
					log.warn(format(connection.channel(), "The connection observed an error"), error);
				}
				HttpClientOperations ops = connection.as(HttpClientOperations.class);
				if (ops != null) {
					// javax.net.ssl.SSLEngine has been closed, do not return the connection to the pool
					ops.markPersistent(false);
				}
			}
			else if (log.isWarnEnabled()) {
				log.warn(format(connection.channel(), "The connection observed an error"), error);
			}
			sink.error(error);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if ((newState == State.CONFIGURED || newState == STREAM_CONFIGURED) &&
						HttpClientOperations.class == connection.getClass()) {
				handler.channel((HttpClientOperations) connection);
			}
		}
	}

	static final class HttpIOHandlerObserver implements ConnectionObserver {

		final MonoSink<Connection> sink;
		final Context currentContext;
		final HttpClientHandler handler;

		HttpIOHandlerObserver(MonoSink<Connection> sink, HttpClientHandler handler) {
			this.sink = sink;
			this.currentContext = Context.of(sink.contextView());
			this.handler = handler;
		}

		@Override
		public Context currentContext() {
			return currentContext;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == HttpClientState.RESPONSE_RECEIVED) {
				sink.success(connection);
				return;
			}
			if ((newState == State.CONFIGURED || newState == STREAM_CONFIGURED) &&
						HttpClientOperations.class == connection.getClass()) {
				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "Handler is being applied: {}"), handler);
				}

				Mono.defer(() -> Mono.fromDirect(handler.requestWithBody((HttpClientOperations) connection)))
				    .subscribe(connection.disposeSubscriber());
			}
		}
	}

	static final class HttpClientHandler extends SocketAddress
			implements Predicate<Throwable>, Supplier<SocketAddress> {
		static final Long FALSE = 0L;

		volatile HttpMethod                     method;
		final HttpHeaders                       defaultHeaders;
		final @Nullable BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>>
		                                        handler;
		final UriEndpointFactory                uriEndpointFactory;
		final @Nullable WebsocketClientSpec     websocketClientSpec;
		final @Nullable BiPredicate<HttpClientRequest, HttpClientResponse>
		                                        followRedirectPredicate;
		final @Nullable BiConsumer<HttpHeaders, HttpClientRequest>
		                                        redirectRequestBiConsumer;
		final @Nullable Consumer<HttpClientRequest>
		                                        redirectRequestConsumer;
		final HttpResponseDecoderSpec           decoder;
		final @Nullable Duration                responseTimeout;

		@Nullable ProxyProvider                 proxyProvider;

		volatile UriEndpoint                    toURI;
		volatile String                         resourceUrl;
		volatile @Nullable UriEndpoint          fromURI;
		volatile Supplier<String> @Nullable []  redirectedFrom;
		volatile boolean                        shouldRetry;
		volatile @Nullable HttpHeaders          previousRequestHeaders;

		HttpClientHandler(HttpClientConfig configuration) {
			this.method = configuration.method;
			this.followRedirectPredicate = configuration.followRedirectPredicate;
			this.redirectRequestBiConsumer = configuration.redirectRequestBiConsumer;
			this.redirectRequestConsumer = configuration.redirectRequestConsumer;
			this.decoder = configuration.decoder;
			this.proxyProvider = configuration.proxyProvider();
			this.responseTimeout = configuration.responseTimeout;
			this.defaultHeaders = configuration.headers;

			String baseUrl = configuration.baseUrl;

			this.uriEndpointFactory =
					new UriEndpointFactory(configuration.remoteAddress(), configuration.isSecure(), URI_ADDRESS_MAPPER);

			this.websocketClientSpec = configuration.websocketClientSpec;
			this.shouldRetry = !configuration.retryDisabled;
			this.handler = configuration.body;

			if (configuration.uri == null) {
				String uri = configuration.uriStr;

				uri = uri == null ? "/" : uri;

				if (baseUrl != null && uri.startsWith("/")) {
					if (baseUrl.endsWith("/")) {
						baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
					}
					uri = baseUrl + uri;
				}

				this.fromURI = this.toURI = uriEndpointFactory.createUriEndpoint(uri, configuration.websocketClientSpec != null);
			}
			else {
				this.fromURI = this.toURI = uriEndpointFactory.createUriEndpoint(configuration.uri, configuration.websocketClientSpec != null);
			}
			this.resourceUrl = toURI.toExternalForm();
		}

		@Override
		public SocketAddress get() {
			SocketAddress address = toURI.getRemoteAddress();
			if (proxyProvider != null && !proxyProvider.shouldProxy(address) && address instanceof InetSocketAddress) {
				address = AddressUtils.replaceWithResolved((InetSocketAddress) address);
			}

			return address;
		}

		@SuppressWarnings("ReferenceEquality")
		Publisher<Void> requestWithBody(HttpClientOperations ch) {
			try {
				ch.resourceUrl = this.resourceUrl;
				ch.responseTimeout = responseTimeout;

				UriEndpoint uri = toURI;
				HttpHeaders headers = ch.getNettyRequest()
				                        .setUri(uri.getPathAndQuery())
				                        .setMethod(method)
				                        .setProtocolVersion(HttpVersion.HTTP_1_1)
				                        .headers();

				// Reset to pickup the actual uri()
				ch.path = null;

				if (!defaultHeaders.isEmpty()) {
					headers.set(defaultHeaders);
				}

				if (!headers.contains(HttpHeaderNames.USER_AGENT)) {
					headers.set(HttpHeaderNames.USER_AGENT, USER_AGENT);
				}

				SocketAddress remoteAddress = uri.getRemoteAddress();
				if (!headers.contains(HttpHeaderNames.HOST)) {
					headers.set(HttpHeaderNames.HOST, resolveHostHeaderValue(remoteAddress));
				}

				if (!headers.contains(HttpHeaderNames.ACCEPT)) {
					headers.set(HttpHeaderNames.ACCEPT, ALL);
				}

				ch.followRedirectPredicate(followRedirectPredicate);

				if (!Objects.equals(method, HttpMethod.GET) &&
						!Objects.equals(method, HttpMethod.HEAD) &&
						!Objects.equals(method, HttpMethod.DELETE) &&
						!headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
					ch.chunkedTransfer(true);
				}

				ch.listener().onStateChange(ch, HttpClientState.REQUEST_PREPARED);
				if (websocketClientSpec != null) {
					// ReferenceEquality is deliberate
					if (ch.version == H2) {
						Long value = ch.channel().parent().attr(ENABLE_CONNECT_PROTOCOL).get();
						if (value == null) {
							throw new WebSocketClientHandshakeException("Websocket is not supported by the server. " +
									"Missing SETTINGS_ENABLE_CONNECT_PROTOCOL(0x8).");
						}
						if (FALSE.equals(value)) {
							throw new WebSocketClientHandshakeException("Websocket is not supported by the server. " +
									"[SETTINGS_ENABLE_CONNECT_PROTOCOL(0x8)=0] was received.");
						}
					}
					Mono<Void> result =
							Mono.fromRunnable(() -> ch.withWebsocketSupport(websocketClientSpec));
					if (handler != null) {
						result = result.thenEmpty(Mono.fromRunnable(() -> Flux.concat(handler.apply(ch, ch))));
					}
					return result;
				}

				Consumer<HttpClientRequest> consumer = null;
				if (fromURI != null && !toURI.equals(fromURI)) {
					if (handler instanceof RedirectSendHandler) {
						headers.remove(HttpHeaderNames.EXPECT)
						       .remove(HttpHeaderNames.COOKIE)
						       .remove(HttpHeaderNames.AUTHORIZATION)
						       .remove(HttpHeaderNames.PROXY_AUTHORIZATION);
					}
					else {
						consumer = request ->
						        request.requestHeaders()
						               .remove(HttpHeaderNames.EXPECT)
						               .remove(HttpHeaderNames.COOKIE)
						               .remove(HttpHeaderNames.AUTHORIZATION)
						               .remove(HttpHeaderNames.PROXY_AUTHORIZATION);
					}
				}
				if (this.redirectRequestConsumer != null) {
					consumer = consumer != null ? consumer.andThen(this.redirectRequestConsumer) : this.redirectRequestConsumer;
				}

				if (redirectRequestBiConsumer != null) {
					ch.previousRequestHeaders = previousRequestHeaders;
					ch.redirectRequestBiConsumer = redirectRequestBiConsumer;
				}

				ch.redirectRequestConsumer(consumer);
				if (handler != null) {
					Publisher<Void> publisher = handler.apply(ch, ch);
					return ch.equals(publisher) ? ch.send() : publisher;
				}
				else {
					return ch.send();
				}
			}
			catch (Throwable t) {
				return Mono.error(t);
			}
		}

		static String resolveHostHeaderValue(@Nullable SocketAddress remoteAddress) {
			if (remoteAddress instanceof InetSocketAddress) {
				InetSocketAddress address = (InetSocketAddress) remoteAddress;
				String host = HttpUtil.formatHostnameForHttp(address);
				int port = address.getPort();
				if (port != 80 && port != 443) {
					host = host + ':' + port;
				}
				return host;
			}
			else {
				return "localhost";
			}
		}

		void redirect(String to) {
			Supplier<String>[] redirectedFrom = this.redirectedFrom;
			UriEndpoint toURITemp;
			UriEndpoint from = toURI;
			SocketAddress address = from.getRemoteAddress();
			if (address instanceof InetSocketAddress) {
				try {
					URI redirectUri = new URI(to);
					if (!redirectUri.isAbsolute()) {
						URI requestUri = new URI(resourceUrl);
						redirectUri = requestUri.resolve(redirectUri);
					}
					toURITemp = uriEndpointFactory.createUriEndpoint(redirectUri, from.isWs());
				}
				catch (URISyntaxException e) {
					throw new IllegalArgumentException("Cannot resolve location header", e);
				}
			}
			else {
				toURITemp = UriEndpointFactory.createUriEndpoint(from, to, () -> address);
			}
			toURI = toURITemp;
			resourceUrl = toURITemp.toExternalForm();
			this.redirectedFrom = addToRedirectedFromArray(redirectedFrom, from);
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		static Supplier<String>[] addToRedirectedFromArray(Supplier<String> @Nullable [] redirectedFrom, UriEndpoint from) {
			Supplier<String> fromUrlSupplier = from::toExternalForm;
			if (redirectedFrom == null) {
				return new Supplier[]{fromUrlSupplier};
			}
			else {
				Supplier<String>[] newRedirectedFrom = new Supplier[redirectedFrom.length + 1];
				System.arraycopy(redirectedFrom,
						0,
						newRedirectedFrom,
						0,
						redirectedFrom.length);
				newRedirectedFrom[redirectedFrom.length] = fromUrlSupplier;
				return newRedirectedFrom;
			}
		}

		void channel(HttpClientOperations ops) {
			Supplier<String>[] redirectedFrom = this.redirectedFrom;
			if (redirectedFrom != null) {
				ops.redirectedFrom = redirectedFrom;
			}
		}

		@Override
		public boolean test(Throwable throwable) {
			if (throwable instanceof RedirectClientException) {
				RedirectClientException re = (RedirectClientException) throwable;
				if (HttpResponseStatus.SEE_OTHER.equals(re.status)) {
					method = HttpMethod.GET;
				}
				redirect(re.location);
				return true;
			}
			if (shouldRetry && AbortedException.isConnectionReset(throwable)) {
				shouldRetry = false;
				redirect(toURI.toString());
				return true;
			}
			return false;
		}

		@Override
		public String toString() {
			return "{" + "uri=" + toURI + ", method=" + method + '}';
		}
	}

	static final AsciiString ALL = new AsciiString("*/*");

	static final AttributeKey<@Nullable Long> ENABLE_CONNECT_PROTOCOL = AttributeKey.valueOf("$ENABLE_CONNECT_PROTOCOL");

	static final Logger log = Loggers.getLogger(HttpClientConnect.class);

	static final BiFunction<String, Integer, InetSocketAddress> URI_ADDRESS_MAPPER = AddressUtils::createUnresolved;

	/**
	 * The default port for reactor-netty HTTP clients. Defaults to 80 but can be tuned via
	 * the {@code PORT} <b>environment variable</b>.
	 */
	static final int DEFAULT_PORT;
	static {
		int port;
		String portStr = null;
		try {
			portStr = System.getenv("PORT");
			port = portStr != null ? Integer.parseInt(portStr) : 80;
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid environment variable [PORT=" + portStr + "].", e);
		}
		DEFAULT_PORT = port;
	}
}
