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

import java.net.InetSocketAddress;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import reactor.netty.tcp.InetSocketAddressUtil;

/**
 * Consumes {@link io.netty.handler.codec.haproxy.HAProxyMessage}
 * and set it into channel attribute for later use.
 *
 * @author aftersss
 */
final class HAProxyMessageReader extends ChannelInboundHandlerAdapter {

	private static final boolean hasProxyProtocol;

	static {
		boolean proxyProtocolCheck = true;
		try{
			Class.forName("io.netty.handler.codec.haproxy.HAProxyMessageDecoder");
		}
		catch (ClassNotFoundException cnfe){
			proxyProtocolCheck = false;
		}
		hasProxyProtocol = proxyProtocolCheck;
	}

	public static boolean hasProxyProtocol() {
		return hasProxyProtocol;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HAProxyMessage) {
			HAProxyMessage proxyMessage = (HAProxyMessage) msg;
			if (proxyMessage.sourceAddress() != null && proxyMessage.sourcePort() != 0) {
				InetSocketAddress remoteAddress = InetSocketAddressUtil
						.createUnresolved(proxyMessage.sourceAddress(), proxyMessage.sourcePort());
				ctx.channel()
				   .attr(ConnectionInfo.REMOTE_ADDRESS_FROM_PROXY_PROTOCOL)
				   .set(remoteAddress);
			}

			ctx.channel()
			   .pipeline()
			   .remove(this);

			ctx.read();
		} else {
			super.channelRead(ctx, msg);
		}
	}
}
