/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.netty5.channel.group.ChannelGroup;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.ssl.JdkSslContext;
import io.netty5.handler.ssl.OpenSsl;
import io.netty5.handler.ssl.SslContext;
import io.netty5.handler.ssl.util.SelfSignedCertificate;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.channel.ChannelMetricsRecorder;
import reactor.netty5.http.Http2SettingsSpec;
import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.server.logging.AccessLog;
import reactor.netty5.http.server.logging.AccessLogArgProvider;
import reactor.netty5.http.server.logging.AccessLogFactory;
import reactor.netty5.internal.util.Metrics;
import reactor.netty5.tcp.SslProvider;
import reactor.netty5.transport.ServerTransport;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

import static reactor.netty5.ReactorNetty.format;

/**
 * An HttpServer allows building in a safe immutable way an HTTP server that is
 * materialized and connecting when {@link #bind()} is ultimately called.
 * <p>
 * <p>Examples:
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
 * @author Violeta Georgieva
 */
public abstract class HttpServer extends ServerTransport<HttpServer, HttpServerConfig> {

	/**
	 * Prepare an {@link HttpServer}
	 *
	 * @return a new {@link HttpServer}
	 */
	public static HttpServer create() {
		return HttpServerBind.INSTANCE;
	}

	/**
	 * Enable or disable the access log. If enabled, the default log system will be used.
	 * <p>
	 * Example:
	 * <pre>
	 * {@code
	 * HttpServer.create()
	 *           .port(8080)
	 *           .route(r -> r.get("/hello",
	 *                   (req, res) -> res.header(CONTENT_TYPE, TEXT_PLAIN)
	 *                                    .sendString(Mono.just("Hello World!"))))
	 *           .accessLog(true)
	 *           .bindNow()
	 *           .onDispose()
	 *           .block();
	 * }
	 * </pre>
	 * <p>
	 *
	 * Note that this method takes precedence over the {@value reactor.netty5.ReactorNetty#ACCESS_LOG_ENABLED} system property.
	 *
	 * @param enable enable or disable the access log
	 * @return a new {@link HttpServer}
	 * @since 1.0.3
	 */
	public final HttpServer accessLog(boolean enable) {
		HttpServer dup = duplicate();
		dup.configuration().accessLog = null;
		dup.configuration().accessLogEnabled = enable;
		return dup;
	}

	/**
	 * Enable or disable the access log and customize it through an {@link AccessLogFactory}.
	 * <p>
	 * Example:
	 * <pre>
	 * {@code
	 * HttpServer.create()
	 *           .port(8080)
	 *           .route(r -> r.get("/hello",
	 *                   (req, res) -> res.header(CONTENT_TYPE, TEXT_PLAIN)
	 *                                    .sendString(Mono.just("Hello World!"))))
	 *           .accessLog(true, AccessLogFactory.createFilter(
	 *                   args -> String.valueOf(args.uri()).startsWith("/health"),
	 *                   args -> AccessLog.create("user-agent={}", args.requestHeader("user-agent"))
	 *            )
	 *           .bindNow()
	 *           .onDispose()
	 *           .block();
	 * }
	 * </pre>
	 * <p>
	 * The {@link AccessLogFactory} class offers several helper methods to generate such a function,
	 * notably if one wants to {@link AccessLogFactory#createFilter(Predicate) filter} some requests out of the access log.
	 *
	 * Note that this method takes precedence over the {@value reactor.netty5.ReactorNetty#ACCESS_LOG_ENABLED} system property.
	 *
	 * @param enable enable or disable the access log
	 * @param accessLogFactory the {@link AccessLogFactory} that creates an {@link AccessLog} given an {@link AccessLogArgProvider}
	 * @return a new {@link HttpServer}
	 * @since 1.0.3
	 */
	public final HttpServer accessLog(boolean enable, AccessLogFactory accessLogFactory) {
		Objects.requireNonNull(accessLogFactory);
		HttpServer dup = duplicate();
		dup.configuration().accessLog = enable ? accessLogFactory : null;
		dup.configuration().accessLogEnabled = enable;
		return dup;
	}

	@Override
	public final HttpServer bindAddress(Supplier<? extends SocketAddress> bindAddressSupplier) {
		return super.bindAddress(bindAddressSupplier);
	}

