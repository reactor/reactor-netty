/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import reactor.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;

import static reactor.netty.ReactorNetty.format;

/**
 * Base {@link ChannelHandler} for collecting metrics on protocol level.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
public abstract class AbstractChannelMetricsHandler extends ChannelDuplexHandler {

	private static final Logger log = Loggers.getLogger(AbstractChannelMetricsHandler.class);

	final SocketAddress remoteAddress;

	final boolean onServer;

	protected AbstractChannelMetricsHandler(@Nullable SocketAddress remoteAddress, boolean onServer) {
		this.remoteAddress = remoteAddress;
		this.onServer = onServer;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		if (onServer) {
			try {
				recorder().recordServerConnectionOpened(ctx.channel().localAddress());
			}
			catch (RuntimeException e) {
				if (log.isWarnEnabled()) {
					log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				}
				// Allow request-response exchange to continue, unaffected by metrics problem
			}
		}
		ctx.fireChannelActive();
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		if (onServer) {
			try {
				recorder().recordServerConnectionClosed(ctx.channel().localAddress());
			}
			catch (RuntimeException e) {
				if (log.isWarnEnabled()) {
					log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				}
				// Allow request-response exchange to continue, unaffected by metrics problem
			}
		}
		ctx.fireChannelInactive();
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) {
		if (!onServer) {
			ctx.pipeline()
			   .addAfter(NettyPipeline.ChannelMetricsHandler,
			             NettyPipeline.ConnectMetricsHandler,
			             connectMetricsHandler());
		}
		if (ctx.pipeline().get(NettyPipeline.SslHandler) != null) {
			ctx.pipeline()
				.addBefore(NettyPipeline.SslHandler,
						 NettyPipeline.TlsMetricsHandler,
						 tlsMetricsHandler());
		}

		ctx.fireChannelRegistered();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof ByteBuf) {
				ByteBuf buffer = (ByteBuf) msg;
				if (buffer.readableBytes() > 0) {
					recordRead(ctx, remoteAddress, buffer.readableBytes());
				}
			}
			else if (msg instanceof DatagramPacket) {
				DatagramPacket p = (DatagramPacket) msg;
				ByteBuf buffer = p.content();
				if (buffer.readableBytes() > 0) {
					recordRead(ctx, remoteAddress != null ? remoteAddress : p.sender(), buffer.readableBytes());
				}
			}
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
			// Allow request-response exchange to continue, unaffected by metrics problem
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		try {
			if (msg instanceof ByteBuf) {
				ByteBuf buffer = (ByteBuf) msg;
				if (buffer.readableBytes() > 0) {
					recordWrite(ctx, remoteAddress, buffer.readableBytes());
				}
			}
			else if (msg instanceof DatagramPacket) {
				DatagramPacket p = (DatagramPacket) msg;
				ByteBuf buffer = p.content();
				if (buffer.readableBytes() > 0) {
					recordWrite(ctx, remoteAddress != null ? remoteAddress : p.recipient(), buffer.readableBytes());
				}
			}
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
			// Allow request-response exchange to continue, unaffected by metrics problem
		}

		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		try {
			recordException(ctx, remoteAddress != null ? remoteAddress : ctx.channel().remoteAddress());
		}
		catch (RuntimeException e) {
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
			// Allow request-response exchange to continue, unaffected by metrics problem
		}

		ctx.fireExceptionCaught(cause);
	}

	public abstract ChannelHandler connectMetricsHandler();
	public abstract ChannelHandler tlsMetricsHandler();

	public abstract ChannelMetricsRecorder recorder();

	protected void recordException(ChannelHandlerContext ctx, SocketAddress address) {
		recorder().incrementErrorsCount(address);
	}

	protected void recordRead(ChannelHandlerContext ctx, SocketAddress address, long bytes) {
		recorder().recordDataReceived(address, bytes);
	}

	protected void recordWrite(ChannelHandlerContext ctx, SocketAddress address, long bytes) {
		recorder().recordDataSent(address, bytes);
	}
}
