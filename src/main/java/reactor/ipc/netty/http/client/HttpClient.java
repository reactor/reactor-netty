/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.http.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.ByteBufMono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.TcpClient;
import reactor.ipc.netty.tcp.TcpServer;

import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An HttpClient allows to build in a safe immutable way an http client that is
 * materialized and connecting when {@link #connect(Bootstrap)} is ultimately called.
 * <p> Internally, materialization happens in three phases, first {@link #tcpConfiguration()}
 * is called to retrieve a ready to use {@link TcpClient}, then {@link
 * TcpClient#configure()} retrieve a usable {@link Bootstrap} for the final {@link
 * #connect(Bootstrap)} is called.
 * <p> Examples:
 * <pre>
 * {@code
 * HttpClient.create("http://example.com")
 *           .get()
 *           .response()
 *           .block();
 * }
 * {@code
 * HttpClient.create("http://example.com")
 *           .post()
 *           .send(Flux.just(bb1, bb2, bb3))
 *           .responseSingle((res, content) -> {
 *                   content.subscribe().dispose();
 *                   return Mono.just(res.status().code());
 *           })
 *           .block();
 * }
 * {@code
 * HttpClient.prepare()
 *           .baseUri("http://example.com")
 *           .post()
 *           .send(ByteBufFlux.fromString(flux))
 *           .responseSingle((res, content) -> {
 *                   content.subscribe().dispose();
 *                   return Mono.just(res.status().code());
 *           })
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
	public interface UriConfiguration<S extends ResponseReceiver<?>> {

		/**
		 * Configure URI to use for this request/response
		 *
		 * @param uri target baseUri
		 *
		 * @return the appropriate sending or receiving contract
		 */
		S uri(String uri);

		/**
		 * Configure URI to use for this request/response on subscribe
		 *
		 * @param uri target baseUri
		 *
		 * @return the appropriate sending or receiving contract
		 */
		S uri(Mono<String> uri);
	}

	/**
	 * A ready to receive {@link HttpClient}
	 */
	public interface ResponseReceiver<S extends ResponseReceiver<?>>
			extends UriConfiguration<S> {

		/**
		 * @return
		 */
		Mono<HttpClientResponse> response();

		/**
		 * @param receiver
		 * @param <V>
		 *
		 * @return
		 */
		<V> Flux<V> response(BiFunction<? super HttpClientResponse, ? super ByteBufFlux, ? extends Publisher<? extends V>> receiver);

		/**
		 * @return
		 */
		ByteBufFlux responseContent();

		/**
		 * @param receiver
		 * @param <V>
		 *
		 * @return
		 */
		<V> Mono<V> responseSingle(BiFunction<? super HttpClientResponse, ? super ByteBufMono, ? extends Mono<? extends V>> receiver);

	}

	/**
	 * A ready to request {@link HttpClient}
	 */
	public interface RequestSender extends ResponseReceiver<RequestSender> {

		/**
		 * @param body
		 *
		 * @return
		 */
		ResponseReceiver<?> send(Publisher<? extends ByteBuf> body);

		/**
		 * @param sender
		 *
		 * @return
		 */
		ResponseReceiver<?> send(BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends NettyOutbound> sender);
	}

	public interface WebsocketReceiver extends ResponseReceiver<WebsocketReceiver> {

	}

	/**
	 * Prepare a pooled {@link HttpClient} given the base URI.
	 *
	 * @return a new {@link HttpClient}
	 */
	public static HttpClient create(String baseUri) {
		return prepare().baseUrl(baseUri);
	}

	/**
	 * Prepare a pooled {@link HttpClient}. {@link UriConfiguration#uri(String)} or
	 * {@link #baseUrl(String)} should be invoked before a verb
	 * {@link #request(HttpMethod)} is selected.
	 *
	 * @return a {@link HttpClient}
	 */
	public static HttpClient prepare() {
		return HttpClientConnect.INSTANCE;
	}

	/**
	 * Prepare a pooled {@link HttpClient}. {@link UriConfiguration#uri(String)} or
	 * {@link #baseUrl(String)} should be invoked before a verb
	 * {@link #request(HttpMethod)} is selected.
	 *
	 * @return a {@link HttpClient}
	 */
	public static HttpClient prepare(PoolResources poolResources) {
		return new HttpClientConnect(TcpClient.create(poolResources)
		                                      .secure()
		                                      .bootstrap(HTTP_OPS_CONF));
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
		return new HttpClientBaseUrl(this, baseUrl);
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
	 * Enable gzip compression
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient compress() {
		return tcpConfiguration(COMPRESS_ATTR_CONFIG);
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
	 * Setup a callback called when {@link HttpClientRequest} is about to be sent.
	 *
	 * @param doOnRequest a consumer observing connected events
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnRequest(Consumer<? super HttpClientRequest> doOnRequest) {
		return new HttpClientLifecycle(this, doOnRequest, null, null, null);
	}

	/**
	 * Setup a callback called when {@link HttpClientRequest} has been sent
	 *
	 * @param doAfterRequest a consumer observing connected events
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doAfterRequest(Consumer<? super HttpClientRequest> doAfterRequest) {
		return new HttpClientLifecycle(this, null, doAfterRequest, null, null);
	}

	/**
	 * Setup a callback called after {@link HttpClientResponse} headers have been
	 * received
	 *
	 * @param doOnResponse a consumer observing connected events
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doOnResponse(Consumer<? super HttpClientResponse> doOnResponse) {
		return new HttpClientLifecycle(this, null, null, doOnResponse, null);
	}

	/**
	 * Setup a callback called after {@link HttpClientResponse} has been fully received.
	 *
	 * @param doAfterResponse a consumer observing disconnected events
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient doAfterResponse(Consumer<? super HttpClientResponse> doAfterResponse) {
		return new HttpClientLifecycle(this, null, null, null, doAfterResponse);
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
	 * HTTP GET to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to consume for response
	 */
	public final ResponseReceiver<?> head() {
		return request(HttpMethod.HEAD);
	}

	/**
	 * Apply headers configuration.
	 *
	 * @param headerBuilder the  header {@link Consumer} to invoke before sending
	 * websocket handshake
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient headers(Consumer<? super HttpHeaders> headerBuilder) {
		return new HttpClientHeaders(this, headerBuilder);
	}

	/**
	 * Disable gzip compression
	 *
	 * @return a new {@link HttpClient}
	 */
	public final HttpClient noCompression() {
		return tcpConfiguration(COMPRESS_ATTR_DISABLE);
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
	 * @return a {@link RequestSender} ready to consume for response
	 */
	public final RequestSender patch() {
		return request(HttpMethod.PATCH);
	}

	/**
	 * HTTP POST to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to consume for response
	 */
	public final RequestSender post() {
		return request(HttpMethod.POST);
	}

	/**
	 * HTTP PUT to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to consume for response
	 */
	public final RequestSender put() {
		return request(HttpMethod.PUT);
	}

	/**
	 * Use the passed HTTP method to connect the {@link HttpClient}.
	 *
	 * @param method the HTTP method to send
	 *
	 * @return a {@link RequestSender} ready to consume for response
	 */
	public RequestSender request(HttpMethod method) {
		Objects.requireNonNull(method, "method");
		return new HttpClientFinalizer(tcpConfiguration(tcp -> tcp.attr(HttpClientConnect.METHOD, method)));
	}

	/**
	 * Apply {@link Bootstrap} configuration given mapper taking currently configured one
	 * and returning a new one to be ultimately used for socket binding. <p> Configuration
	 * will apply during {@link #tcpConfiguration()} phase.
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
	 * WebSocket to connect the {@link HttpClient}.
	 *
	 * @return a {@link RequestSender} ready to consume for response

	public final WebsocketReceiver ws() {
		return request(WS, HttpClientRequest::sendWebsocket);
	}*/ //TODO

	/**
	 * WebSocket to the passed URL, negotiating one of the passed subprotocols.
	 * <p>
	 * The negotiated subprotocol can be accessed through the {@link HttpClientResponse}
	 * by switching to websocket (using any of the {@link HttpClientResponse#receiveWebsocket()
	 * receiveWebSocket} methods) and using {@link WebsocketInbound#selectedSubprotocol()}.
	 * <p>
	 * To send data through the websocket, use {@link HttpClientResponse#receiveWebsocket(BiFunction)}
	 * and then use the function's {@link WebsocketOutbound}.
	 *
	 * @param subprotocols the subprotocol(s) to negotiate, comma-separated, or null if
	 * not relevant.
	 *
	 * @return a {@link RequestSender} ready to consume for response

	public final WebsocketReceiver ws(String subprotocols) {
		return request(WS, req -> req.sendWebsocket(subprotocols));
	}*/ //TODO

	/**
	 * The port to which this client should connect.
	 *
	 * @param port The port to connect to.
	 *
	 * @return a new {@link TcpClient}
	 */
	public final HttpClient port(int port) {
		return tcpConfiguration(tcpClient -> tcpClient.port(port));
	}

	/**
	 * Apply a wire logger configuration using {@link TcpServer} category
	 *
	 * @return a new {@link TcpServer}
	 */
	public final HttpClient wiretap() {
		return tcpConfiguration(tcpClient -> tcpClient.bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER)));
	}

	/**
	 *
	 * @return
	 */
	protected String baseUri() {
		return null;
	}

	/**
	 * Bind the {@link HttpClient} and return a {@link Mono} of {@link Connection}
	 *
	 * @param delegate the {@link TcpClient} to connect on
	 *
	 * @return a {@link Mono} of {@link Connection}
	 */
	protected abstract Mono<? extends Connection> connect(TcpClient delegate);

	/**
	 * Materialize a TcpClient from the parent {@link HttpClient} chain to use with {@link
	 * #connect(TcpClient)} or separately
	 *
	 * @return a configured {@link TcpClient}
	 */
	protected TcpClient tcpConfiguration() {
		return DEFAULT_TCP_CLIENT;
	}


	static final ChannelOperations.OnSetup<Channel> HTTP_OPS =
			(ch, c, msg) -> HttpClientOperations.bindHttp(ch, c);

	static final Function<Bootstrap, Bootstrap> HTTP_OPS_CONF = b -> {
		BootstrapHandlers.channelOperationFactory(b, HTTP_OPS);
		return b;
	};

	static String reactorNettyVersion() {
		return Optional.ofNullable(HttpClient.class.getPackage()
		                                           .getImplementationVersion())
		               .orElse("dev");
	}

	final static HttpMethod                WS           = new HttpMethod("WS");
	final static String                    WS_SCHEME    = "ws";
	final static String                    WSS_SCHEME   = "wss";
	final static String                    HTTP_SCHEME  = "http";

	final static String                    HTTPS_SCHEME = "https";

	static final TcpClient DEFAULT_TCP_CLIENT = TcpClient.create(HttpResources.get())
	                                                     .secure()
	                                                     .bootstrap(HTTP_OPS_CONF)
	                                                     .port(80);

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(HttpClient.class);

	static final AttributeKey<Boolean>          ACCEPT_GZIP          =
			AttributeKey.newInstance("acceptGzip");

	static final Function<TcpClient, TcpClient> COMPRESS_ATTR_CONFIG =
			tcp -> tcp.attr(ACCEPT_GZIP, true);

	static final Function<TcpClient, TcpClient> COMPRESS_ATTR_DISABLE =
			tcp -> tcp.attr(ACCEPT_GZIP, null);
}
