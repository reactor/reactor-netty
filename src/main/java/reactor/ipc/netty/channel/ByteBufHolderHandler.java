/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * A handler that can be used to extract {@link ByteBuf} out of {@link ByteBufHolder},
 * optionally also outputting additional messages (eg. to still propagate a
 * {@link LastHttpContent} message after its content has been extracted).
 *
 * @author Stephane Maldini
 * @author Simon Baslé
 */
@ChannelHandler.Sharable
final class ByteBufHolderHandler extends ChannelInboundHandlerAdapter {

	/**
	 * Mirrors an {@link LastHttpContent} message with an empty version after its
	 * content has been extracted.
 	 */
	static final Function<Object, Object> HTTP_LAST_REPLAY = msg -> msg instanceof LastHttpContent ? LastHttpContent.EMPTY_LAST_CONTENT : null;

	/**
	 * Do not mirror any message when extracting content.
	 */
	static final Function<Object, Object> NO_LAST_REPLAY = msg ->  null;

	/**
	 * A {@link ByteBufHolderHandler} instance that just extract {@link ByteBuf} from
	 * holders without mirroring any terminating message.
	 */
	static final ByteBufHolderHandler INSTANCE = new ByteBufHolderHandler(NO_LAST_REPLAY);

	final Function<Object, Object> additionalMsgOnExtract;

	public ByteBufHolderHandler(Function<Object, Object> additionalMsgOnExtract) {
		this.additionalMsgOnExtract = additionalMsgOnExtract;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof ByteBufHolder) {
			ByteBuf bb = ((ByteBufHolder) msg).content();
			if(bb == Unpooled.EMPTY_BUFFER || bb instanceof EmptyByteBuf){
				ctx.fireChannelRead(msg);
			}
			else {
				ctx.fireChannelRead(bb);
				Object additionalMsg = additionalMsgOnExtract.apply(msg);
				if (additionalMsg != null) {
					ctx.fireChannelRead(additionalMsg);
				}
			}
		}
		else {
			ctx.fireChannelRead(msg);
		}
	}
}
