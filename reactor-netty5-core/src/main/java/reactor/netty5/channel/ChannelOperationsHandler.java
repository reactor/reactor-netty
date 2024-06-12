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
package reactor.netty5.channel;

import io.netty5.buffer.Buffer;
import io.netty5.util.Resource;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.DecoderResult;
import io.netty5.handler.codec.DecoderResultProvider;
import io.netty5.handler.ssl.SslCloseCompletionEvent;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.NettyOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.netty5.ReactorNetty.format;

/**
 * Netty {@link io.netty5.channel.ChannelHandlerAdapter} implementation that bridge data
 * via an IPC {@link NettyOutbound}.
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
			channelExceptionCaught(ctx, err);
		}
	}

	@Override
	public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
		if (evt instanceof SslCloseCompletionEvent sslCloseCompletionEvent) {
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
		Resource.dispose(evt);
	}

	@Override
	public final void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg == null) {
			return;
		}
		else if ((msg instanceof Buffer buffer) && buffer.readableBytes() == 0) {
			buffer.close();
			return;
		}
		try {
			Connection connection = Connection.from(ctx.channel());
			ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
			if (ops != null) {
				ops.onInboundNext(ctx, msg);
			}
			else {
				if (msg instanceof DecoderResultProvider decoderResultProvider) {
					DecoderResult decoderResult = decoderResultProvider.decoderResult();
					if (decoderResult.isFailure()) {
						if (log.isDebugEnabled()) {
							log.debug(format(ctx.channel(), "Decoding failed."), decoderResult.cause());
						}

						ctx.close();
						listener.onUncaughtException(connection, decoderResult.cause());
					}
				}

				if (log.isDebugEnabled()) {
					log.debug(format(ctx.channel(), "No ChannelOperation attached."));
				}

				Resource.dispose(msg);
			}
		}
		catch (Throwable err) {
			safeRelease(msg);
			log.error(format(ctx.channel(), "Error was received while reading the incoming data." +
					" The connection will be closed."), err);
			ctx.close();
			channelExceptionCaught(ctx, err);
		}
	}

	@Override
	public final void channelExceptionCaught(ChannelHandlerContext ctx, Throwable err) {
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
