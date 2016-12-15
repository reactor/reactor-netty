/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * @author Stephane Maldini
 */
@ChannelHandler.Sharable
final class ByteBufHolderHandler extends ChannelInboundHandlerAdapter {

	static final ByteBufHolderHandler INSTANCE = new ByteBufHolderHandler();

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof ByteBufHolder) {
			ByteBuf bb = ((ByteBufHolder) msg).content();
			if(bb == Unpooled.EMPTY_BUFFER || bb instanceof EmptyByteBuf){
				ctx.fireChannelRead(msg);
			}
			else {
				ctx.fireChannelRead(bb);
			}
		}
		else {
			ctx.fireChannelRead(msg);
		}
	}
}
