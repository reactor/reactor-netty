/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import javax.annotation.Nullable;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;

/**
 * The handler sends an HTTP response with a status code of 308 and a
 * location header consisting of the host header of the request and
 * HTTPS protocol to the incoming HTTP requests.
 * <p>
 * The handler is applicable only for HTTP/1.x and will not propagate
 * for incoming HTTP requests
 *
 * @author James Chen
 * @since 1.0.5
 */
final class NonSslRedirectHandler extends ChannelInboundHandlerAdapter {

	private static final String HTTP_PROTOCOL = "http://";
	private static final String HTTPS_PROTOCOL = "https://";

	@SuppressWarnings("FutureReturnValueIgnored")
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof HttpRequest) {
			ctx.pipeline().remove(this);
			HttpRequest request = (HttpRequest) msg;
			String url = getRequestedUrlInHttps(request);
			if (url == null) {
				// "FutureReturnValueIgnored" this is deliberate
				ctx.close();
			}
			else {
				HttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), HttpResponseStatus.PERMANENT_REDIRECT);
				response.headers().set(LOCATION, url);
				ctx.channel().writeAndFlush(response)
						.addListener(ChannelFutureListener.CLOSE);
			}
		}
		else {
			ctx.fireChannelRead(msg);
		}
	}

	@Nullable
	private String getRequestedUrlInHttps(HttpRequest request) {
		String uri = request.uri();
		boolean isAbsoluteUri = uri.startsWith(HTTP_PROTOCOL);
		if (isAbsoluteUri) {
			// Don't use String#replace because of its bad performance due to regex
			return HTTPS_PROTOCOL + uri.substring(HTTP_PROTOCOL.length());
		}
		String host = request.headers().get(HOST);
		if (host == null) {
			return null;
		}
		return HTTPS_PROTOCOL + host + uri;
	}

}