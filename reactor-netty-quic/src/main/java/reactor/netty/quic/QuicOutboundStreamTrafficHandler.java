/*
 * Copyright (c) 2021-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.quic;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;

/**
 * {@link ChannelInboundHandlerAdapter} to handle {@code WRITE_FIN}.
 *
 * @author Violeta Georgieva
 */
final class QuicOutboundStreamTrafficHandler extends ChannelInboundHandlerAdapter {

	static final Logger log = Loggers.getLogger(QuicOutboundStreamTrafficHandler.class);

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
		if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(), "Remote peer sent WRITE_FIN."));
			}
			ctx.channel().config().setAutoRead(true);
			QuicStreamOperations.callTerminate(ctx.channel());
		}
		ctx.fireUserEventTriggered(evt);
	}
}
