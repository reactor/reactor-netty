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
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import static reactor.netty.ReactorNetty.format;

final class Http3Codec extends ChannelInitializer<QuicStreamChannel> {

	static final ChannelHandler FRAME_LOGGING_HANDLER = new Http3FrameLoggingHandler();

	static final Logger log = Loggers.getLogger(Http3Codec.class);

	final BiPredicate<HttpServerRequest, HttpServerResponse>      compressPredicate;
	final ServerCookieDecoder                                     cookieDecoder;
	final ServerCookieEncoder                                     cookieEncoder;
	final HttpServerFormDecoderProvider                           formDecoderProvider;
	final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	final HttpMessageLogFactory                                   httpMessageLogFactory;
	final ConnectionObserver                                      listener;
	final ChannelHandler                                          loggingHandler;
	final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
	                                                              mapHandle;
	final ChannelOperations.OnSetup                               opsFactory;
	final Duration                                                readTimeout;
	final Duration                                                requestTimeout;
	final boolean                                                 validate;

	Http3Codec(
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable ChannelHandler loggingHandler,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			boolean validate) {
		this.compressPredicate = compressPredicate;
		this.cookieDecoder = decoder;
		this.cookieEncoder = encoder;
		this.formDecoderProvider = formDecoderProvider;
		this.forwardedHeaderHandler = forwardedHeaderHandler;
		this.httpMessageLogFactory = httpMessageLogFactory;
		this.listener = listener;
		this.loggingHandler = loggingHandler;
		this.mapHandle = mapHandle;
		this.opsFactory = opsFactory;
		this.readTimeout = readTimeout;
		this.requestTimeout = requestTimeout;
		this.validate = validate;
	}

	@Override
	protected void initChannel(QuicStreamChannel channel) {
		ChannelPipeline p = channel.pipeline();
		if (loggingHandler != null && p.context("Http3FrameCodec#0") != null) {
			p.addAfter(channel.pipeline().context("Http3FrameCodec#0").name(), NettyPipeline.LoggingHandler, loggingHandler);
		}
		p.addLast(new Http3FrameToHttpObjectCodec(true, validate))
		 .addLast(NettyPipeline.HttpTrafficHandler,
		         new Http3StreamBridgeServerHandler(compressPredicate, cookieDecoder, cookieEncoder, formDecoderProvider,
		                 forwardedHeaderHandler, httpMessageLogFactory, listener, mapHandle, readTimeout, requestTimeout));

		ChannelOperations.addReactiveBridge(channel, opsFactory, listener);

		channel.pipeline().remove(this);

		if (log.isDebugEnabled()) {
			log.debug(format(channel, "Initialized HTTP/3 stream pipeline {}"), p);
		}
	}

	static ChannelHandler newHttp3ServerConnectionHandler(
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			boolean enableLogging,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			ChannelOperations.OnSetup opsFactory,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout,
			boolean validate) {
		ChannelHandler loggingHandler = enableLogging ? FRAME_LOGGING_HANDLER : null;
		return new Http3ServerConnectionHandler(
				new Http3Codec(compressPredicate, decoder, encoder, formDecoderProvider, forwardedHeaderHandler,
						httpMessageLogFactory, listener, loggingHandler, mapHandle, opsFactory, readTimeout, requestTimeout, validate),
				loggingHandler, null, null, true);
	}
}