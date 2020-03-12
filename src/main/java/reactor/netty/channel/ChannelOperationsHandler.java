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

package reactor.netty.channel;

import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.DecoderResultProvider;
import io.netty.util.ReferenceCountUtil;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.toPrettyHexDump;

/**
 * Netty {@link io.netty.channel.ChannelDuplexHandler} implementation that bridge data
 * via an IPC {@link NettyOutbound}
 *
 * @author Stephane Maldini
 */
final class ChannelOperationsHandler extends ChannelInboundHandlerAdapter {

	final ConnectionObserver        listener;
	final ChannelOperations.OnSetup opsFactory;


	@SuppressWarnings("unchecked")
	ChannelOperationsHandler(ChannelOperations.OnSetup opsFactory, ConnectionObserver listener) {
		this.listener = listener;
		this.opsFactory = opsFactory;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		Connection c = Connection.from(ctx.channel());
		listener.onStateChange(c, ConnectionObserver.State.CONNECTED);
		ChannelOperations<?, ?> ops = opsFactory.create(c, listener, null);
		if (ops != null) {
			ops.bind();
			listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
		}
	}

	@Override
	final public void channelInactive(ChannelHandlerContext ctx) {
		try {
			Connection connection = Connection.from(ctx.channel());
			ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
			if (ops != null) {
				ops.onInboundClose();
			}
			else {
				listener.onStateChange(connection, ConnectionObserver.State.DISCONNECTING);
			}
		}
		catch (Throwable err) {
			exceptionCaught(ctx, err);
		}
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	final public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg == null || msg == Unpooled.EMPTY_BUFFER || msg instanceof EmptyByteBuf) {
			return;
		}
		try {
			ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
			if (ops != null) {
				ops.onInboundNext(ctx, msg);
			}
			else {
				if (log.isDebugEnabled()) {
					if (msg instanceof DecoderResultProvider) {
						DecoderResult decoderResult = ((DecoderResultProvider) msg).decoderResult();
						if (decoderResult.isFailure()) {
							log.debug(format(ctx.channel(), "Decoding failed: " + msg + " : "),
									decoderResult.cause());
						}
					}

					log.debug(format(ctx.channel(), "No ChannelOperation attached. Dropping: {}"),
							toPrettyHexDump(msg));
				}
				ReferenceCountUtil.release(msg);
			}
		}
		catch (Throwable err) {
			ReferenceCountUtil.safeRelease(msg);
			log.error(format(ctx.channel(), "Error was received while reading the incoming data." +
					" The connection will be closed."), err);
			//"FutureReturnValueIgnored" this is deliberate
			ctx.close();
			exceptionCaught(ctx, err);
		}
	}

	@Override
	final public void exceptionCaught(ChannelHandlerContext ctx, Throwable err) {
		Connection connection = Connection.from(ctx.channel());
		ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
		if (ops != null) {
			ops.onInboundError(err);
		}
		else {
			listener.onUncaughtException(connection, err);
		}
	}

	static final Logger log = Loggers.getLogger(ChannelOperationsHandler.class);

}