	@Override
	public final HttpServer channelGroup(ChannelGroup channelGroup) {
		return super.channelGroup(channelGroup);
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
		HttpServer dup = duplicate();
		dup.configuration().compressPredicate = predicate;
		return dup;
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
		HttpServer dup = duplicate();
		if (compressionEnabled) {
			dup.configuration().minCompressionSize = 0;
		}
		else {
			dup.configuration().minCompressionSize = -1;
			dup.configuration().compressPredicate = null;
		}
		return dup;
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
		HttpServer dup = duplicate();
		dup.configuration().minCompressionSize = minResponseSize;
		return dup;
	}

	/**
	 * Specifies a custom request handler for deriving information about the connection.
	 *
	 * @param handler the forwarded header handler
	 * @return a new {@link HttpServer}
	 * @since 0.9.12
	 */
	public final HttpServer forwarded(BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> handler) {
		Objects.requireNonNull(handler, "handler");
		HttpServer dup = duplicate();
		dup.configuration().forwardedHeaderHandler = handler;
		return dup;
	}

	/**
	 * Specifies whether support for the {@code "Forwarded"} and {@code "X-Forwarded-*"}
	 * HTTP request headers for deriving information about the connection is enabled.
	 *
	 * @param forwardedEnabled if true support for the {@code "Forwarded"} and {@code "X-Forwarded-*"}
	 * HTTP request headers for deriving information about the connection is enabled,
	 * otherwise disabled.
	 * @return a new {@link HttpServer}
	 * @since 0.9.7
	 */
	public final HttpServer forwarded(boolean forwardedEnabled) {
		if (forwardedEnabled) {
			if (configuration().forwardedHeaderHandler == DefaultHttpForwardedHeaderHandler.INSTANCE) {
				return this;
			}
			HttpServer dup = duplicate();
			dup.configuration().forwardedHeaderHandler = DefaultHttpForwardedHeaderHandler.INSTANCE;
			return dup;
		}
		else if (configuration().forwardedHeaderHandler != null) {
			HttpServer dup = duplicate();
			dup.configuration().forwardedHeaderHandler = null;
			return dup;
		}
		return this;
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
	public final HttpServer handle(
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		return childObserve(new HttpServerHandle(handler));
	}

	@Override
	public final HttpServer host(String host) {
		return super.host(host);
	}

	/**
	 * Apply HTTP/2 configuration
	 *
	 * @param http2Settings configures {@link Http2SettingsSpec} before requesting
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer http2Settings(Consumer<Http2SettingsSpec.Builder> http2Settings) {
		Objects.requireNonNull(http2Settings, "http2Settings");
		Http2SettingsSpec.Builder builder = Http2SettingsSpec.builder();
		http2Settings.accept(builder);
		Http2SettingsSpec settings = builder.build();
		if (settings.equals(configuration().http2Settings)) {
			return this;
		}
		HttpServer dup = duplicate();
		dup.configuration().http2Settings = settings;
		return dup;
	}

	/**
	 * Apply HTTP form decoder configuration.
	 * The configuration is used when {@link HttpServerRequest#receiveForm()} is invoked.
	 * When a specific configuration per request is needed {@link HttpServerRequest#receiveForm(Consumer)}
	 * should be used.
	 *
	 * @param formDecoderBuilder {@link HttpServerFormDecoderProvider.Builder} for HTTP form decoder configuration
	 * @return a new {@link HttpServer}
	 * @since 1.0.11
	 */
	public final HttpServer httpFormDecoder(Consumer<HttpServerFormDecoderProvider.Builder> formDecoderBuilder) {
		Objects.requireNonNull(formDecoderBuilder, "formDecoderBuilder");
		HttpServerFormDecoderProvider.Build builder = new HttpServerFormDecoderProvider.Build();
		formDecoderBuilder.accept(builder);
		HttpServerFormDecoderProvider formDecoderProvider = builder.build();
		if (formDecoderProvider.equals(configuration().formDecoderProvider)) {
			return this;
		}
		HttpServer dup = duplicate();
		dup.configuration().formDecoderProvider = formDecoderProvider;
		return dup;
	}

	/**
	 * Configure the {@link io.netty5.handler.codec.http.HttpServerCodec}'s request decoding options.
	 *
	 * @param requestDecoderOptions a function to mutate the provided Http request decoder options
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer httpRequestDecoder(Function<HttpRequestDecoderSpec, HttpRequestDecoderSpec> requestDecoderOptions) {
		Objects.requireNonNull(requestDecoderOptions, "requestDecoderOptions");
		HttpRequestDecoderSpec decoder = requestDecoderOptions.apply(new HttpRequestDecoderSpec()).build();
		if (decoder.equals(configuration().decoder)) {
			return this;
		}
		HttpServer dup = duplicate();
		dup.configuration().decoder = decoder;
		return dup;
	}

	/**
	 * Specifies an idle timeout on the connection when it is waiting for an HTTP request (resolution: ms).
	 * Once the timeout is reached the connection will be closed.
	 * <p>If an {@code idleTimeout} is not specified, this indicates no timeout (i.e. infinite),
	 * which means the connection will be closed only if one of the peers decides to close it.
	 * <p>If the {@code idleTimeout} is less than {@code 1ms}, then {@code 1ms} will be the idle timeout.
	 * <p>By default {@code idleTimeout} is not specified.
	 *
	 * @param idleTimeout an idle timeout on the connection when it is waiting for an HTTP request (resolution: ms)
	 * @return a new {@link HttpServer}
	 * @since 0.9.15
	 */
	public final HttpServer idleTimeout(Duration idleTimeout) {
		Objects.requireNonNull(idleTimeout, "idleTimeout");
		HttpServer dup = duplicate();
		dup.configuration().idleTimeout = idleTimeout;
		return dup;
	}

	/**
	 * Decorate the configured I/O handler.
	 * See {@link #handle(BiFunction)}.
	 *
	 * @param mapHandle A {@link BiFunction} to decorate the configured I/O handler
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer mapHandle(BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle) {
		Objects.requireNonNull(mapHandle, "mapHandle");
		HttpServer dup = duplicate();
		dup.configuration().mapHandle = mapHandle;
		return dup;
	}

	/**
	 * The maximum number of HTTP/1.1 requests which can be served until the connection is closed by the server.
	 * Setting this attribute to:
	 * <ul>
	 *     <li><strong>-1</strong>: The connection serves unlimited number of requests. It is up to the I/O handler to decide
	 *     to close the connection. This is the default behaviour.</li>
	 *     <li><strong>1</strong>: The connection is marked as non persistent and serves just one request.</li>
	 *     <li><strong>&gt;1</strong>: The connection serves a number of requests up to the specified maximum number
	 *     then the connection is closed by the server.</li>
	 * </ul>
	 * @param maxKeepAliveRequests the maximum number of HTTP/1.1 requests which can be served until
	 * the connection is closed by the server
	 * @return a new {@link HttpServer}
	 * @since 1.0.13
	 */
	public final HttpServer maxKeepAliveRequests(int maxKeepAliveRequests) {
		if (maxKeepAliveRequests < -1 || maxKeepAliveRequests == 0) {
			throw new IllegalArgumentException("maxKeepAliveRequests must be positive or -1");
		}
		HttpServer dup = duplicate();
		dup.configuration().maxKeepAliveRequests = maxKeepAliveRequests;
		return dup;
	}

	/**
	 * Whether to enable metrics to be collected and registered in Micrometer's
	 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}
	 * under the name {@link reactor.netty5.Metrics#HTTP_SERVER_PREFIX}.
	 * <p>{@code uriTagValue} function receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty5.Metrics#URI} tag.
	 * For example instead of using the actual uri {@code "/users/1"} as uri tag value, templated uri
	 * {@code "/users/{id}"} can be used.
	 * <p><strong>Note:</strong>
	 * It is strongly recommended applications to configure an upper limit for the number of the URI tags.
	 * For example:
	 * <pre class="code">
	 * Metrics.globalRegistry
	 *        .config()
	 *        .meterFilter(MeterFilter.maximumAllowableTags(HTTP_SERVER_PREFIX, URI, 100, MeterFilter.deny()));
	 * </pre>
	 * <p>By default metrics are not enabled.
	 *
	 * @param enable true enables metrics collection; false disables it
	 * @param uriTagValue a function that receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty5.Metrics#URI} tag
	 * @return a new {@link HttpServer}
	 * @since 0.9.7
	 */
	public final HttpServer metrics(boolean enable, Function<String, String> uriTagValue) {
		if (enable) {
			if (!Metrics.isMicrometerAvailable() && !Metrics.isTracingAvailable()) {
				throw new UnsupportedOperationException(
						"To enable metrics, you must add the dependencies to `io.micrometer:micrometer-core`" +
								" and `io.micrometer:micrometer-tracing` to the class path first");
			}
			HttpServer dup = duplicate();
			dup.configuration().metricsRecorder(() -> configuration().defaultMetricsRecorder());
			dup.configuration().uriTagValue = uriTagValue;
			return dup;
		}
		else if (configuration().metricsRecorder() != null) {
			HttpServer dup = duplicate();
			dup.configuration().metricsRecorder(null);
			dup.configuration().uriTagValue = null;
			return dup;
		}
		else {
			return this;
		}
	}

	@Override
	public final HttpServer metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder) {
		return super.metrics(enable, recorder);
	}

