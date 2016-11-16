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

import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import reactor.core.Exceptions;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Netty {@link io.netty.channel.ChannelInboundHandler} implementation that bridge data
 * via an IPC {@link NettyOutbound} and {@link NettyInbound}
 *
 * @author Stephane Maldini
 */
@ChannelHandler.Sharable
final class ChannelOperationsHandler extends ChannelDuplexHandler {

	@Override
	public void channelActive(final ChannelHandlerContext ctx) throws Exception {
		ctx.fireChannelActive();
		operations(ctx).onChannelActive(ctx);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		try {
			operations(ctx).onChannelComplete();
		}
		catch (Throwable err) {
			Exceptions.throwIfFatal(err);
			operations(ctx).onChannelError(err);
		}
		finally {
			ctx.fireChannelInactive();
		}
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg == null) {
			return;
		}
		try {
			if (msg == Unpooled.EMPTY_BUFFER || msg instanceof EmptyByteBuf) {
				return;
			}
			operations(ctx).onInboundNext(ctx, msg);
			ctx.fireChannelRead(msg);
		}
		catch (Throwable err) {
			Exceptions.throwIfFatal(err);
			operations(ctx).onChannelError(err);
		}
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		operations(ctx).afterInboundNext(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable err)
			throws Exception {
		Exceptions.throwIfFatal(err);
		if(log.isDebugEnabled()){
			log.error("handler failure", err);
		}
		operations(ctx).onChannelError(err);
	}

	final ChannelOperations<?, ?> operations(ChannelHandlerContext ctx) {
		return ctx.channel()
		          .attr(ChannelOperations.OPERATIONS_ATTRIBUTE_KEY)
		          .get();
	}

//

	protected static final Logger log = Loggers.getLogger(ChannelOperationsHandler.class);

}
