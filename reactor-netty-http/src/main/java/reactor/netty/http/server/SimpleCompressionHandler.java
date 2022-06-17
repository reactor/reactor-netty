/*
 * Copyright (c) 2018-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty5.buffer.api.Buffer;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.HttpContentCompressor;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.util.concurrent.Future;

/**
 * @author Stephane Maldini
 */
final class SimpleCompressionHandler extends HttpContentCompressor {

	@Override
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Buffer) {
			return super.write(ctx, new DefaultHttpContent((Buffer) msg));
		}
		return super.write(ctx, msg);
	}

	@Override
	protected void decodeAndClose(ChannelHandlerContext ctx, HttpRequest msg) throws Exception {
		HttpRequest request = msg;
		if (msg instanceof FullHttpRequest && !((FullHttpRequest) msg).payload().isAccessible()) {
			// This can happen only in HTTP2 use case and delayed response
			// When the incoming FullHttpRequest content is with 0 readableBytes it is released immediately
			// decode(...) will observe a freed content
			request = new DefaultHttpRequest(msg.protocolVersion(), msg.method(), msg.uri(), msg.headers());
		}
		super.decodeAndClose(ctx, request);
	}
}
