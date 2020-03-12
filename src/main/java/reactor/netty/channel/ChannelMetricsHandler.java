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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import reactor.netty.NettyPipeline;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.SUCCESS;

/**
 * @author Violeta Georgieva
 */
public class ChannelMetricsHandler extends ChannelDuplexHandler {
	final ChannelMetricsRecorder recorder;

	final SocketAddress remoteAddress;

	final boolean onServer;


	ChannelMetricsHandler(ChannelMetricsRecorder recorder, @Nullable SocketAddress remoteAddress, boolean onServer) {
		this.recorder = recorder;
		this.remoteAddress = remoteAddress;
		this.onServer = onServer;
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) {
		if (!onServer) {
			ctx.pipeline()
			   .addAfter(NettyPipeline.ChannelMetricsHandler,
			             NettyPipeline.ConnectMetricsHandler,
			             new ConnectMetricsHandler(recorder));
		}

		ctx.fireChannelRegistered();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof ByteBuf) {
			ByteBuf buffer = (ByteBuf) msg;
			if (buffer.readableBytes() > 0) {
				recorder.recordDataReceived(remoteAddress, buffer.readableBytes());
			}
		}
		else if (msg instanceof DatagramPacket) {
			DatagramPacket p = (DatagramPacket) msg;
			ByteBuf buffer = p.content();
			if (buffer.readableBytes() > 0) {
				if (remoteAddress != null) {
					recorder.recordDataReceived(remoteAddress, buffer.readableBytes());
				}
				else {
					recorder.recordDataReceived(p.sender(), buffer.readableBytes());
				}
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
				recorder.recordDataSent(remoteAddress, buffer.readableBytes());
			}
		}
		else if (msg instanceof DatagramPacket) {
			DatagramPacket p = (DatagramPacket) msg;
			ByteBuf buffer = p.content();
			if (buffer.readableBytes() > 0) {
				if (remoteAddress != null) {
					recorder.recordDataSent(remoteAddress, buffer.readableBytes());
				}
				else {
					recorder.recordDataSent(p.recipient(), buffer.readableBytes());
				}
			}
		}

		//"FutureReturnValueIgnored" this is deliberate
		ctx.write(msg, promise);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		if (remoteAddress != null) {
			recorder.incrementErrorsCount(remoteAddress);
		}
		else {
			recorder.incrementErrorsCount(ctx.channel().remoteAddress());
		}

		ctx.fireExceptionCaught(cause);
	}

	public ChannelMetricsRecorder recorder() {
		return recorder;
	}

	static final class ConnectMetricsHandler extends ChannelOutboundHandlerAdapter {

		final ChannelMetricsRecorder recorder;

		ConnectMetricsHandler(ChannelMetricsRecorder recorder) {
			this.recorder = recorder;
		}

		@Override
		public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
				SocketAddress localAddress, ChannelPromise promise) throws Exception {
			long connectTimeStart = System.nanoTime();
			super.connect(ctx, remoteAddress, localAddress, promise);
			promise.addListener(future -> {
				ctx.pipeline().remove(this);

				String status;
				if (future.isSuccess()) {
					status = SUCCESS;
				}
				else {
					status = ERROR;
				}
				recorder.recordConnectTime(
						remoteAddress,
						Duration.ofNanos(System.nanoTime() - connectTimeStart),
						status);
			});
		}
	}
}