	/**
	 * Specifies whether the metrics are enabled on the {@link HttpServer}.
	 * All generated metrics are provided to the specified recorder which is only
	 * instantiated if metrics are being enabled (the instantiation is not lazy,
	 * but happens immediately, while configuring the {@link HttpServer}).
	 * <p>{@code uriValue} function receives the actual uri and returns the uri value
	 * that will be used when the metrics are propagated to the recorder.
	 * For example instead of using the actual uri {@code "/users/1"} as uri value, templated uri
	 * {@code "/users/{id}"} can be used.
	 *
	 * @param enable true enables metrics collection; false disables it
	 * @param recorder a supplier for the metrics recorder that receives the collected metrics
	 * @param uriValue a function that receives the actual uri and returns the uri value
	 * that will be used when the metrics are propagated to the recorder.
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder, Function<String, String> uriValue) {
		if (enable) {
			HttpServer dup = duplicate();
			dup.configuration().metricsRecorder(recorder);
			dup.configuration().uriTagValue = uriValue;
			return dup;
		}
		else if (configuration().metricsRecorder() != null) {
			HttpServer dup = duplicate();
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
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer noSSL() {
		if (configuration().isSecure()) {
			HttpServer dup = duplicate();
			dup.configuration().sslProvider = null;
			return dup;
		}
		return this;
	}

	@Override
	public final HttpServer port(int port) {
		return super.port(port);
	}

	/**
	 * The HTTP protocol to support. Default is {@link HttpProtocol#HTTP11}.
	 *
	 * @param supportedProtocols The various {@link HttpProtocol} this server will support
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer protocol(HttpProtocol... supportedProtocols) {
		Objects.requireNonNull(supportedProtocols, "supportedProtocols");
		HttpServer dup = duplicate();
		dup.configuration().protocols(supportedProtocols);
		return dup;
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
		Objects.requireNonNull(proxyProtocolSupportType, "The parameter: proxyProtocolSupportType must not be null.");
		if (proxyProtocolSupportType == configuration().proxyProtocolSupportType) {
			return this;
		}
		if (proxyProtocolSupportType == ProxyProtocolSupportType.ON ||
					proxyProtocolSupportType == ProxyProtocolSupportType.AUTO) {
			if (!HAProxyMessageReader.isProxyProtocolAvailable()) {
				throw new UnsupportedOperationException(
						"To enable proxyProtocol, you must add the dependency `io.netty:netty-codec-haproxy`" +
								" to the class path first");
			}
		}
		HttpServer dup = duplicate();
		dup.configuration().proxyProtocolSupportType = proxyProtocolSupportType;
		return dup;
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
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@code 10} seconds handshake timeout unless the environment property {@code
	 * reactor.netty5.tcp.sslHandshakeTimeout} is set.
	 *
	 * If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * <pre>
	 * {@code
	 *     SelfSignedCertificate cert = new SelfSignedCertificate();
	 *     Http11SslContextSpec http11SslContextSpec =
	 *             Http11SslContextSpec.forServer(cert.certificate(), cert.privateKey());
	 *     secure(sslContextSpec -> sslContextSpec.sslContext(http11SslContextSpec));
	 * }
	 * </pre>
	 *
	 * @param sslProviderBuilder builder callback for further customization of SslContext.
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		return secure(sslProviderBuilder, false);
	}

	/**
	 * Apply an SSL configuration customization via the passed builder. The builder
	 * will produce the {@link SslContext} to be passed to with a default value of
	 * {@code 10} seconds handshake timeout unless the environment property {@code
	 * reactor.netty5.tcp.sslHandshakeTimeout} is set.
	 * <p>
	 * If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * <pre>
	 * {@code
	 *     SelfSignedCertificate cert = new SelfSignedCertificate();
	 *     Http11SslContextSpec http11SslContextSpec =
	 *             Http11SslContextSpec.forServer(cert.certificate(), cert.privateKey());
	 *     secure(sslContextSpec -> sslContextSpec.sslContext(http11SslContextSpec), true);
	 * }
	 * </pre>
	 *
	 * @param sslProviderBuilder  builder callback for further customization of SslContext.
	 * @param redirectHttpToHttps true enables redirecting HTTP to HTTPS by changing the
	 *                            scheme only but otherwise leaving the port the same.
	 *                            This configuration is applicable only for HTTP 1.x.
	 * @return a new {@link HttpServer}
	 * @since 1.0.5
	 */
	public final HttpServer secure(Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder, boolean redirectHttpToHttps) {
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");
		HttpServer dup = duplicate();
		SslProvider.SslContextSpec builder = SslProvider.builder();
		sslProviderBuilder.accept(builder);
		dup.configuration().sslProvider = ((SslProvider.Builder) builder).build();
		dup.configuration().redirectHttpToHttps = redirectHttpToHttps;
		return dup;
	}

