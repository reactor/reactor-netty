/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.incubator.codec.http3.Http3FrameToHttpObjectCodec;
import io.netty.incubator.codec.http3.Http3ServerConnectionHandler;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.netty.http.server.logging.AccessLog;
import reactor.netty.http.server.logging.AccessLogArgProvider;
import reactor.netty.http.server.logging.AccessLogHandlerFactory;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static reactor.netty.ReactorNetty.format;

final class Http3Codec extends ChannelInitializer<QuicStreamChannel> {

	static final Logger log = Loggers.getLogger(Http3Codec.class);

	final boolean                                                 accessLogEnabled;
	final Function<AccessLogArgProvider, AccessLog>               accessLog;
	final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
	final ServerCookieDecoder                                     cookieDecoder;
	final ServerCookieEncoder                                     cookieEncoder;
	final HttpServerFormDecoderProvider                           formDecoderProvider;
	final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	final HttpMessageLogFactory                                   httpMessageLogFactory;
	final ConnectionObserver                                      listener;
	final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
	                                                              mapHandle;
	final int                                                     minCompressionSize;
	final ChannelOperations.OnSetup                               opsFactory;
	final Duration                                                readTimeout;
	final Duration                                                requestTimeout;
	final boolean                                                 validate;

	Http3Codec(
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			boolean validate) {
		this.accessLogEnabled = accessLogEnabled;
		this.accessLog = accessLog;
		this.compressPredicate = compressPredicate;
		this.cookieDecoder = decoder;
		this.cookieEncoder = encoder;
		this.formDecoderProvider = formDecoderProvider;
		this.forwardedHeaderHandler = forwardedHeaderHandler;
		this.httpMessageLogFactory = httpMessageLogFactory;
		this.listener = listener;
		this.mapHandle = mapHandle;
		this.minCompressionSize = minCompressionSize;
		this.opsFactory = opsFactory;
		this.readTimeout = readTimeout;
		this.requestTimeout = requestTimeout;
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
		         new Http3StreamBridgeServerHandler(compressPredicate, cookieDecoder, cookieEncoder, formDecoderProvider,
		                 forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle, readTimeout, requestTimeout));

		boolean alwaysCompress = compressPredicate == null && minCompressionSize == 0;

		if (alwaysCompress) {
			p.addLast(NettyPipeline.CompressionHandler, new SimpleCompressionHandler());
		}

		ChannelOperations.addReactiveBridge(channel, opsFactory, listener);

		channel.pipeline().remove(this);

		if (log.isDebugEnabled()) {
			log.debug(format(channel, "Initialized HTTP/3 stream pipeline {}"), p);
		}
	}

	static ChannelHandler newHttp3ServerConnectionHandler(
			boolean accessLogEnabled,
			@Nullable Function<AccessLogArgProvider, AccessLog> accessLog,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			int minCompressionSize,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			boolean validate) {
		return new Http3ServerConnectionHandler(
				new Http3Codec(accessLogEnabled, accessLog, compressPredicate, decoder, encoder, formDecoderProvider, forwardedHeaderHandler,
						httpMessageLogFactory, listener, mapHandle, minCompressionSize, opsFactory, readTimeout, requestTimeout, validate));
	}
}