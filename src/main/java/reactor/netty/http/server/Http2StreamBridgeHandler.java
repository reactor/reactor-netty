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

import java.net.InetSocketAddress;
import java.util.Optional;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.ssl.SslHandler;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;

import static reactor.netty.ReactorNetty.format;

final class Http2StreamBridgeHandler extends ChannelDuplexHandler {

	final boolean             readForwardHeaders;
	Boolean                   secured;
	InetSocketAddress         remoteAddress;
	final ConnectionObserver  listener;
	final ServerCookieEncoder cookieEncoder;
	final ServerCookieDecoder cookieDecoder;

	Http2StreamBridgeHandler(ConnectionObserver listener, boolean readForwardHeaders,
			ServerCookieEncoder encoder,
			ServerCookieDecoder decoder) {
		this.readForwardHeaders = readForwardHeaders;
		this.listener = listener;
		this.cookieEncoder = encoder;
		this.cookieDecoder = decoder;
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
			secured = ctx.channel().pipeline().get(SslHandler.class) != null;
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


	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof ByteBuf) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(new DefaultHttpContent((ByteBuf) msg), promise);
		}
		else {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}
	}
}
