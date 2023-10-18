/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.ByteBufMono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyOutbound;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.http.Http2SettingsSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.netty.http.logging.ReactorNettyHttpMessageLogFactory;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.netty.internal.util.Metrics;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpClient;
import reactor.netty.transport.ClientTransport;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

/**
 * An HttpClient allows building in a safe immutable way an http client that is
 * materialized and connecting when {@link HttpClient#connect()} is ultimately called.
 * {@code Transfer-Encoding: chunked} will be applied for those HTTP methods for which
 * a request body is expected. {@code Content-Length} provided via request headers
 * will disable {@code Transfer-Encoding: chunked}.
 * <p>
 * <p> Examples:
 * <pre>
 * {@code
 * HttpClient.create()
 *           .baseUrl("https://example.com")
 *           .get()
 *           .response()
 *           .block();
 * }
 * {@code
 * HttpClient.create()
 *           .post()
 *           .uri("https://example.com")
 *           .send(Flux.just(bb1, bb2, bb3))
 *           .responseSingle((res, content) -> Mono.just(res.status().code()))
 *           .block();
 * }
 * {@code
 * HttpClient.create()
 *           .baseUri("https://example.com")
 *           .post()
 *           .send(ByteBufFlux.fromString(flux))
 *           .responseSingle((res, content) -> Mono.just(res.status().code()))
 *           .block();
 * }
 * </pre>
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public abstract class HttpClient extends ClientTransport<HttpClient, HttpClientConfig> {

	public static final String USER_AGENT = String.format("ReactorNetty/%s", reactorNettyVersion());

	/**
	 * A URI configuration
	 */
	public interface UriConfiguration<S extends UriConfiguration<?>> {

		/**
		 * Configure URI to use for this request/response
		 *
		 * @param uri target URI, if starting with "/" it will be prepended with
		 * {@link #baseUrl(String)} when available
		 *
		 * @return the appropriate sending or receiving contract
		 */
		S uri(String uri);

		/**
		 * Configure URI to use for this request/response on subscribe
		 *
		 * @param uri target URI, if starting with "/" it will be prepended with
		 * {@link #baseUrl(String)} when available
		 *
		 * @return the appropriate sending or receiving contract
		 */
		S uri(Mono<String> uri);

		/**
		 * Configure URI to use for this request/response.
		 * <p>Note: {@link #baseUrl(String)} will have no effect when this method is used for configuring an URI.
		 *
		 * @param uri target URI which is an absolute, fully constructed {@link URI}
		 * @return the appropriate sending or receiving contract
		 */
		S uri(URI uri);
	}

	/**
	 * Allow a request body configuration before calling one of the terminal, {@link
	 * Publisher} based, {@link ResponseReceiver} API.
	 */
	public interface RequestSender extends ResponseReceiver<RequestSender> {

		/**
		 * Configure a body to send on request.
		 *
		 * <p><strong>Note:</strong> The body {@code Publisher} passed in will be invoked also for redirect requests
		 * when {@code followRedirect} is enabled. If you need to control what will be sent when
		 * {@code followRedirect} is enabled then use {@link #send(BiFunction)}.
		 * <p><strong>Note:</strong> For redirect requests, sensitive headers
		 * {@link #followRedirect(boolean, Consumer) followRedirect} are removed
		 * from the initialized request when redirecting to a different domain, they can be re-added globally via
		 * {@link #followRedirect(boolean, Consumer)}/{@link #followRedirect(BiPredicate, Consumer)}
		 * or alternatively for full control per redirect request, consider using {@link RedirectSendHandler}
		 * with {@link #send(BiFunction)}
		 *
		 * @param body a body publisher that will terminate the request on complete
		 *
		 * @return a new {@link ResponseReceiver}
		 */
		ResponseReceiver<?> send(Publisher<? extends ByteBuf> body);

		/**
		 * Configure a body to send on request using the {@link NettyOutbound} sending
		 * builder and returning a {@link Publisher} to signal end of the request.
		 *
		 * <p><strong>Note:</strong> The sender {@code BiFunction} passed in will be invoked also for redirect requests
		 * when {@code followRedirect} is enabled. For redirect requests, sensitive headers
		 * {@link #followRedirect(boolean, Consumer) followRedirect} are removed
		 * from the initialized request when redirecting to a different domain, they can be re-added globally via
		 * {@link #followRedirect(boolean, Consumer)}/{@link #followRedirect(BiPredicate, Consumer)}
		 * or alternatively for full control per redirect request, consider using {@link RedirectSendHandler}.
		 * {@link RedirectSendHandler} can be used to indicate explicitly that this {@code BiFunction} has special
		 * handling for redirect requests.
		 *
		 * @param sender a bifunction given the outgoing request and the sending
		 * {@link NettyOutbound}, returns a publisher that will terminate the request
		 * body on complete
		 *
		 * @return a new {@link ResponseReceiver}
		 */
		ResponseReceiver<?> send(BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>> sender);

		/**
		 * Prepare to send an HTTP Form including Multipart encoded Form which support
		 * chunked file upload. It will by default be encoded as Multipart but can be
		 * adapted via {@link HttpClientForm#multipart(boolean)}.
		 *
		 * <p><strong>Note:</strong> The HTTP Form passed in will be invoked also for redirect requests
		 * when {@code followRedirect} is enabled. If you need to control what will be sent when
		 * {@code followRedirect} is enabled use {@link HttpClientRequest#redirectedFrom()} to check the original
		 * and any number of subsequent redirect(s), including the one that is in progress.
		 * <p><strong>Note:</strong> For redirect requests, sensitive headers
		 * {@link #followRedirect(boolean, Consumer) followRedirect} are removed
		 * from the initialized request when redirecting to a different domain, they can be re-added globally via
		 * {@link #followRedirect(boolean, Consumer)}/{@link #followRedirect(BiPredicate, Consumer)}.
		 *
		 * @param formCallback called when form generator is created
		 *
		 * @return a new {@link ResponseReceiver}
		 */
		default ResponseReceiver<?> sendForm(BiConsumer<? super HttpClientRequest, HttpClientForm> formCallback) {
			return sendForm(formCallback, null);
		}

		/**
		 * Prepare to send an HTTP Form including Multipart encoded Form which support
		 * chunked file upload. It will by default be encoded as Multipart but can be
		 * adapted via {@link HttpClientForm#multipart(boolean)}.
		 *
		 * <p><strong>Note:</strong> The HTTP Form passed in will be invoked also for redirect requests
		 * when {@code followRedirect} is enabled. If you need to control what will be sent when
		 * {@code followRedirect} is enabled use {@link HttpClientRequest#redirectedFrom()} to check the original
		 * and any number of subsequent redirect(s), including the one that is in progress.
		 * <p><strong>Note:</strong> For redirect requests, sensitive headers
		 * {@link #followRedirect(boolean, Consumer) followRedirect} are removed
		 * from the initialized request when redirecting to a different domain, they can be re-added globally via
		 * {@link #followRedirect(boolean, Consumer)}/{@link #followRedirect(BiPredicate, Consumer)}.
		 *
		 * @param formCallback called when form generator is created
		 * @param progress called after form is being sent and passed with a {@link Flux} of the latest in-flight or uploaded bytes
		 *
		 * @return a new {@link ResponseReceiver}
		 */
		ResponseReceiver<?> sendForm(BiConsumer<? super HttpClientRequest, HttpClientForm> formCallback,
				@Nullable Consumer<Flux<Long>> progress);
	}

	/**
	 * Allow a request body configuration before calling one of the terminal, {@link
	 * Publisher} based, {@link WebsocketReceiver} API.
	 */
	public interface WebsocketSender extends WebsocketReceiver<WebsocketSender> {

		/**
		 * Configure headers to send on request using the returned {@link Publisher} to
		 * signal end of the request.
		 *
		 * @param sender a bifunction given the outgoing request returns a publisher
		 * that will terminate the request body on complete
		 *
		 * @return a new {@link ResponseReceiver}
		 */
		WebsocketReceiver<?> send(Function<? super HttpClientRequest, ? extends Publisher<Void>> sender);
	}

	/**
	 * A response extractor for this configured {@link HttpClient}. Since
	 * {@link ResponseReceiver} API returns {@link Flux} or {@link Mono},
	 * requesting is always deferred to {@link Publisher#subscribe(Subscriber)}.
	 */
	public interface ResponseReceiver<S extends ResponseReceiver<?>>
			extends UriConfiguration<S> {

		/**
		 * Return the response status and headers as {@link HttpClientResponse}
		 * <p> Will automatically close the response if necessary.
		 * <p>Note: Will automatically close low-level network connection after returned
		 * {@link Mono} terminates or is being cancelled.
		 *
		 * @return the response status and headers as {@link HttpClientResponse}
		 */
		Mono<HttpClientResponse> response();

		/**
		 * Extract a response flux from the given {@link HttpClientResponse} and body
		 * {@link ByteBufFlux}.
		 * <p> Will automatically close the response if necessary after the returned
		 * {@link Flux} terminates or is being cancelled.
		 *
		 * @param receiver extracting receiver
		 * @param <V> the extracted flux type
		 *
		 * @return a {@link Flux} forwarding the returned {@link Publisher} sequence
		 */
		<V> Flux<V> response(BiFunction<? super HttpClientResponse, ? super ByteBufFlux, ? extends Publisher<V>> receiver);

		/**
		 * Extract a response flux from the given {@link HttpClientResponse} and
		 * underlying {@link Connection}.
		 * <p> The connection will not automatically {@link Connection#dispose()} and
		 * manual interaction with this method might be necessary if the remote never
		 * terminates itself.
		 *
		 * @param receiver extracting receiver
		 * @param <V> the extracted flux type
		 *
		 * @return a {@link Flux} forwarding the returned {@link Publisher} sequence
		 */
		<V> Flux<V> responseConnection(BiFunction<? super HttpClientResponse, ? super Connection, ? extends Publisher<V>> receiver);

		/**
		 * Return the response body chunks as {@link ByteBufFlux}.
		 *
		 * <p> Will automatically close the response if necessary after the returned
		 * {@link Flux} terminates or is being cancelled.
		 *
		 * @return the response body chunks as {@link ByteBufFlux}.
		 */
		ByteBufFlux responseContent();

		/**
		 * Extract a response mono from the given {@link HttpClientResponse} and
		 * aggregated body {@link ByteBufMono}.
		 *
		 * <p> Will automatically close the response if necessary after the returned
		 * {@link Mono} terminates or is being cancelled.
		 *
		 * @param receiver extracting receiver
		 * @param <V> the extracted mono type
		 *
		 * @return a {@link Mono} forwarding the returned {@link Mono} result
		 */
		<V> Mono<V> responseSingle(BiFunction<? super HttpClientResponse, ? super ByteBufMono, ? extends Mono<V>> receiver);
	}

	/**
	 * Allow a websocket handling. Since {@link WebsocketReceiver} API returns
	 * {@link Flux} or {@link Mono}, requesting is always deferred to
	 * {@link Publisher#subscribe(Subscriber)}.
	 */
	public interface WebsocketReceiver<S extends WebsocketReceiver<?>> extends UriConfiguration<S> {
		/**
		 * Negotiate a websocket upgrade and return a {@link Mono} of {@link Connection}. If
		 * {@link Mono} is cancelled, the underlying connection will be aborted. Once the
		 * {@link Connection} has been emitted and is not necessary anymore, disposing must be
		 * done by the user via {@link Connection#dispose()}.
		 *
		 * If update configuration phase fails, a {@link Mono#error(Throwable)} will be returned
		 *
		 * @return a {@link Mono} of {@link Connection}
		 */
		Mono<? extends Connection> connect();

		/**
		 * Negotiate a websocket upgrade and extract a flux from the given
		 * {@link WebsocketInbound} and {@link WebsocketOutbound}.
		 * <p> The connection will not automatically {@link Connection#dispose()} and
		 * manual disposing with the {@link Connection},
		 * {@link WebsocketOutbound#sendClose}
		 * or the returned {@link Flux} might be necessary if the remote never
		 * terminates itself.
		 * <p> If the upgrade fails, the returned {@link Flux} will emit a
		 * {@link io.netty.handler.codec.http.websocketx.WebSocketHandshakeException}
		 *
		 * @param receiver extracting receiver
		 * @param <V> the extracted flux type
		 *
		 * @return a {@link Flux} of the extracted data via the returned {@link Publisher}
		 */
		<V> Flux<V> handle(BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<V>> receiver);

		/**
		 * Negotiate a websocket upgrade and extract a flux from the underlying
		 * {@link WebsocketInbound}.
		 * <p> The connection will be disposed when the underlying subscriber is
		 * disposed OR when a close frame has been received, forwarding onComplete to
		 * the returned flux subscription.
		 * <p> If the upgrade fails, the returned {@link Flux} will emit a
		 * {@link io.netty.handler.codec.http.websocketx.WebSocketHandshakeException}
		 *
		 * @return a {@link ByteBufFlux} of the inbound websocket content
		 */
		ByteBufFlux receive();
	}

	/**
	 * Marker interface for use with {@link RequestSender#send(BiFunction)}.
	 * <p>When the {@code BiFunction} passed into {@code send} is an implementation of this
	 * interface, it indicates it differentiates between original and redirect requests,
	 * e.g. as a result of enabling {@link #followRedirect(boolean)}, and is capable of
	 * applying separate logic for each individually. Redirect scenarios may be detected
	 * by checking {@link HttpClientRequest#redirectedFrom()}.
	 * <p>When the {@code BiFunction} passed in is not an implementation of this interface,
	 * it indicates it does not differentiate between original and redirect requests, and
	 * applies the same initialization logic.
	 * @since 0.9.5
	 */
	public interface RedirectSendHandler extends BiFunction<HttpClientRequest, NettyOutbound, Publisher<Void>> {
	}

	/**
	 * Prepare a pooled {@link HttpClient}. {@link UriConfiguration#uri(String)} or
	 * {@link #baseUrl(String)} should be invoked before a verb
	 * {@link #request(HttpMethod)} is selected.
	 *
	 * @return a {@link HttpClient}
	 */
	public static HttpClient create() {
		return new HttpClientConnect(new HttpConnectionProvider());
	}

	/**
	 * Prepare an {@link HttpClient}. {@link UriConfiguration#uri(String)} or
	 * {@link #baseUrl(String)} should be invoked before a verb
	 * {@link #request(HttpMethod)} is selected.
	 *
	 * @param connectionProvider the {@link ConnectionProvider} to be used
	 * @return a {@link HttpClient}
	 */
	public static HttpClient create(ConnectionProvider connectionProvider) {
		Objects.requireNonNull(connectionProvider, "connectionProvider");
		return new HttpClientConnect(new HttpConnectionProvider(connectionProvider));
	}

	/**
	 * Prepare an {@link HttpClient}
	 * <p>
	 * <strong>Note:</strong>
	 * There isn't only one method that replaces this deprecated method.
	 * The configuration that can be done with this deprecated method,
	 * can also be done with the other methods exposed by {@link HttpClient}.
	 * </p>
	 * <p>Examples:</p>
	 * <p>Configuration via the deprecated '.from(...)' method</p>
	 * <pre>
	 * {@code
	 * HttpClient.from(
	 *     TcpClient.attr(...) // configures the channel attributes
	 *              .bindAddress(...) // configures the bind (local) address
	 *              .channelGroup(...) // configures the channel group
	 *              .doOnChannelInit(...) // configures the channel handler
	 *              .doOnConnected(...) // configures the doOnConnected callback
	 *              .doOnDisconnected(...) // configures the doOnDisconnected callback
	 *              .metrics(...) // configures the metrics
	 *              .observe() // configures the connection observer
	 *              .option(...) // configures the channel options
	 *              .proxy(...) // configures the proxy
	 *              .remoteAddress(...) // configures the remote address
	 *              .resolver(...) // configures the host names resolver
	 *              .runOn(...) // configures the event loop group
	 *              .secure() // configures the SSL
	 *              .wiretap()) // configures the wire logging
	 * }
	 * </pre>
	 *
	 * <p>Configuration via the other methods exposed by {@link HttpClient}</p>
	 * <pre>
	 * {@code
	 * HttpClient.attr(...) // configures the channel attributes
	 *           .bindAddress(...) // configures the bind (local) address
	 *           .channelGroup(...) // configures the channel group
	 *           .doOnChannelInit(...) // configures the channel handler
	 *           .doOnConnected(...) // configures the doOnConnected callback
	 *           .doOnDisconnected(...) // configures the doOnDisconnected callback
	 *           .metrics(...) // configures the metrics
	 *           .observe() // configures the connection observer
	 *           .option(...) // configures the channel options
	 *           .proxy(...) // configures the proxy
	 *           .remoteAddress(...) // configures the remote address
	 *           .resolver(...) // configures the host names resolver
	 *           .runOn(...) // configures the event loop group
	 *           .secure() // configures the SSL
	 *           .wiretap() // configures the wire logging
	 * }
	 * </pre>
	 *
	 * <p>Wire logging in plain text</p>
	 * <pre>
	 * {@code
	 * HttpClient.wiretap("logger", LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL)
	 * }
	 * </pre>
	 *
	 * @return a new {@link HttpClient}
	 * @deprecated Use the other methods exposed by {@link HttpClient} to achieve the same configurations.
	 * This method will be removed in version 1.1.0.
	 */
	@Deprecated
	public static HttpClient from(TcpClient tcpClient) {
		Objects.requireNonNull(tcpClient, "tcpClient");
		return HttpClientConnect.applyTcpClientConfig(tcpClient.configuration());
	}

	/**
	 * Prepare an unpooled {@link HttpClient}. {@link UriConfiguration#uri(String)} or
	 * {@link #baseUrl(String)} should be invoked after a verb
	 * {@link #request(HttpMethod)} is selected.
	 *
	 * @return a {@link HttpClient}
	 */
	public static HttpClient newConnection() {
		return new HttpClientConnect(new HttpConnectionProvider(ConnectionProvider.newConnection()));
	}

	/**
	 * Configure URI to use for this request/response.
	 * <p>Note: Configured {@code baseUrl} only applies when used with {@link UriConfiguration#uri(String)}
	 * or {@link UriConfiguration#uri(Mono)}.
	 *
	 * @param baseUrl a default base url that can be fully sufficient for request or can
	 * be used to prepend future {@link UriConfiguration#uri} calls.
	 *
	 * @return the appropriate sending or receiving contract
	 */
	public final HttpClient baseUrl(String baseUrl) {
		Objects.requireNonNull(baseUrl, "baseUrl");
		HttpClient dup = duplicate();
		dup.configuration().baseUrl = baseUrl;
		return dup;
	}

	/**
	 * Specifies whether GZip compression is enabled.
	 *
	 * @param compressionEnabled if true GZip compression is enabled otherwise disabled (default: false)
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient compress(boolean compressionEnabled) {
		if (compressionEnabled) {
			if (!configuration().acceptGzip) {
				HttpClient dup = duplicate();
				HttpHeaders headers = configuration().headers.copy();
				headers.add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
				dup.configuration().headers = headers;
				dup.configuration().acceptGzip = true;
				return dup;
			}
		}
		else if (configuration().acceptGzip) {
			HttpClient dup = duplicate();
			if (isCompressing(configuration().headers)) {
				HttpHeaders headers = configuration().headers.copy();
				headers.remove(HttpHeaderNames.ACCEPT_ENCODING);
				dup.configuration().headers = headers;
			}
			dup.configuration().acceptGzip = false;
			return dup;
		}
		return this;
	}

	/**
	 * Apply cookies configuration.
	 *
	 * @param cookie a cookie to append to the request(s)
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient cookie(Cookie cookie) {
		Objects.requireNonNull(cookie, "cookie");
		if (!cookie.value().isEmpty()) {
			HttpClient dup = duplicate();
			HttpHeaders headers = configuration().headers.copy();
			headers.add(HttpHeaderNames.COOKIE, dup.configuration().cookieEncoder.encode(cookie));
			dup.configuration().headers = headers;
			return dup;
		}
		return this;
	}

	/**
	 * Apply cookies configuration.
	 *
	 * @param cookieBuilder the header {@link Consumer} to invoke before requesting
	 *
	 * @return a new {@link HttpClient}
	 * @deprecated as of 1.1.0. Use {@link #cookie(Cookie)} for configuring cookies. This will be removed in 2.0.0.
	 */
	@Deprecated
	public final HttpClient cookie(String name, Consumer<? super Cookie> cookieBuilder) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(cookieBuilder, "cookieBuilder");
		Cookie cookie = new DefaultCookie(name, "");
		cookieBuilder.accept(cookie);
		return cookie(cookie);
	}

	/**
	 * Configure the
	 * {@link ClientCookieEncoder}, {@link ClientCookieDecoder} will be
	 * chosen based on the encoder
	 *
	 * @param encoder the preferred ClientCookieEncoder
	 *
	 * @return a new {@link HttpClient}
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 supports only strict validation.
	 */
	@Deprecated
	public final HttpClient cookieCodec(ClientCookieEncoder encoder) {
		Objects.requireNonNull(encoder, "encoder");
		ClientCookieDecoder decoder = encoder == ClientCookieEncoder.LAX ?
				ClientCookieDecoder.LAX : ClientCookieDecoder.STRICT;
		return cookieCodec(encoder, decoder);
	}

	/**
	 * Configure the
	 * {@link ClientCookieEncoder} and {@link ClientCookieDecoder}
	 *
	 * @param encoder the preferred ClientCookieEncoder
	 * @param decoder the preferred ClientCookieDecoder
	 *
	 * @return a new {@link HttpClient}
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 supports only strict validation.
	 */
	@Deprecated
	public final HttpClient cookieCodec(ClientCookieEncoder encoder, ClientCookieDecoder decoder) {
		Objects.requireNonNull(encoder, "encoder");
		Objects.requireNonNull(decoder, "decoder");
		HttpClient dup = duplicate();
		dup.configuration().cookieEncoder = encoder;
		dup.configuration().cookieDecoder = decoder;
		return dup;
	}

	/**
	 * Apply cookies configuration emitted by the returned Mono before requesting.
	 *
	 * @param cookieBuilder the cookies {@link Function} to invoke before sending
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient cookiesWhen(String name, Function<? super Cookie, Mono<? extends Cookie>> cookieBuilder) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(cookieBuilder, "cookieBuilder");
		HttpClient dup = duplicate();
		dup.configuration().deferredConf(config ->
				cookieBuilder.apply(new DefaultCookie(name, ""))
				             .map(c -> {
				                 if (!c.value().isEmpty()) {
				                     HttpHeaders headers = configuration().headers.copy();
				                     headers.add(HttpHeaderNames.COOKIE, config.cookieEncoder.encode(c));
				                     config.headers = headers;
				                 }
				                 return config;
				             }));
		return dup;
	}

	/**
	 * HTTP DELETE to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to prepare the content for response
	 */
	public final RequestSender delete() {
		return request(HttpMethod.DELETE);
	}

	/**
	 * Option to disable {@code retry once} support for the outgoing requests that fail with
	 * {@link reactor.netty.channel.AbortedException#isConnectionReset(Throwable)}.
	 * <p>By default this is set to false in which case {@code retry once} is enabled.
	 *
	 * @param disableRetry true to disable {@code retry once}, false to enable it
	 *
	 * @return a new {@link HttpClient}
	 * @since 0.9.6
	 */
	public final HttpClient disableRetry(boolean disableRetry) {
		if (disableRetry == configuration().retryDisabled) {
			return this;
		}
		HttpClient dup = duplicate();
		dup.configuration().retryDisabled = disableRetry;
		return dup;
	}

	/**
	 * Setup a callback called when {@link HttpClientRequest} has been sent
	 * and {@link HttpClientState#REQUEST_SENT} has been emitted.
	 *
	 * @param doAfterRequest a callback called when {@link HttpClientRequest} has been sent
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doAfterRequest(BiConsumer<? super HttpClientRequest, ? super Connection> doAfterRequest) {
		Objects.requireNonNull(doAfterRequest, "doAfterRequest");
		HttpClient dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<HttpClientRequest, Connection> current =
				(BiConsumer<HttpClientRequest, Connection>) configuration().doAfterRequest;
		dup.configuration().doAfterRequest = current == null ? doAfterRequest : current.andThen(doAfterRequest);
		return dup;
	}

	/**
	 * Setup a callback called after {@link HttpClientResponse} has been fully received
	 * and {@link HttpClientState#RESPONSE_COMPLETED} has been emitted.
	 *
	 * @param doAfterResponseSuccess a callback called after {@link HttpClientResponse} has been fully received
	 * and {@link HttpClientState#RESPONSE_COMPLETED} has been emitted.
	 *
	 * @return a new {@link HttpClient}
	 * @since 0.9.5
	 */
	public final HttpClient doAfterResponseSuccess(BiConsumer<? super HttpClientResponse, ? super Connection> doAfterResponseSuccess) {
		Objects.requireNonNull(doAfterResponseSuccess, "doAfterResponseSuccess");
		HttpClient dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<HttpClientResponse, Connection> current =
				(BiConsumer<HttpClientResponse, Connection>) configuration().doAfterResponseSuccess;
		dup.configuration().doAfterResponseSuccess = current == null ? doAfterResponseSuccess : current.andThen(doAfterResponseSuccess);
		return dup;
	}

	/**
	 * Setup a callback called when {@link HttpClientRequest} has not been sent and when {@link HttpClientResponse} has not been fully
	 * received.
	 * <p>
	 * Note that some mutation of {@link HttpClientRequest} performed late in lifecycle
	 * {@link #doOnRequest(BiConsumer)} or {@link RequestSender#send(BiFunction)} might
	 * not be visible if the error results from a connection failure.
	 *
	 * @param doOnRequestError a consumer observing request failures
	 * @param doOnResponseError a consumer observing response failures
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnError(BiConsumer<? super HttpClientRequest, ? super Throwable> doOnRequestError,
			BiConsumer<? super HttpClientResponse, ? super Throwable> doOnResponseError) {
		Objects.requireNonNull(doOnRequestError, "doOnRequestError");
		Objects.requireNonNull(doOnResponseError, "doOnResponseError");
		HttpClient dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<HttpClientRequest, Throwable> currentRequestError =
				(BiConsumer<HttpClientRequest, Throwable>) configuration().doOnRequestError;
		dup.configuration().doOnRequestError =
				currentRequestError == null ? doOnRequestError : currentRequestError.andThen(doOnRequestError);
		@SuppressWarnings("unchecked")
		BiConsumer<HttpClientResponse, Throwable> currentResponseError =
				(BiConsumer<HttpClientResponse, Throwable>) configuration().doOnResponseError;
		dup.configuration().doOnResponseError =
				currentResponseError == null ? doOnResponseError : currentResponseError.andThen(doOnResponseError);
		return dup;
	}

	/**
	 * Setup a callback called after {@link HttpClientResponse} headers have been
	 * received and the request is about to be redirected.
	 *
	 * <p>Note: This callback applies only if auto-redirect is enabled, e.g. via
	 * {@link HttpClient#followRedirect(boolean)}.
	 *
	 * @param doOnRedirect a callback called after {@link HttpClientResponse} headers have been received
	 * and the request is about to be redirected
	 *
	 * @return a new {@link HttpClient}
	 * @since 0.9.6
	 */
	public final HttpClient doOnRedirect(BiConsumer<? super HttpClientResponse, ? super Connection> doOnRedirect) {
		Objects.requireNonNull(doOnRedirect, "doOnRedirect");
		HttpClient dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<HttpClientResponse, Connection> current =
				(BiConsumer<HttpClientResponse, Connection>) configuration().doOnRedirect;
		dup.configuration().doOnRedirect = current == null ? doOnRedirect : current.andThen(doOnRedirect);
		return dup;
	}

	/**
	 * Setup a callback called when {@link HttpClientRequest} is about to be sent
	 * and {@link HttpClientState#REQUEST_PREPARED} has been emitted.
	 *
	 * @param doOnRequest a callback called when {@link HttpClientRequest} is about to be sent
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnRequest(BiConsumer<? super HttpClientRequest, ? super Connection> doOnRequest) {
		Objects.requireNonNull(doOnRequest, "doOnRequest");
		HttpClient dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<HttpClientRequest, Connection> current =
				(BiConsumer<HttpClientRequest, Connection>) configuration().doOnRequest;
		dup.configuration().doOnRequest = current == null ? doOnRequest : current.andThen(doOnRequest);
		return dup;
	}

	/**
	 * Setup a callback called when {@link HttpClientRequest} has not been sent.
	 * Note that some mutation of {@link HttpClientRequest} performed late in lifecycle
	 * {@link #doOnRequest(BiConsumer)} or {@link RequestSender#send(BiFunction)} might
	 * not be visible if the error results from a connection failure.
	 *
	 * @param doOnRequestError a consumer observing request failures
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnRequestError(BiConsumer<? super HttpClientRequest, ? super Throwable> doOnRequestError) {
		Objects.requireNonNull(doOnRequestError, "doOnRequestError");
		HttpClient dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<HttpClientRequest, Throwable> current =
				(BiConsumer<HttpClientRequest, Throwable>) configuration().doOnRequestError;
		dup.configuration().doOnRequestError = current == null ? doOnRequestError : current.andThen(doOnRequestError);
		return dup;
	}

	/**
	 * Setup a callback called after {@link HttpClientResponse} headers have been
	 * received and {@link HttpClientState#RESPONSE_RECEIVED} has been emitted.
	 *
	 * @param doOnResponse a callback called after {@link HttpClientResponse} headers have been received
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnResponse(BiConsumer<? super HttpClientResponse, ? super Connection> doOnResponse) {
		Objects.requireNonNull(doOnResponse, "doOnResponse");
		HttpClient dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<HttpClientResponse, Connection> current =
				(BiConsumer<HttpClientResponse, Connection>) configuration().doOnResponse;
		dup.configuration().doOnResponse = current == null ? doOnResponse : current.andThen(doOnResponse);
		return dup;
	}

	/**
	 * Setup a callback called when {@link HttpClientResponse} has not been fully
	 * received, {@link HttpClientState#RESPONSE_INCOMPLETE} has been emitted.
	 *
	 * @param doOnResponseError a consumer observing response failures
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnResponseError(BiConsumer<? super HttpClientResponse, ? super Throwable> doOnResponseError) {
		Objects.requireNonNull(doOnResponseError, "doOnResponseError");
		HttpClient dup = duplicate();
		@SuppressWarnings("unchecked")
		BiConsumer<HttpClientResponse, Throwable> current =
				(BiConsumer<HttpClientResponse, Throwable>) configuration().doOnResponseError;
		dup.configuration().doOnResponseError = current == null ? doOnResponseError : current.andThen(doOnResponseError);
		return dup;
	}

	/**
	 * Enables auto-redirect support if the passed
	 * {@link java.util.function.Predicate} matches.
	 *
	 * <p><strong>Note:</strong> The passed {@link HttpClientRequest} and {@link HttpClientResponse}
	 * should be considered read-only and the implement SHOULD NOT consume or
	 * write the request/response in this predicate.
	 *
	 * <p><strong>Note:</strong> The sensitive headers {@link #followRedirect(BiPredicate, Consumer) followRedirect}
	 * are removed from the initialized request when redirecting to a different domain, they can be re-added
	 * via {@link #followRedirect(BiPredicate, Consumer)}.
	 *
	 * @param predicate that returns true to enable auto-redirect support.
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient followRedirect(BiPredicate<HttpClientRequest, HttpClientResponse> predicate) {
		return followRedirect(predicate, (Consumer<HttpClientRequest>) null);
	}

	/**
	 * Variant of {@link #followRedirect(BiPredicate)} that also accepts
	 * {@link BiConsumer} that provides the headers from the previous request and the current redirect request.
	 *
	 * <p><strong>Note:</strong> The sensitive headers:
	 * <ul>
	 *     <li>Expect</li>
	 *     <li>Cookie</li>
	 *     <li>Authorization</li>
	 *     <li>Proxy-Authorization</li>
	 * </ul>
	 * are removed from the initialized request when redirecting to a different domain,
	 * they can be re-added using the {@link BiConsumer}.
	 *
	 * @param predicate that returns true to enable auto-redirect support.
	 * @param redirectRequestBiConsumer {@link BiConsumer}, invoked on redirects, after
	 * the redirect request has been initialized, in order to apply further changes such as
	 * add/remove headers and cookies; use {@link HttpClientRequest#redirectedFrom()} to
	 * check the original and any number of subsequent redirect(s), including the one that
	 * is in progress. The {@link BiConsumer} provides the headers from the previous request
	 * and the current redirect request.
	 * @return a new {@link HttpClient}
	 * @since 0.9.12
	 */
	public final HttpClient followRedirect(BiPredicate<HttpClientRequest, HttpClientResponse> predicate,
			@Nullable BiConsumer<HttpHeaders, HttpClientRequest> redirectRequestBiConsumer) {
		Objects.requireNonNull(predicate, "predicate");
		HttpClient dup = duplicate();
		dup.configuration().followRedirectPredicate = predicate;
		dup.configuration().redirectRequestBiConsumer = redirectRequestBiConsumer;
		dup.configuration().redirectRequestConsumer = null;
		return dup;
	}

	/**
	 * Variant of {@link #followRedirect(BiPredicate)} that also accepts a redirect request
	 * processor.
	 *
	 * <p><strong>Note:</strong> The sensitive headers:
	 * <ul>
	 *     <li>Expect</li>
	 *     <li>Cookie</li>
	 *     <li>Authorization</li>
	 *     <li>Proxy-Authorization</li>
	 * </ul>
	 * are removed from the initialized request when redirecting to a different domain,
	 * they can be re-added using {@code redirectRequestConsumer}.
	 *
	 * @param predicate that returns true to enable auto-redirect support.
	 * @param redirectRequestConsumer redirect request consumer, invoked on redirects, after
	 * the redirect request has been initialized, in order to apply further changes such as
	 * add/remove headers and cookies; use {@link HttpClientRequest#redirectedFrom()} to
	 * check the original and any number of subsequent redirect(s), including the one that
	 * is in progress.
	 * @return a new {@link HttpClient}
	 * @since 0.9.5
	 */
	public final HttpClient followRedirect(BiPredicate<HttpClientRequest, HttpClientResponse> predicate,
			@Nullable Consumer<HttpClientRequest> redirectRequestConsumer) {
		Objects.requireNonNull(predicate, "predicate");
		HttpClient dup = duplicate();
		dup.configuration().followRedirectPredicate = predicate;
		dup.configuration().redirectRequestBiConsumer = null;
		dup.configuration().redirectRequestConsumer = redirectRequestConsumer;
		return dup;
	}

	/**
	 * Specifies whether HTTP status 301|302|303|307|308 auto-redirect support is enabled.
	 *
	 * <p><strong>Note:</strong> The sensitive headers {@link #followRedirect(boolean, Consumer) followRedirect}
	 * are removed from the initialized request when redirecting to a different domain, they can be re-added
	 * via {@link #followRedirect(boolean, Consumer)}.
	 *
	 * @param followRedirect if true HTTP status 301|302|307|308 auto-redirect support
	 * is enabled, otherwise disabled (default: false).
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient followRedirect(boolean followRedirect) {
		if (!followRedirect && configuration().followRedirectPredicate == null &&
					configuration().redirectRequestConsumer == null &&
					configuration().redirectRequestBiConsumer == null) {
			return this;
		}
		return followRedirect(followRedirect, (Consumer<HttpClientRequest>) null);
	}

	/**
	 * Variant of {@link #followRedirect(boolean)} that also accepts
	 * {@link BiConsumer} that provides the headers from the previous request and the current redirect request.
	 *
	 * <p><strong>Note:</strong> The sensitive headers:
	 * <ul>
	 *     <li>Expect</li>
	 *     <li>Cookie</li>
	 *     <li>Authorization</li>
	 *     <li>Proxy-Authorization</li>
	 * </ul>
	 * are removed from the initialized request when redirecting to a different domain,
	 * they can be re-added using the {@link BiConsumer}.
	 *
	 * @param followRedirect if true HTTP status 301|302|307|308 auto-redirect support
	 *                       is enabled, otherwise disabled (default: false).
	 * @param redirectRequestBiConsumer {@link BiConsumer}, invoked on redirects, after
	 * the redirect request has been initialized, in order to apply further changes such as
	 * add/remove headers and cookies; use {@link HttpClientRequest#redirectedFrom()} to
	 * check the original and any number of subsequent redirect(s), including the one that
	 * is in progress. The {@link BiConsumer} provides the headers from the previous request
	 * and the current redirect request.
	 * @return a new {@link HttpClient}
	 * @since 0.9.12
	 */
	public final HttpClient followRedirect(boolean followRedirect,
			@Nullable BiConsumer<HttpHeaders, HttpClientRequest> redirectRequestBiConsumer) {
		if (followRedirect) {
			return followRedirect(HttpClientConfig.FOLLOW_REDIRECT_PREDICATE, redirectRequestBiConsumer);
		}
		else {
			HttpClient dup = duplicate();
			dup.configuration().followRedirectPredicate = null;
			dup.configuration().redirectRequestBiConsumer = null;
			dup.configuration().redirectRequestConsumer = null;
			return dup;
		}
	}

	/**
	 * Variant of {@link #followRedirect(boolean)} that also accepts a redirect request
	 * processor.
	 *
	 * <p><strong>Note:</strong> The sensitive headers:
	 * <ul>
	 *     <li>Expect</li>
	 *     <li>Cookie</li>
	 *     <li>Authorization</li>
	 *     <li>Proxy-Authorization</li>
	 * </ul>
	 * are removed from the initialized request when redirecting to a different domain,
	 * they can be re-added using {@code redirectRequestConsumer}.
	 *
	 * @param followRedirect if true HTTP status 301|302|307|308 auto-redirect support
	 * is enabled, otherwise disabled (default: false).
	 * @param redirectRequestConsumer redirect request consumer, invoked on redirects, after
	 * the redirect request has been initialized, in order to apply further changes such as
	 * add/remove headers and cookies; use {@link HttpClientRequest#redirectedFrom()} to
	 * check the original and any number of subsequent redirect(s), including the one that
	 * is in progress.
	 * @return a new {@link HttpClient}
	 * @since 0.9.5
	 */
	public final HttpClient followRedirect(boolean followRedirect, @Nullable Consumer<HttpClientRequest> redirectRequestConsumer) {
		if (followRedirect) {
			return followRedirect(HttpClientConfig.FOLLOW_REDIRECT_PREDICATE, redirectRequestConsumer);
		}
		else {
			HttpClient dup = duplicate();
			dup.configuration().followRedirectPredicate = null;
			dup.configuration().redirectRequestBiConsumer = null;
			dup.configuration().redirectRequestConsumer = null;
			return dup;
		}
	}

	/**
	 * HTTP GET to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to consume for response
	 */
	public final ResponseReceiver<?> get() {
		return request(HttpMethod.GET);
	}

	/**
	 * HTTP HEAD to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to consume for response
	 */
	public final ResponseReceiver<?> head() {
		return request(HttpMethod.HEAD);
	}

	/**
	 * Apply headers configuration.
	 *
	 * @param headerBuilder the header {@link Consumer} to invoke before requesting
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient headers(Consumer<? super HttpHeaders> headerBuilder) {
		Objects.requireNonNull(headerBuilder, "headerBuilder");
		HttpClient dup = duplicate();
		HttpHeaders headers = configuration().headers.copy();
		headerBuilder.accept(headers);
		dup.configuration().headers = headers;
		return dup;
	}

	/**
	 * Apply headers configuration emitted by the returned Mono before requesting.
	 *
	 * @param headerBuilder the header {@link Function} to invoke before sending
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient headersWhen(Function<? super HttpHeaders, Mono<? extends HttpHeaders>> headerBuilder) {
		Objects.requireNonNull(headerBuilder, "headerBuilder");
		HttpClient dup = duplicate();
		dup.configuration().deferredConf(config ->
				headerBuilder.apply(config.headers.copy())
				             .map(h -> {
				                 config.headers = h;
				                 return config;
				             }));
		return dup;
	}

	/**
	 * Apply HTTP/2 configuration
	 *
	 * @param http2Settings configures {@link Http2SettingsSpec} before requesting
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient http2Settings(Consumer<Http2SettingsSpec.Builder> http2Settings) {
		Objects.requireNonNull(http2Settings, "http2Settings");
		Http2SettingsSpec.Builder builder = Http2SettingsSpec.builder();
		http2Settings.accept(builder);
		Http2SettingsSpec settings = builder.build();
		if (settings.equals(configuration().http2Settings)) {
			return this;
		}
		HttpClient dup = duplicate();
		dup.configuration().http2Settings = settings;
		return dup;
	}

	/**
	 * When {@link HttpMessage} is about to be logged the configured factory will be used for
	 * generating a sanitized log message.
	 * <p>
	 * Default to {@link ReactorNettyHttpMessageLogFactory}:
	 * <ul>
	 *     <li>hides the query from the uri</li>
	 *     <li>hides the headers values</li>
	 *     <li>only {@link DecoderException} message is presented</li>
	 * </ul>
	 *
	 * @param httpMessageLogFactory the factory for generating the log message
	 * @return a new {@link HttpClient}
	 * @since 1.0.24
	 */
	public final HttpClient httpMessageLogFactory(HttpMessageLogFactory httpMessageLogFactory) {
		Objects.requireNonNull(httpMessageLogFactory, "httpMessageLogFactory");
		HttpClient dup = duplicate();
		dup.configuration().httpMessageLogFactory = httpMessageLogFactory;
		return dup;
	}

	/**
	 * Configure the {@link io.netty.handler.codec.http.HttpClientCodec}'s response decoding options.
	 *
	 * @param responseDecoderOptions a function to mutate the provided Http response decoder options
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient httpResponseDecoder(Function<HttpResponseDecoderSpec, HttpResponseDecoderSpec> responseDecoderOptions) {
		Objects.requireNonNull(responseDecoderOptions, "responseDecoderOptions");
		HttpResponseDecoderSpec decoder = responseDecoderOptions.apply(new HttpResponseDecoderSpec()).build();
		if (decoder.equals(configuration().decoder)) {
			return this;
		}
		HttpClient dup = duplicate();
		dup.configuration().decoder = decoder;
		return dup;
	}

	/**
	 * Enable or Disable Keep-Alive support for the outgoing request.
	 *
	 * @param keepAlive true if keepAlive should be enabled (default: true)
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient keepAlive(boolean keepAlive) {
		HttpClient dup = duplicate();
		HttpHeaders headers = configuration().headers.copy();
		HttpUtil.setKeepAlive(headers, HttpVersion.HTTP_1_1, keepAlive);
		dup.configuration().headers = headers;
		return dup;
	}

	/**
	 * Intercept the connection lifecycle and allows delaying, transform or inject a
	 * context.
	 *
	 * @param connector A bi function mapping the default connection and configured
	 * bootstrap to a target connection.
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient mapConnect(
			Function<? super Mono<? extends Connection>, ? extends Mono<? extends Connection>> connector) {
		Objects.requireNonNull(connector, "mapConnect");
		HttpClient dup = duplicate();
		Function<? super Mono<? extends Connection>, ? extends Mono<? extends Connection>> currentConnector =
				configuration().connector;
		dup.configuration().connector = currentConnector == null ? connector : currentConnector.andThen(connector);
		return dup;
	}

	/**
	 * Whether to enable metrics to be collected and registered in Micrometer's
	 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}
	 * under the name {@link reactor.netty.Metrics#HTTP_CLIENT_PREFIX}.
	 * <p>{@code uriTagValue} function receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#URI} tag.
	 * For example instead of using the actual uri {@code "/users/1"} as uri tag value, templated uri
	 * {@code "/users/{id}"} can be used.
	 * <p><strong>Note:</strong>
	 * It is strongly recommended to provide template-like form for the URIs. Without a conversion to a template-like form,
	 * each distinct URI leads to the creation of a distinct tag, which takes a lot of memory for the metrics.
	 * <p><strong>Note:</strong>
	 * It is strongly recommended applications to configure an upper limit for the number of the URI tags.
	 * For example:
	 * <pre class="code">
	 * Metrics.globalRegistry
	 *        .config()
	 *        .meterFilter(MeterFilter.maximumAllowableTags(HTTP_CLIENT_PREFIX, URI, 100, MeterFilter.deny()));
	 * </pre>
	 * <p>By default metrics are not enabled.
	 *
	 * @param enable true enables metrics collection; false disables it
	 * @param uriTagValue a function that receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#URI} tag
	 * @return a new {@link HttpClient}
	 * @since 0.9.7
	 */
	public final HttpClient metrics(boolean enable, Function<String, String> uriTagValue) {
		if (enable) {
			if (!Metrics.isMicrometerAvailable() && !Metrics.isTracingAvailable()) {
				throw new UnsupportedOperationException(
						"To enable metrics, you must add the dependencies to `io.micrometer:micrometer-core`" +
								" and `io.micrometer:micrometer-tracing` to the class path first");
			}
			if (uriTagValue == Function.<String>identity()) {
				log.debug("Metrics are enabled with [uriTagValue=Function#identity]. " +
						"It is strongly recommended to provide template-like form for the URIs. " +
						"Without a conversion to a template-like form, each distinct URI leads " +
						"to the creation of a distinct tag, which takes a lot of memory for the metrics.");
			}
			HttpClient dup = duplicate();
			dup.configuration().metricsRecorder(() -> configuration().defaultMetricsRecorder());
			dup.configuration().uriTagValue = uriTagValue;
			return dup;
		}
		else if (configuration().metricsRecorder() != null) {
			HttpClient dup = duplicate();
			dup.configuration().metricsRecorder(null);
			dup.configuration().uriTagValue = null;
			return dup;
		}
		else {
			return this;
		}
	}

	@Override
	public final HttpClient metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder) {
		return super.metrics(enable, recorder);
	}

	/**
	 * Specifies whether the metrics are enabled on the {@link HttpClient}.
	 * All generated metrics are provided to the specified recorder which is only
	 * instantiated if metrics are being enabled (the instantiation is not lazy,
	 * but happens immediately, while configuring the {@link HttpClient}).
	 * <p>{@code uriValue} function receives the actual uri and returns the uri value
	 * that will be used when the metrics are propagated to the recorder.
	 * For example instead of using the actual uri {@code "/users/1"} as uri value, templated uri
	 * {@code "/users/{id}"} can be used.
	 *
	 * @param enable true enables metrics collection; false disables it
	 * @param recorder a supplier for the metrics recorder that receives the collected metrics
	 * @param uriValue a function that receives the actual uri and returns the uri value
	 * that will be used when the metrics are propagated to the recorder.
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder, Function<String, String> uriValue) {
		if (enable) {
			HttpClient dup = duplicate();
			dup.configuration().metricsRecorder(recorder);
			dup.configuration().uriTagValue = uriValue;
			return dup;
		}
		else if (configuration().metricsRecorder() != null) {
			HttpClient dup = duplicate();
			dup.configuration().metricsRecorder(null);
			dup.configuration().uriTagValue = null;
			return dup;
		}
		else {
			return this;
		}
	}

	/**
	 * Removes any previously applied SSL configuration customization
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient noSSL() {
		if (configuration().isSecure()) {
			HttpClient dup = duplicate();
			dup.configuration().sslProvider = null;
			return dup;
		}
		return this;
	}

	@Override
	public final HttpClient observe(ConnectionObserver observer) {
		return super.observe(observer);
	}

	/**
	 * HTTP OPTIONS to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to consume for response
	 */
	public final ResponseReceiver<?> options() {
		return request(HttpMethod.OPTIONS);
	}

	/**
	 * HTTP PATCH to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to finalize request and consume for response
	 */
	public final RequestSender patch() {
		return request(HttpMethod.PATCH);
	}

	@Override
	public final HttpClient port(int port) {
		return super.port(port);
	}

	/**
	 * HTTP POST to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to finalize request and consume for response
	 */
	public final RequestSender post() {
		return request(HttpMethod.POST);
	}

	/**
	 * The HTTP protocol to support. Default is {@link HttpProtocol#HTTP11}.
	 *
	 * @param supportedProtocols The various {@link HttpProtocol} this client will support
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient protocol(HttpProtocol... supportedProtocols) {
		Objects.requireNonNull(supportedProtocols, "supportedProtocols");
		HttpClient dup = duplicate();
		HttpClientConfig config = dup.configuration();
		config.protocols(supportedProtocols);

		boolean isH2c = config.checkProtocol(HttpClientConfig.h2c);
		if ((!isH2c || config._protocols > 1) && HttpClientSecure.hasDefaultSslProvider(config)) {
			dup.configuration().sslProvider = HttpClientSecure.defaultSslProvider(config);
		}
		return dup;
	}

	/**
	 * HTTP PUT to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to finalize request and consume for response
	 */
	public final RequestSender put() {
		return request(HttpMethod.PUT);
	}

	@Override
	public final HttpClient remoteAddress(Supplier<? extends SocketAddress> remoteAddressSupplier) {
		return super.remoteAddress(remoteAddressSupplier);
	}

	/**
	 * Use the passed HTTP method to connect the {@link HttpClient}.
	 *
	 * @param method the HTTP method to send
	 *
	 * @return a {@link RequestSender} ready to finalize request and consume for response
	 */
	public RequestSender request(HttpMethod method) {
		Objects.requireNonNull(method, "method");
		HttpClientFinalizer dup = new HttpClientFinalizer(new HttpClientConfig(configuration()));
		dup.configuration().method = method;
		return dup;
	}

	/**
	 * Specifies the maximum duration allowed between each network-level read operation while reading a given response
	 * (resolution: ms). In other words, {@link io.netty.handler.timeout.ReadTimeoutHandler} is added to the channel
	 * pipeline after sending the request and is removed when the response is fully received.
	 * If the {@code maxReadOperationInterval} is {@code null}, any previous setting will be removed and no
	 * {@code maxReadOperationInterval} will be applied.
	 * If the {@code maxReadOperationInterval} is less than {@code 1ms}, then {@code 1ms} will be the
	 * {@code maxReadOperationInterval}.
	 * The {@code maxReadOperationInterval} setting on {@link HttpClientRequest} level overrides any
	 * {@code maxReadOperationInterval} setting on {@link HttpClient} level.
	 *
	 * @param maxReadOperationInterval the maximum duration allowed between each network-level read operations
	 *                                 (resolution: ms).
	 * @return a new {@link HttpClient}
	 * @since 0.9.11
	 * @see io.netty.handler.timeout.ReadTimeoutHandler
	 */
	public final HttpClient responseTimeout(@Nullable Duration maxReadOperationInterval) {
		if (Objects.equals(maxReadOperationInterval, configuration().responseTimeout)) {
			return this;
		}
		HttpClient dup = duplicate();
		dup.configuration().responseTimeout = maxReadOperationInterval;
		return dup;
	}

	/**
	 * Enable default sslContext support.
	 * <p>By default {@link SslContext} is initialized with:
	 * <ul>
	 *     <li>{@code 10} seconds handshake timeout unless
	 *     the environment property {@code reactor.netty.tcp.sslHandshakeTimeout} is set</li>
	 *     <li>hostname verification enabled</li>
	 * </ul>
	 * </p>
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient secure() {
		SslProvider sslProvider = HttpClientSecure.defaultSslProvider(configuration());
		if (sslProvider.equals(configuration().sslProvider)) {
			return this;
		}
		HttpClient dup = duplicate();
		dup.configuration().sslProvider = sslProvider;
		return dup;
	}

	/**
	 * Apply an SSL configuration customization via the passed builder.
	 * <p>The builder will produce the {@link SslContext} with:
	 * <ul>
	 *     <li>{@code 10} seconds handshake timeout unless the passed builder sets another configuration or
	 *     the environment property {@code reactor.netty.tcp.sslHandshakeTimeout} is set</li>
	 *     <li>hostname verification enabled</li>
	 * </ul>
	 * </p>
	 *
	 * @param sslProviderBuilder builder callback for further customization of SslContext.
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");
		SslProvider.SslContextSpec builder = SslProvider.builder();
		sslProviderBuilder.accept(builder);
		SslProvider sslProvider = HttpClientSecure.sslProvider(((SslProvider.Builder) builder).build());
		if (sslProvider.equals(configuration().sslProvider)) {
			return this;
		}
		HttpClient dup = duplicate();
		dup.configuration().sslProvider = sslProvider;
		return dup;
	}

	/**
	 * Apply an SSL configuration via the passed {@link SslProvider}.
	 * <p>Note: Hostname verification is not enabled by default.
	 * If hostname verification is needed, please apply the
	 * {@link HttpClientSecurityUtils#HOSTNAME_VERIFICATION_CONFIGURER}
	 * configuration to the {@link SslProvider}:
	 * </p>
	 * <p>
	 * <pre>
	 * {@code
	 * SslProvider.builder()
	 *            .sslContext(...)
	 *            .handlerConfigurator(HttpClientSecurityUtils.HOSTNAME_VERIFICATION_CONFIGURER)
	 *            .build();
	 * }
	 * </pre>
	 * </p>
	 *
	 * @param sslProvider The provider to set when configuring SSL
	 * @return a new {@link HttpClient}
	 */
	public HttpClient secure(SslProvider sslProvider) {
		Objects.requireNonNull(sslProvider, "sslProvider");
		HttpClient dup = duplicate();
		dup.configuration().sslProvider = sslProvider;
		return dup;
	}

	/**
	 * Apply a {@link TcpClient} mapping function to update TCP configuration and
	 * return an enriched {@link HttpClient} to use.
	 * <p>
	 * <strong>Note:</strong>
	 * There isn't only one method that replaces this deprecated method.
	 * The configuration that can be done with this deprecated method,
	 * can also be done with the other methods exposed by {@link HttpClient}.
	 * </p>
	 * <p>Examples:</p>
	 * <p>Configuration via the deprecated '.tcpConfiguration(...)' method</p>
	 * <pre>
	 * {@code
	 * HttpClient.tcpConfiguration(tcpClient ->
	 *     tcpClient.attr(...) // configures the channel attributes
	 *              .bindAddress(...) // configures the bind (local) address
	 *              .channelGroup(...) // configures the channel group
	 *              .doOnChannelInit(...) // configures the channel handler
	 *              .doOnConnected(...) // configures the doOnConnected callback
	 *              .doOnDisconnected(...) // configures the doOnDisconnected callback
	 *              .host(...) // configures the host name
	 *              .metrics(...) // configures the metrics
	 *              .noProxy() // removes proxy configuration
	 *              .noSSL() // removes SSL configuration
	 *              .observe() // configures the connection observer
	 *              .option(...) // configures the channel options
	 *              .port(...) // configures the port
	 *              .proxy(...) // configures the proxy
	 *              .remoteAddress(...) // configures the remote address
	 *              .resolver(...) // configures the host names resolver
	 *              .runOn(...) // configures the event loop group
	 *              .secure() // configures the SSL
	 *              .wiretap()) // configures the wire logging
	 * }
	 * </pre>
	 *
	 * <p>Configuration via the other methods exposed by {@link HttpClient}</p>
	 * <pre>
	 * {@code
	 * HttpClient.attr(...) // configures the channel attributes
	 *           .bindAddress(...) // configures the bind (local) address
	 *           .channelGroup(...) // configures the channel group
	 *           .doOnChannelInit(...) // configures the channel handler
	 *           .doOnConnected(...) // configures the doOnConnected callback
	 *           .doOnDisconnected(...) // configures the doOnDisconnected callback
	 *           .host(...) // configures the host name
	 *           .metrics(...) // configures the metrics
	 *           .noProxy() // removes proxy configuration
	 *           .noSSL() // removes SSL configuration
	 *           .observe() // configures the connection observer
	 *           .option(...) // configures the channel options
	 *           .port(...) // configures the port
	 *           .proxy(...) // configures the proxy
	 *           .remoteAddress(...) // configures the remote address
	 *           .resolver(...) // configures the host names resolver
	 *           .runOn(...) // configures the event loop group
	 *           .secure() // configures the SSL
	 *           .wiretap() // configures the wire logging
	 * }
	 * </pre>
	 *
	 * <p>Wire logging in plain text</p>
	 * <pre>
	 * {@code
	 * HttpClient.wiretap("logger", LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL)
	 * }
	 * </pre>
	 *
	 * @param tcpMapper A {@link TcpClient} mapping function to update TCP configuration and
	 * return an enriched {@link HttpClient} to use.
	 * @return a new {@link HttpClient}
	 * @deprecated Use the other methods exposed by {@link HttpClient} to achieve the same configurations.
	 * This method will be removed in version 1.1.0.
	 */
	@Deprecated
	@SuppressWarnings("ReturnValueIgnored")
	public final HttpClient tcpConfiguration(Function<? super TcpClient, ? extends TcpClient> tcpMapper) {
		Objects.requireNonNull(tcpMapper, "tcpMapper");
		HttpClientTcpConfig tcpClient = new HttpClientTcpConfig(this);
		// ReturnValueIgnored is deliberate
		tcpMapper.apply(tcpClient);
		return tcpClient.httpClient;
	}

	/**
	 * Based on the actual configuration, returns a {@link Mono} that triggers:
	 * <ul>
	 *     <li>an initialization of the event loop group</li>
	 *     <li>an initialization of the host name resolver</li>
	 *     <li>loads the necessary native libraries for the transport</li>
	 *     <li>loads the necessary native libraries for the security if there is such</li>
	 * </ul>
	 * By default, when method is not used, the {@code first request} absorbs the extra time needed to load resources.
	 *
	 * @return a {@link Mono} representing the completion of the warmup
	 * @since 1.0.3
	 */
	@Override
	public Mono<Void> warmup() {
		return Mono.when(
				super.warmup(),
				// When the URL scheme is HTTPS and there is no security configured,
				// the default security will be used thus always try to load the OpenSsl natives
				// see HttpClientConnect.MonoHttpConnect#subscribe
				Mono.fromRunnable(OpenSsl::version));
	}

	/**
	 * HTTP Websocket to connect the {@link HttpClient}.
	 *
	 * @return a {@link WebsocketSender} ready to consume for response
	 */
	public final WebsocketSender websocket() {
		return websocket(WebsocketClientSpec.builder().build());
	}

	/**
	 * HTTP Websocket to connect the {@link HttpClient}.
	 *
	 * @param websocketClientSpec {@link WebsocketClientSpec} for websocket configuration
	 * @return a {@link WebsocketSender} ready to consume for response
	 * @since 0.9.7
	 */
	public final WebsocketSender websocket(WebsocketClientSpec websocketClientSpec) {
		Objects.requireNonNull(websocketClientSpec, "websocketClientSpec");
		WebsocketFinalizer dup = new WebsocketFinalizer(new HttpClientConfig(configuration()));
		HttpClientConfig config = dup.configuration();
		config.websocketClientSpec = websocketClientSpec;
		return dup;
	}

	@Override
	public final HttpClient wiretap(boolean enable) {
		return super.wiretap(enable);
	}

	static boolean isCompressing(HttpHeaders h) {
		return h.contains(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP, true);
	}

	static String reactorNettyVersion() {
		return Optional.ofNullable(HttpClient.class.getPackage()
		                                           .getImplementationVersion())
		               .orElse("dev");
	}

	static final Logger log = Loggers.getLogger(HttpClient.class);

	static final String HTTP_SCHEME = "http";

	static final String HTTPS_SCHEME = "https";

	static final String WS_SCHEME = "ws";

	static final String WSS_SCHEME = "wss";

	static final int DEFAULT_PORT = 80;

	static final int DEFAULT_SECURE_PORT = 443;
}
