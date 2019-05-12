/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.HttpConversionUtil;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;

import static reactor.netty.ReactorNetty.format;

final class Http2StreamBridgeHandler extends ChannelDuplexHandler {

	final boolean            readForwardHeaders;
	final boolean            secured;
	final ConnectionObserver listener;
	final ServerCookieEncoder cookieEncoder;
	final ServerCookieDecoder cookieDecoder;

	Http2StreamBridgeHandler(ConnectionObserver listener, boolean readForwardHeaders,
			ServerCookieEncoder encoder,
			ServerCookieDecoder decoder,
			boolean secured) {
		this.readForwardHeaders = readForwardHeaders;
		this.listener = listener;
		this.cookieEncoder = encoder;
		this.secured = secured;
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
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof Http2HeadersFrame) {
			Http2HeadersFrame headersFrame = (Http2HeadersFrame)msg;
			HttpRequest request;
			if (headersFrame.isEndStream()) {
				request = HttpConversionUtil.toFullHttpRequest(-1,
						headersFrame.headers(),
						ctx.channel().alloc(),
						false);
			}
			else {
				request = HttpConversionUtil.toHttpRequest(-1,
								headersFrame.headers(),
								false);
			}
			HttpToH2Operations ops = new HttpToH2Operations(Connection.from(ctx.channel()),
					listener,
					request,
					headersFrame.headers(),
					ConnectionInfo.from(ctx.channel()
					                       .parent(),
							readForwardHeaders,
							request,
							secured),
					cookieEncoder, cookieDecoder);
			ops.bind();
			listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
		}
		ctx.fireChannelRead(msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof Http2Headers) {
			msg = new DefaultHttp2HeadersFrame((Http2Headers) msg);
		}

		boolean endOfHttp2Stream = false;
		if (msg instanceof Http2DataFrame) {
			endOfHttp2Stream = ((Http2DataFrame) msg).isEndStream();
		}


		if (!endOfHttp2Stream && msg instanceof ByteBuf &&
				ctx.channel() instanceof Http2StreamChannel) {
			msg = new DefaultHttp2DataFrame((ByteBuf) msg);
		}

		ctx.write(msg, promise);
	}

}