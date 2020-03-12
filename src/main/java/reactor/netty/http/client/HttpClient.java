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
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.logging.LoggingHandler;
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
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.HttpResources;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpClient;
import reactor.util.Metrics;

/**
 * An HttpClient allows to build in a safe immutable way an http client that is
 * materialized and connecting when {@link TcpClient#connect()} is ultimately called.
 * <p> Internally, materialization happens in three phases, first {@link #tcpConfiguration()}
 * is called to retrieve a ready to use {@link TcpClient}, then {@link
 * TcpClient#configure()} retrieve a usable {@link Bootstrap} for the final {@link
 * TcpClient#connect()} is called.
 * {@code Transfer-Encoding: chunked} will be applied for those HTTP methods for which
 * a request body is expected. {@code Content-Length} provided via request headers
 * will disable {@code Transfer-Encoding: chunked}.
 * <p> Examples:
 * <pre>
 * {@code
 * HttpClient.create()
 *           .baseUrl("http://example.com")
 *           .get()
 *           .response()
 *           .block();
 * }
 * {@code
 * HttpClient.create()
 *           .post()
 *           .uri("http://example.com")
 *           .send(Flux.just(bb1, bb2, bb3))
 *           .responseSingle((res, content) -> Mono.just(res.status().code()))
 *           .block();
 * }
 * {@code
 * HttpClient.create()
 *           .baseUri("http://example.com")
 *           .post()
 *           .send(ByteBufFlux.fromString(flux))
 *           .responseSingle((res, content) -> Mono.just(res.status().code()))
 *           .block();
 * }
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public abstract class HttpClient {

	public static final String                         USER_AGENT =
			String.format("ReactorNetty/%s", reactorNettyVersion());

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
		 * @param progress called after form is being sent and passed with a {@link Flux} of latest in-flight or uploaded bytes
		 *
		 * @return a new {@link ResponseReceiver}
		 */
		ResponseReceiver<?> sendForm(BiConsumer<? super HttpClientRequest, HttpClientForm> formCallback,
				@Nullable Consumer<Flux<Long>>progress);
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
	public interface WebsocketReceiver<S extends WebsocketReceiver<?>> extends UriConfiguration<S>  {
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
		return create(HttpResources.get());
	}

	/**
	 * Prepare an {@link HttpClient}. {@link UriConfiguration#uri(String)} or
	 * {@link #baseUrl(String)} should be invoked before a verb
	 * {@link #request(HttpMethod)} is selected.
	 *
	 * @return a {@link HttpClient}
	 */
	public static HttpClient create(ConnectionProvider connectionProvider) {
		return new HttpClientConnect(TcpClient.create(connectionProvider)
		                                      .port(80));
	}

	/**
	 * Prepare an unpooled {@link HttpClient}. {@link UriConfiguration#uri(String)} or
	 * {@link #baseUrl(String)} should be invoked after a verb
	 * {@link #request(HttpMethod)} is selected.
	 *
	 * @return a {@link HttpClient}
	 */
	public static HttpClient newConnection() {
		return HttpClientConnect.INSTANCE;
	}

	/**
	 * Prepare a pooled {@link HttpClient}
	 *
	 * @return a {@link HttpClient}
	 */
	public static HttpClient from(TcpClient tcpClient) {
		return new HttpClientConnect(tcpClient);
	}

	/**
	 * Configure URI to use for this request/response
	 *
	 * @param baseUrl a default base url that can be fully sufficient for request or can
	 * be used to prepend future {@link UriConfiguration#uri} calls.
	 *
	 * @return the appropriate sending or receiving contract
	 */
	public final HttpClient baseUrl(String baseUrl) {
		Objects.requireNonNull(baseUrl, "baseUrl");
		return tcpConfiguration(tcp -> tcp.bootstrap(b -> HttpClientConfiguration.baseUrl(b, baseUrl)));
	}

	/**
	 * The address to which this client should connect for each subscribe.
	 *
	 * @param connectAddressSupplier A supplier of the address to connect to.
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient addressSupplier(Supplier<? extends SocketAddress> connectAddressSupplier) {
		Objects.requireNonNull(connectAddressSupplier, "connectAddressSupplier");
		return tcpConfiguration(tcpClient -> tcpClient.addressSupplier(connectAddressSupplier));
	}

	/**
	 * Specifies whether GZip compression/websocket compression
	 * extension is enabled.
	 *
	 * @param compressionEnabled if true GZip compression/websocket compression extension
	 *                              is enabled otherwise disabled (default: false)
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient compress(boolean compressionEnabled) {
		if (compressionEnabled) {
			return tcpConfiguration(COMPRESS_ATTR_CONFIG).headers(COMPRESS_HEADERS);
		}
		else {
			return tcpConfiguration(COMPRESS_ATTR_DISABLE).headers(COMPRESS_HEADERS_DISABLE);
		}
	}

	/**
	 * Intercept the connection lifecycle and allows to delay, transform or inject a
	 * context.
	 *
	 * @param connector A bi function mapping the default connection and configured
	 * bootstrap to a target connection.
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient mapConnect(BiFunction<? super Mono<? extends Connection>, ? super Bootstrap, ? extends Mono<? extends Connection>> connector) {
		return new HttpClientOnConnectMap(this, connector);
	}

	/**
	 * Apply cookies configuration.
	 *
	 * @param cookie a cookie to append to the request(s)
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient cookie(Cookie cookie) {
		return new HttpClientCookie(this, cookie);
	}

	/**
	 * Apply cookies configuration.
	 *
	 * @param cookieBuilder the header {@link Consumer} to invoke before requesting
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient cookie(String name, Consumer<? super Cookie> cookieBuilder) {
		return new HttpClientCookie(this, name, cookieBuilder);
	}

	/**
	 * Apply cookies configuration emitted by the returned Mono before requesting.
	 *
	 * @param cookieBuilder the cookies {@link Function} to invoke before sending
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient cookiesWhen(String name, Function<? super Cookie, Mono<? extends Cookie>> cookieBuilder) {
		return new HttpClientCookieWhen(this, name, cookieBuilder);
	}

	/**
	 * Configure the
	 * {@link ClientCookieEncoder}, {@link ClientCookieDecoder} will be
	 * chosen based on the encoder
	 *
	 * @param encoder the preferred ClientCookieEncoder
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient cookieCodec(ClientCookieEncoder encoder) {
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
	 */
	public final HttpClient cookieCodec(ClientCookieEncoder encoder, ClientCookieDecoder decoder) {
		return tcpConfiguration(tcp -> tcp.bootstrap(
				b -> HttpClientConfiguration.cookieCodec(b, encoder, decoder)));
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
	 * Setup a callback called when {@link HttpClientRequest} has not been sent and when {@link HttpClientResponse} has not been fully
	 * received.
	 * <p>
	 * Note that some mutation of {@link HttpClientRequest} performed late in lifecycle
	 * {@link #doOnRequest(BiConsumer)} or {@link RequestSender#send(BiFunction)} might
	 * not be visible if the error results from a connection failure.
	 *
	 * @param doOnRequest a consumer observing request failures
	 * @param doOnResponse a consumer observing response failures
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnError(BiConsumer<? super HttpClientRequest, ? super Throwable> doOnRequest,
			BiConsumer<? super HttpClientResponse, ? super Throwable> doOnResponse) {
		Objects.requireNonNull(doOnRequest, "doOnRequest");
		Objects.requireNonNull(doOnResponse, "doOnResponse");
		return new HttpClientDoOnError(this, doOnRequest, doOnResponse);
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
		return new HttpClientDoOn(this, doOnRequest, null, null, null, null);
	}

	/**
	 * Setup a callback called when {@link HttpClientRequest} has not been sent.
	 * Note that some mutation of {@link HttpClientRequest} performed late in lifecycle
	 * {@link #doOnRequest(BiConsumer)} or {@link RequestSender#send(BiFunction)} might
	 * not be visible if the error results from a connection failure.
	 *
	 * @param doOnRequest a consumer observing request failures
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnRequestError(BiConsumer<? super HttpClientRequest, ? super Throwable> doOnRequest) {
		Objects.requireNonNull(doOnRequest, "doOnRequest");
		return new HttpClientDoOnError(this, doOnRequest, null);
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
		return new HttpClientDoOn(this, null, doAfterRequest, null, null, null);
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
		return new HttpClientDoOn(this, null, null, doOnResponse, null, null);
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
		return new HttpClientDoOn(this, null, null, null, null, doOnRedirect);
	}

	/**
	 * Setup a callback called when {@link HttpClientResponse} has not been fully
	 * received, {@link HttpClientState#RESPONSE_INCOMPLETE} has been emitted.
	 *
	 * @param doOnResponse a consumer observing response failures
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnResponseError(BiConsumer<? super HttpClientResponse, ? super Throwable> doOnResponse) {
		Objects.requireNonNull(doOnResponse, "doOnResponse");
		return new HttpClientDoOnError(this, null, doOnResponse);
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
		return new HttpClientDoOn(this, null, null, null, doAfterResponseSuccess, null);
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
		return new HttpClientHeaders(this, headerBuilder);
	}

	/**
	 * Apply headers configuration emitted by the returned Mono before requesting.
	 *
	 * @param headerBuilder the header {@link Function} to invoke before sending
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient headersWhen(Function<? super HttpHeaders, Mono<? extends HttpHeaders>> headerBuilder) {
		return new HttpClientHeadersWhen(this, headerBuilder);
	}

	/**
	 * Enable or Disable Keep-Alive support for the outgoing request.
	 *
	 * @param keepAlive true if keepAlive should be enabled (default: true)
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient keepAlive(boolean keepAlive) {
		if (keepAlive) {
			return tcpConfiguration(KEEPALIVE_ATTR_CONFIG);
		}
		else {
			return tcpConfiguration(KEEPALIVE_ATTR_DISABLE);
		}
	}

	/**
	 * Specifies whether HTTP status 301|302|307|308 auto-redirect support is enabled.
	 *
	 * <p><strong>Note:</strong> The sensitive headers {@link #followRedirect(boolean, Consumer) followRedirect}
	 * are removed from the initialized request when redirecting to a different domain, they can be re-added
	 * via {@link #followRedirect(boolean, Consumer)}.
	 *
	 * @param followRedirect if true HTTP status 301|302|307|308 auto-redirect support
	 *                       is enabled, otherwise disabled (default: false).
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient followRedirect(boolean followRedirect) {
		return followRedirect(followRedirect, null);
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
	 *                       is enabled, otherwise disabled (default: false).
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
			return followRedirect(HttpClientConfiguration.FOLLOW_REDIRECT_PREDICATE, redirectRequestConsumer);
		}
		else {
			return tcpConfiguration(FOLLOW_REDIRECT_ATTR_DISABLE);
		}
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
		return followRedirect(predicate, null);
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
		return tcpConfiguration(tcp -> tcp.bootstrap(
				b -> HttpClientConfiguration.followRedirectPredicate(b, predicate, redirectRequestConsumer)));
	}

	/**
	 * Setup all lifecycle callbacks called on or after {@link io.netty.channel.Channel}
	 * has been connected and after it has been disconnected.
	 *
	 * @param observer a consumer observing state changes
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient observe(ConnectionObserver observer) {
		return new HttpClientObserve(this, observer);
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

	/**
	 * The port to which this client should connect.
	 *
	 * @param port The port to connect to.
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient port(int port) {
		return tcpConfiguration(tcpClient -> tcpClient.port(port));
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
	 * @param supportedProtocols The various {@link HttpProtocol} this server will support
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient protocol(HttpProtocol... supportedProtocols) {
		return tcpConfiguration(tcpClient -> tcpClient.bootstrap(b -> HttpClientConfiguration.protocols(b, supportedProtocols)));
	}


	/**
	 * HTTP PUT to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to finalize request and consume for response
	 */
	public final RequestSender put() {
		return request(HttpMethod.PUT);
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
		TcpClient tcpConfiguration = tcpConfiguration().bootstrap(b -> HttpClientConfiguration.method(b, method));
		return new HttpClientFinalizer(tcpConfiguration);
	}

	/**
	 * Enable default sslContext support. The default {@link SslContext} will be
	 * assigned to
	 * with a default value of {@code 10} seconds handshake timeout unless
	 * the environment property {@code reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient secure() {
		return new HttpClientSecure(this, null);
	}

	/**
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@code 10} seconds handshake timeout unless the environment property {@code
	 * reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * @param sslProviderBuilder builder callback for further customization of SslContext.
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		return HttpClientSecure.secure(this, sslProviderBuilder);
	}

	/**
	 * Apply {@link Bootstrap} configuration given mapper taking currently configured one
	 * and returning a new one to be ultimately used for socket binding. <p> Configuration
	 * will apply during {@link #tcpConfiguration()} phase.
	 *
	 * <p> Always prefer {@link HttpClient#secure} to
	 * {@link #tcpConfiguration()} and {@link TcpClient#secure}. While configuration
	 * with the later is possible, {@link HttpClient#secure} will inject extra information
	 * for HTTPS support.
	 *
	 * @param tcpMapper A tcpClient mapping function to update tcp configuration and
	 * return an enriched tcp client to use.
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient tcpConfiguration(Function<? super TcpClient, ? extends TcpClient> tcpMapper) {
		return new HttpClientTcpConfig(this, tcpMapper);
	}

	/**
	 * Apply or remove a wire logger configuration using {@link HttpClient} category
	 * and {@code DEBUG} logger level
	 *
	 * @param enable Specifies whether the wire logger configuration will be added to
	 *               the pipeline
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient wiretap(boolean enable) {
		if (enable) {
			return tcpConfiguration(tcpClient -> tcpClient.bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER)));
		}
		else {
			return tcpConfiguration(tcpClient -> tcpClient.bootstrap(
			        b -> BootstrapHandlers.removeConfiguration(b, NettyPipeline.LoggingHandler)));
		}
	}

	/**
	 * Whether to enable metrics to be collected and registered in Micrometer's
	 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}
	 * under the name {@link reactor.netty.Metrics#HTTP_CLIENT_PREFIX}. Applications can
	 * separately register their own
	 * {@link io.micrometer.core.instrument.config.MeterFilter filters} associated with this name.
	 * For example, to put an upper bound on the number of tags produced:
	 * <pre class="code">
	 * MeterFilter filter = ... ;
	 * Metrics.globalRegistry.config().meterFilter(MeterFilter.maximumAllowableTags(HTTP_CLIENT_PREFIX, 100, filter));
	 * </pre>
	 * <p>By default this is not enabled.
	 *
	 * @param metricsEnabled true enables metrics collection; false disables it
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient metrics(boolean metricsEnabled) {
		if (metricsEnabled) {
			if (!Metrics.isInstrumentationAvailable()) {
				throw new UnsupportedOperationException(
						"To enable metrics, you must add the dependency `io.micrometer:micrometer-core`" +
								" to the class path first");
			}

			return tcpConfiguration(tcpClient ->
					tcpClient.bootstrap(b -> BootstrapHandlers.updateMetricsSupport(b,
							MicrometerHttpClientMetricsRecorder.INSTANCE)));
		}
		else {
			return tcpConfiguration(tcpClient -> tcpClient.bootstrap(BootstrapHandlers::removeMetricsSupport));
		}
	}

	/**
	 * Specifies whether the metrics are enabled on the {@link HttpClient}.
	 * All generated metrics are provided to the specified recorder.
	 *
	 * @param metricsEnabled if true enables the metrics on the server.
	 * @param recorder the {@link HttpClientMetricsRecorder}
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient metrics(boolean metricsEnabled, HttpClientMetricsRecorder recorder) {
		return tcpConfiguration(tcpClient -> tcpClient.metrics(metricsEnabled, recorder));
	}

	/**
	 * Configure the {@link io.netty.handler.codec.http.HttpClientCodec}'s response decoding options.
	 *
	 * @param responseDecoderOptions a function to mutate the provided Http response decoder options
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient httpResponseDecoder(Function<HttpResponseDecoderSpec, HttpResponseDecoderSpec> responseDecoderOptions) {
		return tcpConfiguration(
				responseDecoderOptions.apply(new HttpResponseDecoderSpec())
				                      .build());
	}

	/**
	 * HTTP Websocket to connect the {@link HttpClient}.
	 *
	 * @return a {@link WebsocketSender} ready to consume for response
	 */
	public final WebsocketSender websocket() {
		return websocket("");
	}

	/**
	 * HTTP Websocket to connect the {@link HttpClient}.
	 *
	 * @param subprotocols a websocket subprotocol comma separated list
	 *
	 * @return a {@link WebsocketSender} ready to consume for response
	 */
	public final WebsocketSender websocket(String subprotocols) {
		return websocket(subprotocols, 65536);
	}

	/**
	 * HTTP Websocket to connect the {@link HttpClient}.
	 *
	 * @param maxFramePayloadLength maximum allowable frame payload length
	 *
	 * @return a {@link WebsocketSender} ready to consume for response
	 */
	public final WebsocketSender websocket(int maxFramePayloadLength) {
		return websocket("", maxFramePayloadLength);
	}

	/**
	 * HTTP Websocket to connect the {@link HttpClient}.
	 *
	 * @param proxyPing whether to proxy websocket ping frames or respond to them
	 *
	 * @return a {@link WebsocketSender} ready to consume for response
	 * @since 0.9.3
	 */
	public final WebsocketSender websocket(boolean proxyPing) {
		return websocket("", 65536, proxyPing);
	}

	/**
	 * HTTP Websocket to connect the {@link HttpClient}.
	 *
	 * @param subprotocols a websocket subprotocol comma separated list
	 * @param maxFramePayloadLength maximum allowable frame payload length
	 *
	 * @return a {@link WebsocketSender} ready to consume for response
	 */
	public final WebsocketSender websocket(String subprotocols, int maxFramePayloadLength) {
		return websocket(subprotocols, maxFramePayloadLength, false);
	}

	/**
	 * HTTP Websocket to connect the {@link HttpClient}.
	 *
	 * @param subprotocols a websocket subprotocol comma separated list
	 * @param maxFramePayloadLength maximum allowable frame payload length
	 * @param proxyPing whether to proxy websocket ping frames or respond to them
	 *
	 * @return a {@link WebsocketSender} ready to consume for response
	 * @since 0.9.3
	 */
	public final WebsocketSender websocket(String subprotocols, int maxFramePayloadLength, boolean proxyPing) {
		Objects.requireNonNull(subprotocols, "subprotocols");
		TcpClient tcpConfiguration = tcpConfiguration()
				.bootstrap(b -> HttpClientConfiguration.websocketSubprotocols(b, subprotocols))
				.bootstrap(b -> HttpClientConfiguration.websocketMaxFramePayloadLength(b, maxFramePayloadLength))
				.bootstrap(b -> HttpClientConfiguration.websocketProxyPing(b, proxyPing));
		return new WebsocketFinalizer(tcpConfiguration);
	}

	/**
	 * Get a TcpClient from the parent {@link HttpClient} chain to use with {@link
	 * TcpClient#connect()}} or separately
	 *
	 * @return a configured {@link TcpClient}
	 */
	protected TcpClient tcpConfiguration() {
		return DEFAULT_TCP_CLIENT;
	}

	static String reactorNettyVersion() {
		return Optional.ofNullable(HttpClient.class.getPackage()
		                                           .getImplementationVersion())
		               .orElse("dev");
	}

	final static String                    WS_SCHEME    = "ws";
	final static String                    WSS_SCHEME   = "wss";
	final static String                    HTTP_SCHEME  = "http";
	final static String                    HTTPS_SCHEME = "https";

	static final TcpClient DEFAULT_TCP_CLIENT = TcpClient.newConnection()
	                                                     .port(80);

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(HttpClient.class);

	static final Function<TcpClient, TcpClient> COMPRESS_ATTR_CONFIG =
			tcp -> tcp.bootstrap(HttpClientConfiguration.MAP_COMPRESS);

	static final Function<TcpClient, TcpClient> COMPRESS_ATTR_DISABLE =
			tcp -> tcp.bootstrap(HttpClientConfiguration.MAP_NO_COMPRESS);

	static final Function<TcpClient, TcpClient> KEEPALIVE_ATTR_CONFIG =
			tcp -> tcp.bootstrap(HttpClientConfiguration.MAP_KEEPALIVE);

	static final Function<TcpClient, TcpClient> KEEPALIVE_ATTR_DISABLE =
			tcp -> tcp.bootstrap(HttpClientConfiguration.MAP_NO_KEEPALIVE);

	static final Function<TcpClient, TcpClient> FOLLOW_REDIRECT_ATTR_DISABLE =
			tcp -> tcp.bootstrap(HttpClientConfiguration.MAP_NO_REDIRECT);

	static final Consumer<? super HttpHeaders> COMPRESS_HEADERS =
			h -> h.add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);

	static final Consumer<? super HttpHeaders> COMPRESS_HEADERS_DISABLE = h -> {
		if (isCompressing(h)) {
			h.remove(HttpHeaderNames.ACCEPT_ENCODING);
		}
	};

	static boolean isCompressing(HttpHeaders h){
		return h.contains(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP, true);
	}

}
