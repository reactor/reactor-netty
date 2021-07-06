/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;

/**
 * Base {@link ChannelHandler} for collecting metrics on protocol level.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
public abstract class AbstractChannelMetricsHandler extends ChannelDuplexHandler {

	final SocketAddress remoteAddress;

	final boolean onServer;

	protected AbstractChannelMetricsHandler(@Nullable SocketAddress remoteAddress, boolean onServer) {
		this.remoteAddress = remoteAddress;
		this.onServer = onServer;
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) {
		if (!onServer) {
			ctx.pipeline()
			   .addAfter(NettyPipeline.ChannelMetricsHandler,
			             NettyPipeline.ConnectMetricsHandler,
			             connectMetricsHandler());
		}

		ctx.fireChannelRegistered();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
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

		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
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

		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		recordException(ctx, remoteAddress != null ? remoteAddress : ctx.channel().remoteAddress());

		ctx.fireExceptionCaught(cause);
	}

	public abstract ChannelHandler connectMetricsHandler();

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
