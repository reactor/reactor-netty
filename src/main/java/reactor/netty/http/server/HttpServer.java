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

package reactor.netty.http.server;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpServer;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * An HttpServer allows to build in a safe immutable way an HTTP server that is
 * materialized and connecting when {@link #bind(TcpServer)} is ultimately called.
 * <p> Internally, materialization happens in three phases, first {@link
 * #tcpConfiguration()} is called to retrieve a ready to use {@link TcpServer}, then
 * {@link HttpServer#tcpConfiguration()} ()} retrieve a usable {@link TcpServer} for the final
 * {@link #bind(TcpServer)} is called. <p> Examples:
 * <pre>
 * {@code
 * HttpServer.create()
 *           .host("0.0.0.0")
 *           .secure()
 *           .handle((req, res) -> res.sendString(Flux.just("hello"))
 *           .bind()
 *           .block();
 * }
 *
 * @author Stephane Maldini
 */
public abstract class HttpServer {

	/**
	 * Prepare a {@link HttpServer}
	 *
	 * @return a {@link HttpServer}
	 */
	public static HttpServer create() {
		return HttpServerBind.INSTANCE;
	}

	/**
	 * Prepare a {@link HttpServer}
	 *
	 * @return a {@link HttpServer}
	 */
	public static HttpServer from(TcpServer tcpServer) {
		return new HttpServerBind(tcpServer);
	}

	/**
	 * Bind the {@link HttpServer} and return a {@link Mono} of {@link DisposableServer}. If
	 * {@link Mono} is cancelled, the underlying binding will be aborted. Once the {@link
	 * DisposableServer} has been emitted and is not necessary anymore, disposing main server
	 * loop must be done by the user via {@link DisposableServer#dispose()}.
	 *
	 * If update configuration phase fails, a {@link Mono#error(Throwable)} will be returned
	 *
	 * @return a {@link Mono} of {@link DisposableServer}
	 */
	public final Mono<? extends DisposableServer> bind() {
		return bind(tcpConfiguration());
	}

	/**
	 * Start a Server in a blocking fashion, and wait for it to finish initializing. The
	 * returned {@link DisposableServer} offers simple server API, including to {@link
	 * DisposableServer#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @return a {@link Connection}
	 */
	public final DisposableServer bindNow() {
		return bindNow(Duration.ofSeconds(45));
	}


	/**
	 * Start a Server in a blocking fashion, and wait for it to finish initializing. The
	 * returned {@link DisposableServer} offers simple server API, including to {@link
	 * DisposableServer#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @param timeout max startup timeout
	 *
	 * @return a {@link DisposableServer}
	 */
	public final DisposableServer bindNow(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		try {
			return Objects.requireNonNull(bind().block(timeout), "aborted");
		}
		catch (IllegalStateException e) {
			if (e.getMessage().contains("blocking read")) {
				throw new IllegalStateException("HttpServer couldn't be started within "
						+ timeout.toMillis() + "ms");
			}
			throw e;
		}
	}

	/**
	 * Start a Server in a fully blocking fashion, not only waiting for it to initialize
	 * but also blocking during the full lifecycle of the client/server. Since most
	 * servers will be long-lived, this is more adapted to running a server out of a main
	 * method, only allowing shutdown of the servers through sigkill.
	 * <p>
	 * Note that a {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} is added by
	 * this method in order to properly disconnect the client/server upon receiving a
	 * sigkill signal.
	 *
	 * @param timeout a timeout for server shutdown
	 * @param onStart an optional callback on server start
	 */
	public final void bindUntilJavaShutdown(Duration timeout,
			@Nullable Consumer<DisposableServer> onStart) {

		Objects.requireNonNull(timeout, "timeout");
		DisposableServer facade = bindNow();

		Objects.requireNonNull(facade, "facade");

		if (onStart != null) {
			onStart.accept(facade);
		}
		Runtime.getRuntime()
		       .addShutdownHook(new Thread(() -> facade.disposeNow(timeout)));

		facade.onDispose()
		      .block();
	}

	/**
	 * Enable GZip response compression if the client request presents accept encoding
	 * headers.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer compress() {
		return tcpConfiguration(COMPRESS_ATTR_CONFIG);
	}

	/**
	 * Enable GZip response compression if the client request presents accept encoding
	 * headers AND the response reaches a minimum threshold
	 *
	 * @param minResponseSize compression is performed once response size exceeds given
	 * value in byte
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer compress(int minResponseSize) {
		if (minResponseSize < 0) {
			throw new IllegalArgumentException("minResponseSize must be positive");
		}
		return tcpConfiguration(tcp -> tcp.bootstrap(b -> HttpServerConfiguration.compressSize(b, minResponseSize)));
	}

	/**
	 * Enable GZip response compression if the client request presents accept encoding
	 * headers and the passed {@link java.util.function.Predicate} matches.
	 * <p>
	 *     note the passed {@link HttpServerRequest} and {@link HttpServerResponse}
	 *     should be considered read-only and the implement SHOULD NOT consume or
	 *     write the request/response in this predicate.
	 *
	 * @param predicate that returns true to compress the response.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer compress(BiPredicate<HttpServerRequest, HttpServerResponse> predicate) {
		Objects.requireNonNull(predicate, "compressionPredicate");
		return tcpConfiguration(tcp -> tcp.bootstrap(b -> HttpServerConfiguration.compressPredicate(b, predicate)));
	}

	/**
	 * Enable support for the {@code "Forwarded"} and {@code "X-Forwarded-*"}
	 * HTTP request headers for deriving information about the connection.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer forwarded() {
		return tcpConfiguration(FORWARD_ATTR_CONFIG);
	}

	/**
	 * The host to which this server should bind.
	 *
	 * @param host The host to bind to.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer host(String host) {
		return tcpConfiguration(tcpServer -> tcpServer.host(host));
	}

	/**
	 * Attach an IO handler to react on connected server
	 *
	 * @param handler an IO handler that can dispose underlying connection when {@link
	 * Publisher} terminates. Only the first registered handler will subscribe to the
	 * returned {@link Publisher} while other will immediately cancel given a same
	 * {@link Connection}
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer handle(BiFunction<? super HttpServerRequest, ? super
			HttpServerResponse, ? extends Publisher<Void>> handler) {
		return new HttpServerHandle(this, handler);
	}

	/**
	 * Configure the {@link io.netty.handler.codec.http.HttpServerCodec}'s request decoding options.
	 *
	 * @param requestDecoderOptions a function to mutate the provided Http request decoder options
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer httpRequestDecoder(Function<HttpRequestDecoderSpec, HttpRequestDecoderSpec> requestDecoderOptions) {
		return tcpConfiguration(
				requestDecoderOptions.apply(new HttpRequestDecoderSpec())
				                     .build());
	}

	/**
	 * Disable gzip compression
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer noCompression() {
		return tcpConfiguration(COMPRESS_ATTR_DISABLE);
	}

	/**
	 * Disable support for the {@code "Forwarded"} and {@code "X-Forwarded-*"}
	 * HTTP request headers.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer noForwarded() {
		return tcpConfiguration(FORWARD_ATTR_DISABLE);
	}

	/**
	 * Setup all lifecycle callbacks called on or after
	 * each child {@link io.netty.channel.Channel}
	 * has been connected and after it has been disconnected.
	 *
	 * @param observer a consumer observing state changes
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer observe(ConnectionObserver observer) {
		return new HttpServerObserve(this, observer);
	}

	/**
	 * The port to which this server should bind.
	 *
	 * @param port The port to bind to.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer port(int port) {
		return tcpConfiguration(tcpServer -> tcpServer.port(port));
	}

	/**
	 * Enable default sslContext support. The default {@link SslContext} will be
	 * assigned to
	 * with a default value of {@code 10} seconds handshake timeout unless
	 * the environment property {@code reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer secure() {
		return new HttpServerSecure(this, null);
	}

	/**
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@code 10} seconds handshake timeout unless the environment property {@code
	 * reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * @param sslProviderBuilder builder callback for further customization of SslContext.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		return HttpServerSecure.secure(this, sslProviderBuilder);
	}

	/**
	 * Define routes for the server through the provided {@link HttpServerRoutes} builder.
	 *
	 * @param routesBuilder provides a route builder to be mutated in order to define routes.
	 * @return a new {@link HttpServer} starting the router on subscribe
	 */
	public final HttpServer route(Consumer<? super HttpServerRoutes> routesBuilder) {
		Objects.requireNonNull(routesBuilder, "routeBuilder");
		HttpServerRoutes routes = HttpServerRoutes.newRoutes();
		routesBuilder.accept(routes);
		return handle(routes);
	}

	/**
	 * Apply {@link ServerBootstrap} configuration given mapper taking currently
	 * configured one and returning a new one to be ultimately used for socket binding.
	 * <p> Configuration will apply during {@link #tcpConfiguration()} phase.
	 *
	 * @param tcpMapper A tcpServer mapping function to update tcp configuration and
	 * return an enriched tcp server to use.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer tcpConfiguration(Function<? super TcpServer, ? extends TcpServer> tcpMapper) {
		return new HttpServerTcpConfig(this, tcpMapper);
	}

	/**
	 * Apply a wire logger configuration using {@link HttpServer} category
	 * and {@code DEBUG} logger level
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer wiretap() {
		return tcpConfiguration(tcpServer ->
		        tcpServer.bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER)));
	}

	/**
	 * Bind the {@link HttpServer} and return a {@link Mono} of {@link DisposableServer}
	 *
	 * @param b the {@link TcpServer} to bind
	 *
	 * @return a {@link Mono} of {@link DisposableServer}
	 */
	protected abstract Mono<? extends DisposableServer> bind(TcpServer b);

	/**
	 * Materialize a TcpServer from the parent {@link HttpServer} chain to use with
	 * {@link #bind(TcpServer)} or separately
	 *
	 * @return a configured {@link TcpServer}
	 */
	protected TcpServer tcpConfiguration() {
		return DEFAULT_TCP_SERVER;
	}

	static final TcpServer DEFAULT_TCP_SERVER = TcpServer.create();

	static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(HttpServer.class);
	static final Logger         log             = Loggers.getLogger(HttpServer.class);

	static final Function<TcpServer, TcpServer> COMPRESS_ATTR_CONFIG =
			tcp -> tcp.bootstrap(HttpServerConfiguration.MAP_COMPRESS);

	static final Function<TcpServer, TcpServer> COMPRESS_ATTR_DISABLE =
			tcp -> tcp.bootstrap(HttpServerConfiguration.MAP_NO_COMPRESS);

	static final Function<TcpServer, TcpServer> FORWARD_ATTR_CONFIG =
			tcp -> tcp.bootstrap(HttpServerConfiguration.MAP_FORWARDED);

	static final Function<TcpServer, TcpServer> FORWARD_ATTR_DISABLE =
			tcp -> tcp.bootstrap(HttpServerConfiguration.MAP_NO_FORWARDED);
}
