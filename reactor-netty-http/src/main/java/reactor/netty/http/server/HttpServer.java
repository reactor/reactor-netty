/*
 * Copyright (c) 2011-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.http.Http2SettingsSpec;
import reactor.netty.http.Http3SettingsSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.netty.http.logging.ReactorNettyHttpMessageLogFactory;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.http.server.logging.AccessLogArgProvider;
import reactor.netty.http.server.logging.AccessLogFactory;
import reactor.netty.internal.util.Metrics;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpServer;
import reactor.netty.transport.ServerTransport;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Incubating;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.internal.Http3.isHttp3Available;
import static reactor.netty.http.server.HttpServerConfig.h3;

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
	 * Prepare an {@link HttpServer}.
	 *
	 * @return a new {@link HttpServer}
	 */
	public static HttpServer create() {
		return HttpServerBind.INSTANCE;
	}

	/**
	 * Prepare an {@link HttpServer}
	 * <p>
	 * <strong>Note:</strong>
	 * There isn't only one method that replaces this deprecated method.
	 * The configuration that can be done with this deprecated method,
	 * can also be done with the other methods exposed by {@link HttpServer}.
	 * </p>
	 * <p>Examples:</p>
	 * <p>Configuration via the deprecated '.from(...)' method</p>
	 * <pre>
	 * {@code
	 * HttpServer.from(
	 *     TcpServer.attr(...) // configures the channel attributes
	 *              .bindAddress(...) // configures the bind (local) address
	 *              .childAttr(...) // configures the child channel attributes
	 *              .childObserve() // configures the child channel connection observer
	 *              .childOption(...) // configures the child channel options
	 *              .channelGroup(...) // configures the channel group
	 *              .doOnBound(...) // configures the doOnBound callback
	 *              .doOnChannelInit(...) // configures the channel handler
	 *              .doOnConnection(...) // configures the doOnConnection callback
	 *              .doOnUnbound(...) // configures the doOnUnbound callback
	 *              .metrics(...) // configures the metrics
	 *              .observe() // configures the connection observer
	 *              .option(...) // configures the channel options
	 *              .runOn(...) // configures the event loop group
	 *              .secure() // configures the SSL
	 *              .wiretap()) // configures the wire logging
	 * }
	 * </pre>
	 *
	 * <p>Configuration via the other methods exposed by {@link HttpServer}</p>
	 * <pre>
	 * {@code
	 * HttpServer.attr(...) // configures the channel attributes
	 *           .bindAddress(...) // configures the bind (local) address
	 *           .childAttr(...) // configures the child channel attributes
	 *           .childObserve() // configures the child channel connection observer
	 *           .childOption(...) // configures the child channel options
	 *           .channelGroup(...) // configures the channel group
	 *           .doOnBound(...) // configures the doOnBound callback
	 *           .doOnChannelInit(...) // configures the channel handler
	 *           .doOnConnection(...) // configures the doOnConnection callback
	 *           .doOnUnbound(...) // configures the doOnUnbound callback
	 *           .metrics(...) // configures the metrics
	 *           .observe() // configures the connection observer
	 *           .option(...) // configures the channel options
	 *           .runOn(...) // configures the event loop group
	 *           .secure() // configures the SSL
	 *           .wiretap() // configures the wire logging
	 * }
	 * </pre>
	 *
	 * <p>Wire logging in plain text</p>
	 * <pre>
	 * {@code
	 * HttpServer.wiretap("logger", LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL)
	 * }
	 * </pre>
	 *
	 * @return a new {@link HttpServer}
	 * @deprecated Use the other methods exposed by {@link HttpServer} to achieve the same configurations.
	 * This method will be removed in version 1.1.0.
	 */
	@Deprecated
	public static HttpServer from(TcpServer tcpServer) {
		Objects.requireNonNull(tcpServer, "tcpServer");
		return HttpServerBind.applyTcpServerConfig(tcpServer.configuration());
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
	 * Note that this method takes precedence over the {@value reactor.netty.ReactorNetty#ACCESS_LOG_ENABLED} system property.
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
	 * Note that this method takes precedence over the {@value reactor.netty.ReactorNetty#ACCESS_LOG_ENABLED} system property.
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

	/**
	 * Customize the access log, provided access logging has been enabled through the
	 * {@value reactor.netty.ReactorNetty#ACCESS_LOG_ENABLED} system property.
	 * <p>
	 * Example:
	 * <pre>
	 * {@code
	 * HttpServer.create()
	 *           .port(8080)
	 *           .route(r -> r.get("/hello",
	 *                   (req, res) -> res.header(CONTENT_TYPE, TEXT_PLAIN)
	 *                                    .sendString(Mono.just("Hello World!"))))
	 *           .accessLog(argProvider ->
	 *                   AccessLog.create("user-agent={}", argProvider.requestHeader("user-agent")))
	 *           .bindNow()
	 *           .onDispose()
	 *           .block();
	 * }
	 * </pre>
	 * <p>
	 *
	 * @param accessLogFactory the {@link Function} that creates an {@link AccessLog} given an {@link AccessLogArgProvider}
	 * @return a new {@link HttpServer}
	 * @since 1.0.1
	 * @deprecated as of 1.0.3. Prefer the {@link #accessLog(boolean, AccessLogFactory) variant}
	 * with the {@link AccessLogFactory} interface instead. This method will be removed in version 1.2.0.
	 */
	@Deprecated
	public final HttpServer accessLog(Function<AccessLogArgProvider, AccessLog> accessLogFactory) {
		Objects.requireNonNull(accessLogFactory, "accessLogFactory");
		HttpServer dup = duplicate();
		dup.configuration().accessLog = accessLogFactory;
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
	 * headers AND the response reaches a minimum threshold.
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
	 * Configure the
	 * {@link ServerCookieEncoder}; {@link ServerCookieDecoder} will be
	 * chosen based on the encoder.
	 *
	 * @param encoder the preferred ServerCookieEncoder
	 *
	 * @return a new {@link HttpServer}
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 supports only strict validation.
	 */
	@Deprecated
	public final HttpServer cookieCodec(ServerCookieEncoder encoder) {
		Objects.requireNonNull(encoder, "encoder");
		ServerCookieDecoder decoder = encoder == ServerCookieEncoder.LAX ?
				ServerCookieDecoder.LAX : ServerCookieDecoder.STRICT;
		HttpServer dup = duplicate();
		dup.configuration().cookieEncoder = encoder;
		dup.configuration().cookieDecoder = decoder;
		return dup;
	}

	/**
	 * Configure the
	 * {@link ServerCookieEncoder} and {@link ServerCookieDecoder}.
	 *
	 * @param encoder the preferred ServerCookieEncoder
	 * @param decoder the preferred ServerCookieDecoder
	 *
	 * @return a new {@link HttpServer}
	 * @deprecated as of 1.1.0. This will be removed in 2.0.0 as Netty 5 supports only strict validation.
	 */
	@Deprecated
	public final HttpServer cookieCodec(ServerCookieEncoder encoder, ServerCookieDecoder decoder) {
		Objects.requireNonNull(encoder, "encoder");
		Objects.requireNonNull(decoder, "decoder");
		HttpServer dup = duplicate();
		dup.configuration().cookieEncoder = encoder;
		dup.configuration().cookieDecoder = decoder;
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
	 * Attach an I/O handler to react on a connected client.
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
	 * Apply HTTP/2 configuration.
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
	 * Apply HTTP/3 configuration.
	 *
	 * @param http3Settings configures {@link Http3SettingsSpec} before requesting
	 * @return a new {@link HttpServer}
	 * @since 1.2.0
	 */
	@Incubating
	public final HttpServer http3Settings(Consumer<Http3SettingsSpec.Builder> http3Settings) {
		Objects.requireNonNull(http3Settings, "http3Settings");
		if (!isHttp3Available()) {
			throw new UnsupportedOperationException(
					"To enable HTTP/3 support, you must add the dependency `io.netty.incubator:netty-incubator-codec-http3`" +
							" to the class path first");
		}
		Http3SettingsSpec.Builder builder = Http3SettingsSpec.builder();
		http3Settings.accept(builder);
		Http3SettingsSpec settings = builder.build();
		if (settings.equals(configuration().http3Settings)) {
			return this;
		}
		HttpServer dup = duplicate();
		dup.configuration().http3Settings = settings;
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
	 * @return a new {@link HttpServer}
	 * @since 1.0.24
	 */
	public final HttpServer httpMessageLogFactory(HttpMessageLogFactory httpMessageLogFactory) {
		Objects.requireNonNull(httpMessageLogFactory, "httpMessageLogFactory");
		HttpServer dup = duplicate();
		dup.configuration().httpMessageLogFactory = httpMessageLogFactory;
		return dup;
	}

	/**
	 * Configure the {@link io.netty.handler.codec.http.HttpServerCodec}'s request decoding options.
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
	 * under the name {@link reactor.netty.Metrics#HTTP_SERVER_PREFIX}.
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
	 *        .meterFilter(MeterFilter.maximumAllowableTags(HTTP_SERVER_PREFIX, URI, 100, MeterFilter.deny()));
	 * </pre>
	 * <p>By default metrics are not enabled.
	 *
	 * @param enable true enables metrics collection; false disables it
	 * @param uriTagValue a function that receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#URI} tag
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
			if (uriTagValue == Function.<String>identity()) {
				log.debug("Metrics are enabled with [uriTagValue=Function#identity]. " +
						"It is strongly recommended to provide template-like form for the URIs. " +
						"Without a conversion to a template-like form, each distinct URI leads " +
						"to the creation of a distinct tag, which takes a lot of memory for the metrics.");
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

	/**
	 * Whether to enable metrics to be collected and registered in Micrometer's
	 * {@link io.micrometer.core.instrument.Metrics#globalRegistry globalRegistry}
	 * under the name {@link reactor.netty.Metrics#HTTP_SERVER_PREFIX}.
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
	 *        .meterFilter(MeterFilter.maximumAllowableTags(HTTP_SERVER_PREFIX, URI, 100, MeterFilter.deny()));
	 * </pre>
	 * <p>{@code methodTagValue} function receives the actual method name and returns the method tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#METHOD} tag.
	 * <p>By default metrics are not enabled.
	 *
	 * @param enable true enables metrics collection; false disables it
	 * @param uriTagValue a function that receives the actual uri and returns the uri tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#URI} tag
	 * @param methodTagValue a function that receives the actual method name and returns the method tag value
	 * that will be used for the metrics with {@link reactor.netty.Metrics#METHOD} tag
	 * @return a new {@link HttpServer}
	 * @since 1.0.39
	 */
	public final HttpServer metrics(boolean enable, Function<String, String> uriTagValue, Function<String, String> methodTagValue) {
		if (enable) {
			Objects.requireNonNull(methodTagValue, "methodTagValue");
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
			if (methodTagValue == Function.<String>identity()) {
				log.debug("Metrics are enabled with [methodTagValue=Function#identity]. " +
						"It is strongly recommended to provide a function for transforming the method names.");
			}
			HttpServer dup = duplicate();
			dup.configuration().metricsRecorder(() -> configuration().defaultMetricsRecorder());
			dup.configuration().methodTagValue = methodTagValue;
			dup.configuration().uriTagValue = uriTagValue;
			return dup;
		}
		else if (configuration().metricsRecorder() != null) {
			HttpServer dup = duplicate();
			dup.configuration().metricsRecorder(null);
			dup.configuration().methodTagValue = null;
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
	 * Specifies whether the metrics are enabled on the {@link HttpServer}.
	 * All generated metrics are provided to the specified recorder which is only
	 * instantiated if metrics are being enabled (the instantiation is not lazy,
	 * but happens immediately, while configuring the {@link HttpServer}).
	 * <p>{@code uriValue} function receives the actual uri and returns the uri value
	 * that will be used when the metrics are propagated to the recorder.
	 * For example instead of using the actual uri {@code "/users/1"} as uri value, templated uri
	 * {@code "/users/{id}"} can be used.
	 * <p>{@code methodValue} function receives the actual method name and returns the method value
	 * that will be used when the metrics are propagated to the recorder.
	 *
	 * @param enable true enables metrics collection; false disables it
	 * @param recorder a supplier for the metrics recorder that receives the collected metrics
	 * @param uriValue a function that receives the actual uri and returns the uri value
	 * that will be used when the metrics are propagated to the recorder.
	 * @param methodValue a function that receives the actual method name and returns the method value
	 * that will be used when the metrics are propagated to the recorder.
	 * @return a new {@link HttpServer}
	 * @since 1.0.39
	 */
	public final HttpServer metrics(boolean enable, Supplier<? extends ChannelMetricsRecorder> recorder,
			Function<String, String> uriValue, Function<String, String> methodValue) {
		if (enable) {
			Objects.requireNonNull(methodValue, "methodTagValue");
			HttpServer dup = duplicate();
			dup.configuration().metricsRecorder(recorder);
			dup.configuration().methodTagValue = methodValue;
			dup.configuration().uriTagValue = uriValue;
			return dup;
		}
		else if (configuration().metricsRecorder() != null) {
			HttpServer dup = duplicate();
			dup.configuration().metricsRecorder(null);
			dup.configuration().methodTagValue = null;
			dup.configuration().uriTagValue = null;
			return dup;
		}
		else {
			return this;
		}
	}

	/**
	 * Removes any previously applied SSL configuration customization.
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

	/**
	 * The port to which this server should bind.
	 * If a port is not specified, the system picks up an ephemeral port.
	 *
	 * @param port The port to bind to.
	 * @return a new {@link HttpServer}
	 */
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
		if ((dup.configuration()._protocols & h3) == h3 && !isHttp3Available()) {
			throw new UnsupportedOperationException(
					"To enable HTTP/3 support, you must add the dependency `io.netty.incubator:netty-incubator-codec-http3`" +
							" to the class path first");
		}
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
	 * Specifies the maximum duration allowed between each network-level read operation while reading a given request
	 * content (resolution: ms). In other words, {@link io.netty.handler.timeout.ReadTimeoutHandler} is added to the
	 * channel pipeline after all the request headers are received, and removed from the channel pipeline after the
	 * content is fully received.
	 * If the {@code readTimeout} is {@code null}, any previous setting will be removed and no
	 * {@code readTimeout} will be applied.
	 * If the {@code readTimeout} is less than {@code 1ms}, then {@code 1ms} will be the
	 * {@code readTimeout}.
	 *
	 * @param readTimeout the maximum duration allowed between each network-level read operation while reading a given
	 * request content (resolution: ms)
	 * @return a new {@link HttpServer}
	 * @since 1.1.9
	 * @see io.netty.handler.timeout.ReadTimeoutHandler
	 */
	public final HttpServer readTimeout(@Nullable Duration readTimeout) {
		if (Objects.equals(readTimeout, configuration().readTimeout)) {
			return this;
		}
		HttpServer dup = duplicate();
		dup.configuration().readTimeout = readTimeout;
		return dup;
	}

	/**
	 * Specifies the maximum duration for reading a given request content (resolution: ms).
	 * If the {@code requestTimeout} is {@code null}, any previous setting will be removed and no
	 * {@code requestTimeout} will be applied.
	 * If the {@code requestTimeout} is less than {@code 1ms}, then {@code 1ms} will be the
	 * {@code requestTimeout}.
	 *
	 * @param requestTimeout the maximum duration for reading a given request content (resolution: ms)
	 * @return a new {@link HttpServer}
	 * @since 1.1.9
	 */
	public final HttpServer requestTimeout(@Nullable Duration requestTimeout) {
		if (Objects.equals(requestTimeout, configuration().requestTimeout)) {
			return this;
		}
		HttpServer dup = duplicate();
		dup.configuration().requestTimeout = requestTimeout;
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
	 * will produce the {@link SslContext} to be passed to with a default value of:
	 * <ul>
	 *     <li>{@code 10} seconds handshake timeout unless the environment property
	 *     {@code reactor.netty.tcp.sslHandshakeTimeout} is set.</li>
	 *     <li>{@code 3} seconds close_notify flush timeout</li>
	 *     <li>{@code 0} second close_notify read timeout</li>
	 * </ul>
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
	 * will produce the {@link SslContext} to be passed to with a default value of:
	 * <ul>
	 *     <li>{@code 10} seconds handshake timeout unless the environment property
	 *     {@code reactor.netty.tcp.sslHandshakeTimeout} is set.</li>
	 *     <li>{@code 3} seconds close_notify flush timeout</li>
	 *     <li>{@code 0} second close_notify read timeout</li>
	 * </ul>
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
	 * Apply a {@link TcpServer} mapping function to update TCP configuration and
	 * return an enriched {@link HttpServer} to use.
	 * <p>
	 * <strong>Note:</strong>
	 * There isn't only one method that replaces this deprecated method.
	 * The configuration that can be done with this deprecated method,
	 * can also be done with the other methods exposed by {@link HttpServer}.
	 * </p>
	 * <p>Examples:</p>
	 * <p>Configuration via the deprecated '.tcpConfiguration(...)' method</p>
	 * <pre>
	 * {@code
	 * HttpServer.tcpConfiguration(tcpServer ->
	 *     tcpServer.attr(...) // configures the channel attributes
	 *              .bindAddress(...) // configures the bind (local) address
	 *              .channelGroup(...) // configures the channel group
	 *              .childAttr(...) // configures the child channel attributes
	 *              .childObserve(...) // configures the child channel connection observer
	 *              .childOption(...) // configures the child channel options
	 *              .doOnBound(...) // configures the doOnBound callback
	 *              .doOnChannelInit(...) // configures the channel handler
	 *              .doOnConnection(...) // configures the doOnConnection callback
	 *              .doOnUnbound(...) // configures the doOnUnbound callback
	 *              .handle(...) // configures the I/O handler
	 *              .host(...) // configures the host name
	 *              .metrics(...) // configures the metrics
	 *              .noSSL() // removes SSL configuration
	 *              .observe() // configures the connection observer
	 *              .option(...) // configures the channel options
	 *              .port(...) // configures the port
	 *              .runOn(...) // configures the event loop group
	 *              .secure() // configures the SSL
	 *              .wiretap()) // configures the wire logging
	 * }
	 * </pre>
	 *
	 * <p>Configuration via the other methods exposed by {@link HttpServer}</p>
	 * <pre>
	 * {@code
	 * HttpServer.attr(...) // configures the channel attributes
	 *           .bindAddress(...) // configures the bind (local) address
	 *           .channelGroup(...) // configures the channel group
	 *           .childAttr(...) // configures the child channel attributes
	 *           .childObserve(...) // configures the child channel connection observer
	 *           .childOption(...) // configures the child channel options
	 *           .doOnBound(...) // configures the doOnBound callback
	 *           .doOnChannelInit(...) // configures the channel handler
	 *           .doOnConnection(...) // configures the doOnConnection callback
	 *           .doOnUnbound(...) // configures the doOnUnbound callback
	 *           .handle(...) // configures the I/O handler
	 *           .host(...) // configures the host name
	 *           .metrics(...) // configures the metrics
	 *           .noSSL() // removes SSL configuration
	 *           .observe() // configures the connection observer
	 *           .option(...) // configures the channel options
	 *           .port(...) // configures the port
	 *           .runOn(...) // configures the event loop group
	 *           .secure() // configures the SSL
	 *           .wiretap() // configures the wire logging
	 * }
	 * </pre>
	 *
	 * <p>Wire logging in plain text</p>
	 * <pre>
	 * {@code
	 * HttpServer.wiretap("logger", LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL)
	 * }
	 * </pre>
	 *
	 * @param tcpMapper A {@link TcpServer} mapping function to update TCP configuration and
	 * return an enriched {@link HttpServer} to use.
	 * @return a new {@link HttpServer}
	 * @deprecated Use the other methods exposed by {@link HttpServer} to achieve the same configurations.
	 * This method will be removed in version 1.1.0.
	 */
	@Deprecated
	@SuppressWarnings("ReturnValueIgnored")
	public final HttpServer tcpConfiguration(Function<? super TcpServer, ? extends TcpServer> tcpMapper) {
		Objects.requireNonNull(tcpMapper, "tcpMapper");
		HttpServerTcpConfig tcpServer = new HttpServerTcpConfig(this);
		// ReturnValueIgnored is deliberate
		tcpMapper.apply(tcpServer);
		return tcpServer.httpServer;
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
		@SuppressWarnings("FutureReturnValueIgnored")
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
					//"FutureReturnValueIgnored" this is deliberate
					connection.channel()
					          .close();
				}
			}
		}
	}
}
