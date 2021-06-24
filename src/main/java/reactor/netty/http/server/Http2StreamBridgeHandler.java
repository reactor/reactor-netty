/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.ssl.SslHandler;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.util.annotation.Nullable;

import static reactor.netty.ReactorNetty.format;

/**
 * This handler is intended to work together with {@link Http2StreamFrameToHttpObjectCodec}
 * it converts the outgoing messages into objects expected by
 * {@link Http2StreamFrameToHttpObjectCodec}.
 *
 * @author Violeta Georgieva
 */
final class Http2StreamBridgeHandler extends ChannelDuplexHandler implements ChannelFutureListener {

	final BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler;

	Boolean                   secured;
	InetSocketAddress         remoteAddress;
	final ConnectionObserver  listener;
	final ServerCookieEncoder cookieEncoder;
	final ServerCookieDecoder cookieDecoder;
	final BiPredicate<HttpServerRequest, HttpServerResponse> compress;

	Http2StreamBridgeHandler(ConnectionObserver listener,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler,
			ServerCookieEncoder encoder,
			ServerCookieDecoder decoder,
			@Nullable BiPredicate<HttpServerRequest, HttpServerResponse> compress) {
		this.forwardedHeaderHandler = forwardedHeaderHandler;
		this.listener = listener;
		this.cookieEncoder = encoder;
		this.cookieDecoder = decoder;
		this.compress = compress;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		super.handlerAdded(ctx);
		if (HttpServerOperations.log.isDebugEnabled()) {
			HttpServerOperations.log.debug(format(ctx.channel(), "New http2 connection, requesting read"));
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
					        .orElse(((SocketChannel) ctx.channel().parent()).remoteAddress());
		}
		if (msg instanceof HttpRequest) {
			HttpRequest request = (HttpRequest) msg;
			HttpServerOperations ops;
			try {
				ops = new HttpServerOperations(Connection.from(ctx.channel()),
						listener,
						compress,
						request,
						ConnectionInfo.from(ctx.channel().parent(),
						                    request,
						                    secured,
						                    remoteAddress,
						                    forwardedHeaderHandler),
						cookieEncoder,
						cookieDecoder);
			}
			catch (RuntimeException e) {
				HttpServerOperations.sendDecodingFailures(ctx, e, msg);
				return;
			}
			ops.bind();
			listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
		}
		ctx.fireChannelRead(msg);
	}


	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof ByteBuf) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(new DefaultHttpContent((ByteBuf) msg), promise);
		}
		else {
			//"FutureReturnValueIgnored" this is deliberate
			ChannelFuture f = ctx.write(msg, promise);
			if (msg instanceof LastHttpContent) {
				f.addListener(this);
			}
		}
	}

	@Override
	public void operationComplete(ChannelFuture future) {
		if (!future.isSuccess()) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(future.channel(),
						"Sending last HTTP packet was not successful, terminating the channel"),
						future.cause());
			}
		}
		else {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug(format(future.channel(),
						"Last HTTP packet was sent, terminating the channel"));
			}
		}

		HttpServerOperations.cleanHandlerTerminate(future.channel());
	}
}
