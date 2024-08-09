/*
 * Copyright (c) 2018-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.DecoderResult;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.HttpObject;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty5.handler.ssl.SslHandler;
import io.netty5.util.Resource;
import io.netty5.util.concurrent.Future;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.ReactorNetty;
import reactor.netty5.http.logging.HttpMessageArgProviderFactory;
import reactor.netty5.http.logging.HttpMessageLogFactory;
import reactor.util.annotation.Nullable;

import static io.netty5.handler.codec.http.HttpResponseStatus.CONTINUE;
import static reactor.netty5.ReactorNetty.format;

/**
 * This handler is intended to work together with {@link Http2StreamFrameToHttpObjectCodec}
 * it converts the outgoing messages into objects expected by
 * {@link Http2StreamFrameToHttpObjectCodec}.
 *
 * @author Violeta Georgieva
 */
final class Http2StreamBridgeServerHandler extends ChannelHandlerAdapter {

	final BiPredicate<HttpServerRequest, HttpServerResponse>      compress;
	final HttpServerFormDecoderProvider                           formDecoderProvider;
	final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;
	final HttpMessageLogFactory                                   httpMessageLogFactory;
	final ConnectionObserver                                      listener;
	final BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>>
	                                                              mapHandle;
	final Duration                                                readTimeout;
	final Duration                                                requestTimeout;

	SocketAddress remoteAddress;

	Boolean secured;

	/**
	 * Flag to indicate if a request is not yet fully responded.
	 */
	boolean pendingResponse;

	Http2StreamBridgeServerHandler(
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compress,
			HttpServerFormDecoderProvider formDecoderProvider,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			HttpMessageLogFactory httpMessageLogFactory,
			ConnectionObserver listener,
			@Nullable BiFunction<? super Mono<Void>, ? super Connection, ? extends Mono<Void>> mapHandle,
			@Nullable Duration readTimeout,
			@Nullable Duration requestTimeout) {
		this.compress = compress;
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
		if (msg instanceof HttpRequest request) {
			HttpServerOperations ops;
			ZonedDateTime timestamp = ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM);
			ConnectionInfo connectionInfo = null;
			try {
				pendingResponse = true;
				connectionInfo = ConnectionInfo.from(ctx.channel(),
						request,
						secured,
						remoteAddress,
						forwardedHeaderHandler);
				ops = new HttpServerOperations(Connection.from(ctx.channel()),
						listener,
						request,
						compress,
						connectionInfo,
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
			Resource.dispose(msg);
			ctx.read();
			return;
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Buffer buffer) {
			if (!pendingResponse) {
				if (HttpServerOperations.log.isDebugEnabled()) {
					HttpServerOperations.log.debug(
							format(ctx.channel(), "Dropped HTTP content, since response has been sent already: {}"), msg);
				}
				((Buffer) msg).close();
				return ctx.newSucceededFuture();
			}

			return ctx.write(new DefaultHttpContent(buffer));
		}
		else if (msg instanceof HttpResponse && CONTINUE.code() == ((HttpResponse) msg).status().code()) {
			return ctx.write(msg);
		}
		else {
			Future<Void> f = ctx.write(msg);
			if (msg instanceof LastHttpContent) {
				pendingResponse = false;
				ctx.read();
			}
			return f;
		}
	}
}
