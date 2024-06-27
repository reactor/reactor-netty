/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.incubator.codec.http3.Http3FrameToHttpObjectCodec;
import io.netty.incubator.codec.quic.QuicStreamChannel;

/**
 * This handler is intended to work together with {@link Http3FrameToHttpObjectCodec}
 * it converts the outgoing messages into objects expected by
 * {@link Http3FrameToHttpObjectCodec}.
 *
 * @author Violeta Georgieva
 * @since 1.2.0
 */
final class Http3StreamBridgeClientHandler extends ChannelOutboundHandlerAdapter {

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (msg instanceof ByteBuf) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(new DefaultHttpContent((ByteBuf) msg), promise);
		}
		else {
			ChannelFuture f = ctx.write(msg, promise);
			if (msg instanceof LastHttpContent) {
				f.addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
			}
		}
	}

	@Override
	public boolean isSharable() {
		return true;
	}
}
