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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.logging.HttpMessageArgProviderFactory;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.netty.http.server.compression.HttpCompressionOptionsSpec;

import java.net.SocketAddress;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;
import static reactor.netty.ReactorNetty.format;

final class Http3StreamBridgeServerHandler extends ChannelDuplexHandler {
	final @Nullable BiPredicate<HttpServerRequest, HttpServerResponse>      compress;
	final @Nullable HttpCompressionOptionsSpec                              compressionOptions;
	final ServerCookieDecoder                                               cookieDecoder;
	final ServerCookieEncoder                                               cookieEncoder;
	final HttpServerFormDecoderProvider                                     formDecoderProvider;
	final @Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	final HttpMessageLogFactory                                             httpMessageLogFactory;
	final ConnectionObserver                                                listener;
	final @Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
	                                                                        mapHandle;
	final @Nullable Duration                                                readTimeout;
	final @Nullable Duration                                                requestTimeout;

	@SuppressWarnings("NullAway")
	// Deliberately suppress "NullAway"
	// This is a lazy initialization
	SocketAddress remoteAddress;

	/**
	 * Flag to indicate if a request is not yet fully responded.
	 */
	boolean pendingResponse;

	Http3StreamBridgeServerHandler(
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compress,
			@Nullable HttpCompressionOptionsSpec compressionOptions,
			ServerCookieDecoder decoder,
			ServerCookieEncoder encoder,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout) {
		this.compress = compress;
		this.compressionOptions = compressionOptions;
		this.cookieDecoder = decoder;
		this.cookieEncoder = encoder;
		this.formDecoderProvider = formDecoderProvider;
		this.forwardedHeaderHandler = forwardedHeaderHandler;
		this.httpMessageLogFactory = httpMessageLogFactory;
		this.listener = listener;
		this.mapHandle = mapHandle;
		this.readTimeout = readTimeout;
		this.requestTimeout = requestTimeout;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		if (HttpServerOperations.log.isDebugEnabled()) {
			HttpServerOperations.log.debug(format(ctx.channel(), "New HTTP/3 stream"));
		}
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		ctx.read();
	}

	@Override
	@SuppressWarnings("NullAway")
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		assert ctx.channel().parent() instanceof QuicChannel;
		QuicChannel parent = (QuicChannel) ctx.channel().parent();
		if (remoteAddress == null) {
			// Deliberately suppress "NullAway"
			// This is null if none is assigned yet, or assigned anymore,
			// at this point (channelRead) it cannot be null.
			remoteAddress = parent.remoteSocketAddress();
		}
		if (msg instanceof HttpRequest) {
			HttpRequest request = (HttpRequest) msg;
			HttpServerOperations ops;
			ZonedDateTime timestamp = ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM);
			ConnectionInfo connectionInfo = null;
			try {
				pendingResponse = true;
				connectionInfo = ConnectionInfo.from(
						request,
						true,
						// Deliberately suppress "NullAway"
						// This is null if none is assigned yet, or assigned anymore,
						// at this point (channelRead) it cannot be null.
						parent.localSocketAddress(),
						remoteAddress,
						forwardedHeaderHandler);
				ops = new Http3ServerOperations(Connection.from(ctx.channel()),
						listener,
						request,
						compressionOptions,
						compress,
						connectionInfo,
						cookieDecoder,
						cookieEncoder,
						formDecoderProvider,
						httpMessageLogFactory,
						true,
						mapHandle,
						readTimeout,
						requestTimeout,
						true,
						timestamp);
			}
			catch (RuntimeException e) {
				pendingResponse = false;
				request.setDecoderResult(DecoderResult.failure(e.getCause() != null ? e.getCause() : e));
				HttpServerOperations.sendDecodingFailures(ctx, listener, true, e, msg, httpMessageLogFactory, true, timestamp, connectionInfo, remoteAddress, true);
				return;
			}
			ops.bind();
			listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
		}
		else if (!pendingResponse) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
				HttpVersion version = channelOps instanceof HttpServerOperations ?
						((HttpServerOperations) channelOps).version() : null;
				HttpServerOperations.log.debug(
						format(ctx.channel(), "Dropped HTTP content, since response has been sent already: {}"),
						msg instanceof HttpObject ?
								httpMessageLogFactory.debug(HttpMessageArgProviderFactory.create(msg, version)) : msg);
			}
			ReferenceCountUtil.release(msg);
			ctx.read();
			return;
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		Class<?> msgClass = msg.getClass();
		if (msgClass == DefaultHttpResponse.class) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}
		else if (msgClass == DefaultFullHttpResponse.class) {
			//"FutureReturnValueIgnored" this is deliberate
			ChannelFuture f = ctx.write(msg, promise);
			if (HttpResponseStatus.CONTINUE.code() == ((DefaultFullHttpResponse) msg).status().code()) {
				return;
			}
			finalizeResponse(ctx, f);
		}
		else if (msg == EMPTY_LAST_CONTENT || msgClass == DefaultLastHttpContent.class) {
			finalizeResponse(ctx, ctx.write(msg, promise));
		}
		else if (msg instanceof ByteBuf) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(new DefaultHttpContent((ByteBuf) msg), promise);
		}
		else if (msg instanceof HttpResponse && HttpResponseStatus.CONTINUE.code() == ((HttpResponse) msg).status().code()) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}
		else {
			//"FutureReturnValueIgnored" this is deliberate
			ChannelFuture f = ctx.write(msg, promise);
			if (msg instanceof LastHttpContent) {
				finalizeResponse(ctx, f);
			}
		}
	}

	void finalizeResponse(ChannelHandlerContext ctx, ChannelFuture f) {
		pendingResponse = false;
		f.addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
		ctx.read();
	}
}
