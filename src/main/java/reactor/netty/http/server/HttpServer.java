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

package reactor.netty.http.server;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.http.HttpProtocol;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpServer;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.Metrics;

/**
 * An HttpServer allows to build in a safe immutable way an HTTP server that is
 * materialized and connecting when {@link #bind(TcpServer)} is ultimately called.
 * <p>Internally, materialization happens in two phases:</p>
 * <ul>
 * <li>first {@link #tcpConfiguration()} is called to retrieve a ready to use {@link TcpServer}</li>
 * <li>then {@link #bind(TcpServer)} is called</li>
 * </ul>
 * <p>Examples:</p>
 * <pre>
 * {@code
 * HttpServer.create()
 *           .host("0.0.0.0")
 *           .handle((req, res) -> res.sendString(Flux.just("hello")))
 *           .bind()
 *           .block();
 * }
 * </pre>
 *
 * @author Stephane Maldini
 */
public abstract class HttpServer {

	/**
	 * Prepare an {@link HttpServer}
	 *
	 * @return a new {@link HttpServer}
	 */
	public static HttpServer create() {
		return HttpServerBind.INSTANCE;
	}

	/**
	 * Prepare an {@link HttpServer}
	 *
	 * @return a new {@link HttpServer}
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
	 * Start the server in a blocking fashion, and wait for it to finish initializing
	 * or the startup timeout expires (the startup timeout is {@code 45} seconds). The
	 * returned {@link DisposableServer} offers simple server API, including to {@link
	 * DisposableServer#disposeNow()} shut it down in a blocking fashion.
	 *
	 * @return a {@link DisposableServer}
	 */
	public final DisposableServer bindNow() {
		return bindNow(Duration.ofSeconds(45));
	}


