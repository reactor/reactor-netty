/*
 * Copyright (c) 2011-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.DecoderResultProvider;
import io.netty.handler.ssl.SslCloseCompletionEvent;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty.ReactorNetty.format;

/**
 * Netty {@link io.netty.channel.ChannelDuplexHandler} implementation that bridge data
 * via an IPC {@link NettyOutbound}.
 *
 * @author Stephane Maldini
 */
final class ChannelOperationsHandler extends ChannelInboundHandlerAdapter {

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
	public final void channelInactive(ChannelHandlerContext ctx) {
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
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
		if (evt instanceof SslCloseCompletionEvent) {
			SslCloseCompletionEvent sslCloseCompletionEvent = (SslCloseCompletionEvent) evt;

			// When a close_notify is received, the SSLHandler fires an SslCloseCompletionEvent.SUCCESS event,
			// so if the event is success and if the channel is still active (not closing for example),
			// then immediately close the channel.
			// see https://www.rfc-editor.org/rfc/rfc5246#section-7.2.1, which states that when receiving a close_notify,
			// then the connection must be closed down immediately.
			if (sslCloseCompletionEvent.isSuccess() && ctx.channel().isActive()) {
				if (log.isDebugEnabled()) {
					log.debug(format(ctx.channel(), "Received a TLS close_notify, closing the channel now."));
				}
				ctx.close();
			}
		}
		ReferenceCountUtil.release(evt);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public final void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg == null || msg == Unpooled.EMPTY_BUFFER || msg instanceof EmptyByteBuf) {
			return;
		}
		try {
			Connection connection = Connection.from(ctx.channel());
			ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
			if (ops != null) {
				ops.onInboundNext(ctx, msg);
			}
			else {
				if (msg instanceof DecoderResultProvider) {
					DecoderResult decoderResult = ((DecoderResultProvider) msg).decoderResult();
					if (decoderResult.isFailure()) {
						if (log.isDebugEnabled()) {
							log.debug(format(ctx.channel(), "Decoding failed."), decoderResult.cause());
						}

						//"FutureReturnValueIgnored" this is deliberate
						ctx.close();
						listener.onUncaughtException(connection, decoderResult.cause());
					}
				}

				if (log.isDebugEnabled()) {
					log.debug(format(ctx.channel(), "No ChannelOperation attached."));
				}

				ReferenceCountUtil.release(msg);
			}
		}
		catch (Throwable err) {
			safeRelease(msg);
			log.error(format(ctx.channel(), "Error was received while reading the incoming data." +
					" The connection will be closed."), err);
			//"FutureReturnValueIgnored" this is deliberate
			ctx.close();
			exceptionCaught(ctx, err);
		}
	}

	@Override
	public final void exceptionCaught(ChannelHandlerContext ctx, Throwable err) {
		Connection connection = Connection.from(ctx.channel());
		ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
		if (ops != null) {
			ops.onInboundError(err);
		}
		else {
			listener.onUncaughtException(connection, err);
		}
	}

	@Override
	public final void channelWritabilityChanged(ChannelHandlerContext ctx) {
		ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
		if (ops != null) {
			ops.onWritabilityChanged();
		}
	}

	static void safeRelease(Object msg) {
		if (msg instanceof ReferenceCounted) {
			ReferenceCounted referenceCounted = (ReferenceCounted) msg;
			if (referenceCounted.refCnt() > 0) {
				try {
					referenceCounted.release();
				}
				catch (IllegalReferenceCountException e) {
					if (log.isDebugEnabled()) {
						log.debug("", e);
					}
				}
			}
		}
	}

	static final Logger log = Loggers.getLogger(ChannelOperationsHandler.class);

}
