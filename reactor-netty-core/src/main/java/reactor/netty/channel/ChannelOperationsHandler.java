/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.channel;

import io.netty5.buffer.api.Buffer;
import io.netty5.util.Resource;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.DecoderResult;
import io.netty5.handler.codec.DecoderResultProvider;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.toPrettyHexDump;

/**
 * Netty {@link io.netty5.channel.ChannelHandlerAdapter} implementation that bridge data
 * via an IPC {@link NettyOutbound}
 *
 * @author Stephane Maldini
 */
final class ChannelOperationsHandler extends ChannelHandlerAdapter {

	final ConnectionObserver        listener;
	final ChannelOperations.OnSetup opsFactory;

	ChannelOperationsHandler(ChannelOperations.OnSetup opsFactory, ConnectionObserver listener) {
		this.listener = listener;
		this.opsFactory = opsFactory;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		// When AbstractNioChannel.AbstractNioUnsafe.finishConnect/fulfillConnectPromise,
		// fireChannelActive will be triggered regardless that the channel might be closed in the meantime
		if (ctx.channel().isActive()) {
			Connection c = Connection.from(ctx.channel());
			listener.onStateChange(c, ConnectionObserver.State.CONNECTED);
			ChannelOperations<?, ?> ops = opsFactory.create(c, listener, null);
			if (ops != null) {
				ops.bind();
				listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
			}
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
			channelExceptionCaught(ctx, err);
		}
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	final public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg == null) {
			return;
		}
		else if ((msg instanceof Buffer buffer) && buffer.readableBytes() == 0) {
			buffer.close();
			return;
		}
		try {
			ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
			if (ops != null) {
				ops.onInboundNext(ctx, msg);
			}
			else {
				if (log.isDebugEnabled()) {
					if (msg instanceof DecoderResultProvider decoderResultProvider) {
						DecoderResult decoderResult = decoderResultProvider.decoderResult();
						if (decoderResult.isFailure()) {
							log.debug(format(ctx.channel(), "Decoding failed: " + msg + " : "),
									decoderResult.cause());
						}
					}

					log.debug(format(ctx.channel(), "No ChannelOperation attached. Dropping: {}"),
							toPrettyHexDump(msg));
				}
				Resource.dispose(msg);
			}
		}
		catch (Throwable err) {
			safeRelease(msg);
			log.error(format(ctx.channel(), "Error was received while reading the incoming data." +
					" The connection will be closed."), err);
			//"FutureReturnValueIgnored" this is deliberate
			ctx.close();
			channelExceptionCaught(ctx, err);
		}
	}

	@Override
	final public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable err) {
		Connection connection = Connection.from(ctx.channel());
		ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
		if (ops != null) {
			ops.onInboundError(err);
		}
		else {
			listener.onUncaughtException(connection, err);
		}
	}

	static void safeRelease(Object msg) {
		if (msg instanceof Resource<?> resource && resource.isAccessible()) {
			try {
				resource.close();
			}
			catch (RuntimeException e) {
				if (log.isDebugEnabled()) {
					log.debug("", e);
				}
			}
		}
	}

	static final Logger log = Loggers.getLogger(ChannelOperationsHandler.class);

}
