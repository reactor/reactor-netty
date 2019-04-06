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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessage;

/**
 * Consumes {@link io.netty.handler.codec.haproxy.HAProxyMessage}
 * and set it into channel attribute for later use.
 *
 * @author aftersss
 */
final class HAProxyMessageReader extends ChannelInboundHandlerAdapter {

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HAProxyMessage) {
			HAProxyMessage haProxyMessage = (HAProxyMessage)msg;
			ctx.channel().attr(ConnectionInfo.PROXY_PROTOCOL_MESSAGE).set(haProxyMessage);

			ctx.channel().pipeline().remove(this);
			ctx.read();
		} else {
			super.channelRead(ctx, msg);
		}
	}
}