	/**
	 * Start the server in a blocking fashion, and wait for it to finish initializing
	 * or the provided startup timeout expires. The returned {@link DisposableServer}
	 * offers simple server API, including to {@link DisposableServer#disposeNow()}
	 * shut it down in a blocking fashion.
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
	 * Start the server in a fully blocking fashion, not only waiting for it to initialize
	 * but also blocking during the full lifecycle of the server. Since most
	 * servers will be long-lived, this is more adapted to running a server out of a main
	 * method, only allowing shutdown of the servers through {@code sigkill}.
	 * <p>
	 * Note: {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} is added by
	 * this method in order to properly disconnect the server upon receiving a
	 * {@code sigkill} signal.
	 * </p>
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
	 * Specifies whether GZip response compression is enabled if the client request
	 * presents accept encoding.
	 *
	 * @param compressionEnabled if true GZip response compression
	 * is enabled if the client request presents accept encoding, otherwise disabled.
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer compress(boolean compressionEnabled) {
		if (compressionEnabled) {
			return tcpConfiguration(COMPRESS_ATTR_CONFIG);
		}
		else {
			return tcpConfiguration(COMPRESS_ATTR_DISABLE);
		}
	}

	/**
	 * Enable GZip response compression if the client request presents accept encoding
	 * headers AND the response reaches a minimum threshold
	 *
	 * @param minResponseSize compression is performed once response size exceeds the given
	 * value in bytes
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
	 * headers and the provided {@link java.util.function.Predicate} matches.
	 * <p>
	 * Note: the passed {@link HttpServerRequest} and {@link HttpServerResponse}
	 * should be considered read-only and the implement SHOULD NOT consume or
	 * write the request/response in this predicate.
	 * </p>
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
	 * Specifies whether support for the {@code "Forwarded"} and {@code "X-Forwarded-*"}
	 * HTTP request headers for deriving information about the connection is enabled.
	 *
	 * @param forwardedEnabled if true support for the {@code "Forwarded"} and {@code "X-Forwarded-*"}
	 *                         HTTP request headers for deriving information about the connection is enabled,
	 *                         otherwise disabled.
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer forwarded(boolean forwardedEnabled) {
		if (forwardedEnabled) {
			return tcpConfiguration(FORWARD_ATTR_CONFIG);
		}
		else {
			return tcpConfiguration(FORWARD_ATTR_DISABLE);
		}
	}

	/**
	 * Specifies whether support for the {@code "HAProxy proxy protocol"}
	 * for deriving information about the address of the remote peer is enabled.
	 *
	 * @param proxyProtocolSupportType
	 *      <ul>
	 *         <li>
	 *             choose {@link ProxyProtocolSupportType#ON}
	 *             to enable support for the {@code "HAProxy proxy protocol"}
	 *             for deriving information about the address of the remote peer.
	 *         </li>
	 *         <li>choose {@link ProxyProtocolSupportType#OFF} to disable the proxy protocol support.</li>
	 *         <li>
	 *             choose {@link ProxyProtocolSupportType#AUTO}
	 *             then each connection of the same {@link HttpServer} will auto detect whether there is proxy protocol,
	 *             so {@link HttpServer} can accept requests with or without proxy protocol at the same time.
	 *         </li>
	 *      </ul>
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer proxyProtocol(ProxyProtocolSupportType proxyProtocolSupportType) {
		if (proxyProtocolSupportType == null) {
			throw new NullPointerException("The parameter: proxyProtocolSupportType must not be null.");
		}

		if (proxyProtocolSupportType == ProxyProtocolSupportType.ON ||
				proxyProtocolSupportType == ProxyProtocolSupportType.AUTO) {
			if (!HAProxyMessageReader.hasProxyProtocol()) {
				throw new UnsupportedOperationException(
				        "To enable proxyProtocol, you must add the dependency `io.netty:netty-codec-haproxy`" +
				                " to the class path first");
			}

			return tcpConfiguration(tcpServer ->
			        tcpServer.bootstrap(b -> BootstrapHandlers.updateConfiguration(b,
			                NettyPipeline.ProxyProtocolDecoder,
			                (connectionObserver, channel) -> {
			                    if (proxyProtocolSupportType == ProxyProtocolSupportType.ON) {
			                        channel.pipeline()
			                               .addFirst(NettyPipeline.ProxyProtocolDecoder, new HAProxyMessageDecoder())
			                               .addAfter(NettyPipeline.ProxyProtocolDecoder,
			                                         NettyPipeline.ProxyProtocolReader, new HAProxyMessageReader());
			                    }
			                    else { // AUTO
			                        channel.pipeline()
			                               .addFirst(NettyPipeline.ProxyProtocolDecoder, new HAProxyMessageDetector());
			                    }
			                })));
		}
		else if (proxyProtocolSupportType == ProxyProtocolSupportType.OFF) {
			return tcpConfiguration(tcpServer ->
			        tcpServer.bootstrap(b -> BootstrapHandlers.removeConfiguration(b, NettyPipeline.ProxyProtocolDecoder)));
		}
		else {
			//Will never be here
			throw new IllegalArgumentException("Invalid proxyProtocolSupportType");
		}
	}

	/**
	 * Whether to enable metrics to be collected and registered in Micrometer's
	 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}
	 * under the name {@link reactor.netty.Metrics#HTTP_SERVER_PREFIX}. Applications can
	 * separately register their own
	 * {@link io.micrometer.core.instrument.config.MeterFilter filters} associated with this name.
	 * For example, to put an upper bound on the number of tags produced:
	 * <pre class="code">
	 * MeterFilter filter = ... ;
	 * Metrics.globalRegistry.config().meterFilter(MeterFilter.maximumAllowableTags(HTTP_SERVER_PREFIX, 100, filter));
	 * </pre>
	 * <p>By default this is not enabled.
	 *
	 * @param metricsEnabled true enables metrics collection; false disables it
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer metrics(boolean metricsEnabled) {
		if (metricsEnabled) {
			if (!Metrics.isInstrumentationAvailable()) {
				throw new UnsupportedOperationException(
						"To enable metrics, you must add the dependency `io.micrometer:micrometer-core`" +
								" to the class path first");
			}

			return tcpConfiguration(tcpServer ->
					tcpServer.bootstrap(b -> BootstrapHandlers.updateMetricsSupport(b,
							MicrometerHttpServerMetricsRecorder.INSTANCE)));
		}
		else {
			return tcpConfiguration(tcpServer -> tcpServer.bootstrap(BootstrapHandlers::removeMetricsSupport));
		}
	}

	/**
	 * Specifies whether the metrics are enabled on the {@link HttpServer}.
	 * All generated metrics are provided to the specified recorder.
	 *
	 * @param metricsEnabled if true enables the metrics on the server.
	 * @param recorder the {@link HttpServerMetricsRecorder}
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer metrics(boolean metricsEnabled, HttpServerMetricsRecorder recorder) {
		return tcpConfiguration(tcpServer -> tcpServer.metrics(metricsEnabled, recorder));
	}

	/**
	 * The host to which this server should bind.
	 * By default the server will listen on any local address.
	 *
	 * @param host The host to bind to.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer host(String host) {
		return tcpConfiguration(tcpServer -> tcpServer.host(host));
	}

	/**
	 * Attach an I/O handler to react on a connected client
	 *
	 * @param handler an I/O handler that can dispose underlying connection when {@link
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
	 * Configure the
	 * {@link ServerCookieEncoder}; {@link ServerCookieDecoder} will be
	 * chosen based on the encoder
	 *
	 * @param encoder the preferred ServerCookieEncoder
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer cookieCodec(ServerCookieEncoder encoder) {
		ServerCookieDecoder decoder = encoder == ServerCookieEncoder.LAX ?
				ServerCookieDecoder.LAX : ServerCookieDecoder.STRICT;
		return tcpConfiguration(tcp -> tcp.bootstrap(
				b -> HttpServerConfiguration.cookieCodec(b, encoder, decoder)));
	}

	/**
	 * Configure the
	 * {@link ServerCookieEncoder} and {@link ServerCookieDecoder}
	 *
	 * @param encoder the preferred ServerCookieEncoder
	 * @param decoder the preferred ServerCookieDecoder
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer cookieCodec(ServerCookieEncoder encoder, ServerCookieDecoder decoder) {
		return tcpConfiguration(tcp -> tcp.bootstrap(
				b -> HttpServerConfiguration.cookieCodec(b, encoder, decoder)));
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
	 * The HTTP protocol to support. Default is {@link HttpProtocol#HTTP11}.
	 *
	 * @param supportedProtocols The various {@link HttpProtocol} this server will support
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer protocol(HttpProtocol... supportedProtocols) {
		return tcpConfiguration(tcpServer -> tcpServer.bootstrap(b -> HttpServerConfiguration.protocols(b, supportedProtocols)));
	}

	/**
	 * The port to which this server should bind.
	 * By default the system will pick up an ephemeral port in the {@link #bind()} operation:
	 *
	 * @param port The port to bind to.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer port(int port) {
		return tcpConfiguration(tcpServer -> tcpServer.port(port));
	}

	/**
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@code 10} seconds handshake timeout unless the environment property {@code
	 * reactor.netty.tcp.sslHandshakeTimeout} is set.
	 *
	 * If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * <pre>
	 * {@code
	 *     SelfSignedCertificate cert = new SelfSignedCertificate();
	 *     SslContextBuilder sslContextBuilder =
	 *             SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
	 *     secure(sslContextSpec -> sslContextSpec.sslContext(sslContextBuilder));
	 * }
	 * </pre>
	 *
	 * @param sslProviderBuilder builder callback for further customization of {@link SslContext}.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		return new HttpServerSecure(this, sslProviderBuilder);
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
	 * <p>Configuration will apply during {@link #tcpConfiguration()} phase.</p>
	 *
	 * @param tcpMapper A {@link TcpServer} mapping function to update tcp configuration and
	 * return an enriched TCP server to use.
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer tcpConfiguration(Function<? super TcpServer, ? extends TcpServer> tcpMapper) {
		return new HttpServerTcpConfig(this, tcpMapper);
	}

	/**
	 * Apply or remove a wire logger configuration using {@link HttpServer} category
	 * and {@code DEBUG} logger level
	 *
	 * @param enable Specifies whether the wire logger configuration will be added to
	 *               the pipeline
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer wiretap(boolean enable) {
		if (enable) {
			return tcpConfiguration(tcpServer ->
			        tcpServer.bootstrap(b -> BootstrapHandlers.updateLogSupport(b, LOGGING_HANDLER)));
		}
		else {
			return tcpConfiguration(tcpServer ->
			        tcpServer.bootstrap(b -> BootstrapHandlers.removeConfiguration(b, NettyPipeline.LoggingHandler)));
		}
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
	 * Materialize a {@link TcpServer} from the parent {@link HttpServer} chain to use with
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