	/**
	 * Applies an SSL configuration via the passed {@link SslProvider}.
	 *
	 * If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * <pre>
	 * {@code
	 *     SelfSignedCertificate cert = new SelfSignedCertificate();
	 *     Http11SslContextSpec http11SslContextSpec =
	 *             Http11SslContextSpec.forServer(cert.certificate(), cert.privateKey());
	 *     secure(sslContextSpec -> sslContextSpec.sslContext(http11SslContextSpec));
	 * }
	 * </pre>
	 *
	 * @param sslProvider The provider to set when configuring SSL
	 *
	 * @return a new {@link HttpServer}
	 */
	public final HttpServer secure(SslProvider sslProvider) {
		return secure(sslProvider, false);
	}

	/**
	 * Applies an SSL configuration via the passed {@link SslProvider}.
	 * <p>
	 * If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * <pre>
	 * {@code
	 *     SelfSignedCertificate cert = new SelfSignedCertificate();
	 *     Http11SslContextSpec http11SslContextSpec =
	 *             Http11SslContextSpec.forServer(cert.certificate(), cert.privateKey());
	 *     secure(sslContextSpec -> sslContextSpec.sslContext(http11SslContextSpec), true);
	 * }
	 * </pre>
	 *
	 * @param sslProvider         The provider to set when configuring SSL
	 * @param redirectHttpToHttps true enables redirecting HTTP to HTTPS by changing the
	 *                            scheme only but otherwise leaving the port the same.
	 *                            This configuration is applicable only for HTTP 1.x.
	 * @return a new {@link HttpServer}
	 * @since 1.0.5
	 */
	public final HttpServer secure(SslProvider sslProvider, boolean redirectHttpToHttps) {
		Objects.requireNonNull(sslProvider, "sslProvider");
		HttpServer dup = duplicate();
		dup.configuration().sslProvider = sslProvider;
		dup.configuration().redirectHttpToHttps = redirectHttpToHttps;
		return dup;
	}

