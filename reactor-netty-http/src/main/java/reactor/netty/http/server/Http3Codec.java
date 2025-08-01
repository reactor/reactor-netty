/*
 * Copyright (c) 2024-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http3.Http3FrameToHttpObjectCodec;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.netty.http.server.compression.HttpCompressionOptionsSpec;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.http.server.logging.AccessLogArgProvider;
import reactor.netty.http.server.logging.AccessLogHandlerFactory;
import reactor.netty.http.server.logging.error.DefaultErrorLogHandler;
import reactor.netty.http.server.logging.error.ErrorLog;
import reactor.netty.http.server.logging.error.ErrorLogArgProvider;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static reactor.netty.ReactorNetty.format;

final class Http3Codec extends ChannelInitializer<QuicStreamChannel> {

	static final Logger log = Loggers.getLogger(Http3Codec.class);

	final boolean                                                           accessLogEnabled;
	final @Nullable Function<AccessLogArgProvider, @Nullable AccessLog>     accessLog;
	final @Nullable HttpCompressionOptionsSpec                              compressionOptions;
	final @Nullable BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
	final ServerCookieDecoder                                               cookieDecoder;
	final ServerCookieEncoder                                               cookieEncoder;
	final boolean                                                           errorLogEnabled;
	final @Nullable Function<ErrorLogArgProvider, @Nullable ErrorLog>       errorLog;
	final HttpServerFormDecoderProvider                                     formDecoderProvider;
	final @Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	final HttpMessageLogFactory                                             httpMessageLogFactory;
	final ConnectionObserver                                                listener;
	final @Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
	                                                                        mapHandle;
	final @Nullable Function<String, String>                                methodTagValue;
	final @Nullable ChannelMetricsRecorder                                  metricsRecorder;
	final int                                                               minCompressionSize;
	final ChannelOperations.OnSetup                                         opsFactory;
	final @Nullable Duration                                                readTimeout;
	final @Nullable Duration                                                requestTimeout;
	final @Nullable Function<String, String>                                uriTagValue;
	final boolean                                                           validate;

	Http3Codec(
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, @Nullable AccessLog> accessLog,
			@Nullable HttpCompressionOptionsSpec compressionOptions,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			boolean errorLogEnabled,
			@Nullable Function<ErrorLogArgProvider, @Nullable ErrorLog> errorLog,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable Function<String, String> methodTagValue,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			@Nullable Function<String, String> uriTagValue,
			boolean validate) {
		this.accessLogEnabled = accessLogEnabled;
		this.accessLog = accessLog;
		this.compressionOptions = compressionOptions;
		this.compressPredicate = compressPredicate;
		this.cookieDecoder = decoder;
		this.cookieEncoder = encoder;
		this.errorLogEnabled = errorLogEnabled;
		this.errorLog = errorLog;
		this.formDecoderProvider = formDecoderProvider;
		this.forwardedHeaderHandler = forwardedHeaderHandler;
		this.httpMessageLogFactory = httpMessageLogFactory;
		this.listener = listener;
		this.mapHandle = mapHandle;
		this.methodTagValue = methodTagValue;
		this.metricsRecorder = metricsRecorder;
		this.minCompressionSize = minCompressionSize;
		this.opsFactory = opsFactory;
		this.readTimeout = readTimeout;
		this.requestTimeout = requestTimeout;
		this.uriTagValue = uriTagValue;
		this.validate = validate;
	}

	@Override
	protected void initChannel(QuicStreamChannel channel) {
		ChannelPipeline p = channel.pipeline();

		if (accessLogEnabled) {
			p.addLast(NettyPipeline.AccessLogHandler, AccessLogHandlerFactory.H3.create(accessLog));
		}

		p.addLast(NettyPipeline.H3ToHttp11Codec, new Http3FrameToHttpObjectCodec(true, validate))
		 .addLast(NettyPipeline.HttpTrafficHandler,
		         new Http3StreamBridgeServerHandler(compressPredicate, compressionOptions, cookieDecoder, cookieEncoder, formDecoderProvider,
		                 forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle, readTimeout, requestTimeout));

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			p.addLast(NettyPipeline.CompressionHandler, SimpleCompressionHandler.create(compressionOptions));
		}

		ChannelOperations.addReactiveBridge(channel, opsFactory, listener);

		if (metricsRecorder != null) {
			if (metricsRecorder instanceof HttpServerMetricsRecorder) {
				ChannelHandler handler;
				if (metricsRecorder instanceof MicrometerHttpServerMetricsRecorder) {
					handler = new MicrometerHttpServerMetricsHandler((MicrometerHttpServerMetricsRecorder) metricsRecorder, methodTagValue, uriTagValue);
				}
				else if (metricsRecorder instanceof ContextAwareHttpServerMetricsRecorder) {
					handler = new ContextAwareHttpServerMetricsHandler((ContextAwareHttpServerMetricsRecorder) metricsRecorder, methodTagValue, uriTagValue);
				}
				else {
					handler = new HttpServerMetricsHandler((HttpServerMetricsRecorder) metricsRecorder, methodTagValue, uriTagValue);
				}
				p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.HttpMetricsHandler, handler);
			}
		}

		if (errorLogEnabled) {
			p.addBefore(NettyPipeline.ReactiveBridge, NettyPipeline.ErrorLogHandler, new DefaultErrorLogHandler(errorLog));
		}

		channel.pipeline().remove(this);

		if (log.isDebugEnabled()) {
			log.debug(format(channel, "Initialized HTTP/3 stream pipeline {}"), p);
		}
	}

	static ChannelHandler newHttp3ServerConnectionHandler(
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, @Nullable AccessLog> accessLog,
			@Nullable HttpCompressionOptionsSpec compressionOptions,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			boolean errorLogEnabled,
			@Nullable Function<ErrorLogArgProvider, @Nullable ErrorLog> errorLog,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable Function<String, String> methodTagValue,
			@Nullable ChannelMetricsRecorder metricsRecorder,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			@Nullable Function<String, String> uriTagValue,
			boolean validate) {
		return new Http3ServerConnectionHandler(
				new Http3Codec(accessLogEnabled, accessLog, compressionOptions, compressPredicate, decoder, encoder, errorLogEnabled, errorLog,
						formDecoderProvider, forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle, methodTagValue, metricsRecorder,
						minCompressionSize, opsFactory, readTimeout, requestTimeout, uriTagValue, validate));
	}
}