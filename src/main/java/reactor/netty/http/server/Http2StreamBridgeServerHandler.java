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

import java.net.SocketAddress;
import java.util.Optional;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.ssl.SslHandler;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.http.Http2StreamBridgeHandler;

final class Http2StreamBridgeServerHandler extends Http2StreamBridgeHandler {

	final ServerCookieDecoder cookieDecoder;
	final ServerCookieEncoder cookieEncoder;
	final ConnectionObserver  listener;
	final boolean             readForwardHeaders;

	SocketAddress             remoteAddress;
	Boolean                   secured;

	Http2StreamBridgeServerHandler(ConnectionObserver listener, boolean readForwardHeaders,
			ServerCookieEncoder encoder, ServerCookieDecoder decoder) {
		this.cookieDecoder = decoder;
		this.cookieEncoder = encoder;
		this.listener = listener;
		this.readForwardHeaders = readForwardHeaders;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		super.handlerAdded(ctx);
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
			try {
				ops = new HttpServerOperations(Connection.from(ctx.channel()),
						listener,
						null,
						request,
						ConnectionInfo.from(ctx.channel().parent(),
						                    readForwardHeaders,
						                    request,
						                    secured,
						                    remoteAddress),
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
}
