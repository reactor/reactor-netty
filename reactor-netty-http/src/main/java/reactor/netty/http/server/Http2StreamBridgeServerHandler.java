/*
 * Copyright (c) 2018-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
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
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.ReactorNetty;
import reactor.netty.http.logging.HttpMessageArgProviderFactory;
import reactor.netty.http.logging.HttpMessageLogFactory;
import reactor.netty.http.server.compression.HttpCompressionOptionsSpec;

import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;
import static reactor.netty.ReactorNetty.format;

/**
 * This handler is intended to work together with {@link Http2StreamFrameToHttpObjectCodec}
 * it converts the outgoing messages into objects expected by
 * {@link Http2StreamFrameToHttpObjectCodec}.
 *
 * @author Violeta Georgieva
 */
final class Http2StreamBridgeServerHandler extends ChannelDuplexHandler {

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

	@SuppressWarnings("NullAway")
	// Deliberately suppress "NullAway"
	// This is a lazy initialization
	Boolean secured;

	/**
	 * Flag to indicate if a request is not yet fully responded.
	 */
	boolean pendingResponse;

	Http2StreamBridgeServerHandler(
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
			HttpServerOperations.log.debug(format(ctx.channel(), "New HTTP/2 stream"));
		}
		ctx.read();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (secured == null) {
			secured = ctx.channel().parent().pipeline().get(SslHandler.class) != null;
		}
		if (remoteAddress == null) {
			remoteAddress =
					Optional.ofNullable(HAProxyMessageReader.resolveRemoteAddressFromProxyProtocol(ctx.channel().parent()))
					        .orElse(ctx.channel().parent().remoteAddress());
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
						secured,
						ctx.channel().localAddress(),
						remoteAddress,
						forwardedHeaderHandler);
				ops = new HttpServerOperations(Connection.from(ctx.channel()),
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
						secured,
						timestamp,
						true);
			}
			catch (RuntimeException e) {
				pendingResponse = false;
				request.setDecoderResult(DecoderResult.failure(e.getCause() != null ? e.getCause() : e));
				HttpServerOperations.sendDecodingFailures(ctx, listener, secured, e, msg, httpMessageLogFactory, true, timestamp, connectionInfo, remoteAddress, true);
				return;
			}
			ops.bind();
			listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
		}
		else if (!pendingResponse) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(
						format(ctx.channel(), "Dropped HTTP content, since response has been sent already: {}"),
						msg instanceof HttpObject ?
								httpMessageLogFactory.debug(HttpMessageArgProviderFactory.create(msg)) : msg);
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
			ctx.write(msg, promise);
			if (HttpResponseStatus.CONTINUE.code() == ((DefaultFullHttpResponse) msg).status().code()) {
				return;
			}
			finalizeResponse(ctx);
		}
		else if (msg == EMPTY_LAST_CONTENT || msgClass == DefaultLastHttpContent.class) {
			ctx.write(msg, promise);
			finalizeResponse(ctx);
		}
		else if (msg instanceof ByteBuf) {
			if (!pendingResponse) {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(
							format(ctx.channel(), "Dropped HTTP content, since response has been sent already: {}"), msg);
				}
				((ByteBuf) msg).release();
				promise.setSuccess();
				return;
			}

			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(new DefaultHttpContent((ByteBuf) msg), promise);
		}
		else if (msg instanceof HttpResponse && HttpResponseStatus.CONTINUE.code() == ((HttpResponse) msg).status().code()) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}
		else {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
			if (msg instanceof LastHttpContent) {
				finalizeResponse(ctx);
			}
		}
	}

	void finalizeResponse(ChannelHandlerContext ctx) {
		pendingResponse = false;
		ctx.read();
	}
}