	/**
	 * Based on the actual configuration, returns a {@link Mono} that triggers:
	 * <ul>
	 *     <li>an initialization of the event loop groups</li>
	 *     <li>loads the necessary native libraries for the transport</li>
	 *     <li>loads the necessary native libraries for the security if there is such</li>
	 * </ul>
	 * By default, when method is not used, the {@code bind operation} absorbs the extra time needed to load resources.
	 *
	 * @return a {@link Mono} representing the completion of the warmup
	 * @since 1.0.3
	 */
	@Override
	public Mono<Void> warmup() {
		return Mono.when(
				super.warmup(),
				Mono.fromRunnable(() -> {
					SslProvider provider = configuration().sslProvider();
					if (provider != null && !(provider.getSslContext() instanceof JdkSslContext)) {
						OpenSsl.version();
					}
				}));
	}

	@Override
	public final HttpServer wiretap(boolean enable) {
		return super.wiretap(enable);
	}

	static final Logger log = Loggers.getLogger(HttpServer.class);

	static final class HttpServerHandle implements ConnectionObserver {

		final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler;

		HttpServerHandle(BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
			this.handler = handler;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == HttpServerState.REQUEST_RECEIVED) {
				try {
					if (log.isDebugEnabled()) {
						log.debug(format(connection.channel(), "Handler is being applied: {}"), handler);
					}
					HttpServerOperations ops = (HttpServerOperations) connection;
					Publisher<Void> publisher = handler.apply(ops, ops);
					Mono<Void> mono = Mono.deferContextual(ctx -> {
						ops.currentContext = Context.of(ctx);
						return Mono.fromDirect(publisher);
					});
					if (ops.mapHandle != null) {
						mono = ops.mapHandle.apply(mono, connection);
					}
					mono.subscribe(ops.disposeSubscriber());
				}
				catch (Throwable t) {
					log.error(format(connection.channel(), ""), t);
					connection.channel()
					          .close();
				}
			}
		}
	}
}
