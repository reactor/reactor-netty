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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpRequest;

import java.util.List;

/**
 * @author Stephane Maldini
 */
final class SimpleCompressionHandler extends HttpContentCompressor {

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {

		if (msg instanceof ByteBuf) {
			super.write(ctx, new DefaultHttpContent((ByteBuf)msg), promise);
		}
		else {
			super.write(ctx, msg, promise);
		}
	}

	@Override
	public void decode(ChannelHandlerContext ctx, HttpRequest msg, List<Object> out) throws Exception {
		super.decode(ctx, msg, out);
	}
}
