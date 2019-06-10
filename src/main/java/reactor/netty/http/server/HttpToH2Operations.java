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

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.ReferenceCountUtil;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 */
public class HttpToH2Operations extends HttpServerOperations {


	final Http2Headers http2Headers;

	HttpToH2Operations(Connection c,
			ConnectionObserver listener,
			HttpRequest request,
			Http2Headers headers,
			ConnectionInfo connectionInfo,
			ServerCookieEncoder encoder,
			ServerCookieDecoder decoder) {
		super(c, listener, null, request, connectionInfo, encoder, decoder);

		this.http2Headers = headers;
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http2Frame) {
			if (msg instanceof Http2DataFrame) {
				Http2DataFrame data = (Http2DataFrame) msg;
				super.onInboundNext(ctx, data);
				if (data.isEndStream()) {
					onInboundComplete();
				}
				return;
			}

			if(msg instanceof Http2HeadersFrame) {
				try {
					listener().onStateChange(this, HttpServerState.REQUEST_RECEIVED);
				}
				catch (Exception e) {
					onInboundError(e);
					ReferenceCountUtil.release(msg);
					return;
				}
				if (((Http2HeadersFrame) msg).isEndStream()) {
					super.onInboundNext(ctx, msg);
				}
				return;
			}

			if (log.isDebugEnabled()){
				log.debug(format(channel(), "Unused H2 frame " + msg.toString()));
			}
			return;
		}

		super.onInboundNext(ctx, msg);
	}
}
