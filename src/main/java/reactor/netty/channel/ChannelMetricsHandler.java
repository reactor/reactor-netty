/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import static reactor.netty.Metrics.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import reactor.netty.NettyPipeline;

import javax.annotation.Nullable;
import java.net.SocketAddress;

/**
 * @author Violeta Georgieva
 */
public class ChannelMetricsHandler extends ChannelDuplexHandler {

	final MeterRegistry registry = Metrics.globalRegistry;

	final String name;

	final String remoteAddress;

	final String protocol;

	final boolean onServer;


	final DistributionSummary.Builder dataReceivedBuilder;
	DistributionSummary dataReceived;

	final DistributionSummary.Builder dataSentBuilder;
	DistributionSummary dataSent;

	final Counter.Builder errorCountBuilder;
	Counter errorCount;


	ChannelMetricsHandler(String name, @Nullable String remoteAddress, String protocol, boolean onServer) {
		this.name = name;
		if (remoteAddress == null && !"udp".equals(protocol)) {
			throw new IllegalArgumentException("remoteAddress is null for protocol " + protocol);
		}
		this.remoteAddress = remoteAddress;
		this.protocol = protocol;
		this.onServer = onServer;

		this.dataReceivedBuilder =
				DistributionSummary.builder(name + DATA_RECEIVED)
				                   .baseUnit("bytes")
				                   .description("Amount of the data that is received, in bytes")
				                   .tag(URI, protocol);

		this.dataSentBuilder =
				DistributionSummary.builder(name + DATA_SENT)
				                   .baseUnit("bytes")
				                   .description("Amount of the data that is sent, in bytes")
				                   .tag(URI, protocol);

		this.errorCountBuilder =
				Counter.builder(name + ERRORS)
				       .description("Number of the errors that are occurred")
				       .tag(URI, protocol);

		if (remoteAddress != null) {
			this.dataReceivedBuilder.tag(REMOTE_ADDRESS, remoteAddress);
			this.dataReceived = dataReceivedBuilder.register(registry);

			this.dataSentBuilder.tag(REMOTE_ADDRESS, remoteAddress);
			this.dataSent = dataSentBuilder.register(registry);

			this.errorCountBuilder.tag(REMOTE_ADDRESS, remoteAddress);
			this.errorCount = errorCountBuilder.register(registry);
		}
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
		if (!onServer) {
			ctx.pipeline()
			   .addAfter(NettyPipeline.TcpMetricsHandler,
			             NettyPipeline.ConnectMetricsHandler,
			             new ConnectMetricsHandler(registry, name, remoteAddress));
		}

		if (ctx.pipeline().get(SslHandler.class) != null) {
			ctx.pipeline()
			   .addAfter(NettyPipeline.SslHandler,
			             NettyPipeline.SslMetricsHandler,
			             new TlsMetricsHandler(registry, name, remoteAddress));
		}

		super.channelRegistered(ctx);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof ByteBuf) {
			ByteBuf buffer = (ByteBuf) msg;
			if (buffer.readableBytes() > 0) {
				dataReceived.record(buffer.readableBytes());
			}
		}
		else if (msg instanceof DatagramPacket) {
			DatagramPacket p = (DatagramPacket) msg;
			ByteBuf buffer = p.content();
			if (buffer.readableBytes() > 0) {
				if (dataReceived == null) {
					dataReceivedBuilder.tag(REMOTE_ADDRESS, reactor.netty.Metrics.formatSocketAddress(p.sender()))
					                   .register(registry)
					                   .record(buffer.readableBytes());

				}
				else {
					dataReceived.record(buffer.readableBytes());
				}
			}
		}

		super.channelRead(ctx, msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (msg instanceof ByteBuf) {
			ByteBuf buffer = (ByteBuf) msg;
			if (buffer.readableBytes() > 0) {
				dataSent.record(buffer.readableBytes());
			}
		}
		else if (msg instanceof DatagramPacket) {
			DatagramPacket p = (DatagramPacket) msg;
			ByteBuf buffer = p.content();
			if (buffer.readableBytes() > 0) {
				if (dataSent == null) {
					dataSentBuilder.tag(REMOTE_ADDRESS, reactor.netty.Metrics.formatSocketAddress(p.recipient()))
					               .register(registry)
					               .record(buffer.readableBytes());

				}
				else {
					dataSent.record(buffer.readableBytes());
				}
			}
		}

		super.write(ctx, msg, promise);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		if (errorCount == null) {
			String address = reactor.netty.Metrics.formatSocketAddress(ctx.channel().remoteAddress());
			if (address != null) {
				errorCountBuilder.tag(REMOTE_ADDRESS, address)
				                 .register(registry)
				                 .increment();
			}
		}
		else {
			errorCount.increment();
		}

		super.exceptionCaught(ctx, cause);
	}

	public MeterRegistry registry() {
		return registry;
	}

	public String name() {
		return name;
	}


	static final class TlsMetricsHandler extends ChannelInboundHandlerAdapter {

		Timer.Sample tlsHandshakeTimeSample;


		final MeterRegistry registry;

		final String name;

		final String remoteAddress;

		TlsMetricsHandler(MeterRegistry registry, String name, String remoteAddress) {
			this.registry = registry;
			this.name = name;
			this.remoteAddress = remoteAddress;
			this.tlsHandshakeTimeSample = Timer.start(registry);
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			if (evt instanceof SslHandshakeCompletionEvent) {
				ctx.pipeline().remove(this);

				SslHandshakeCompletionEvent handshake = (SslHandshakeCompletionEvent) evt;
				String status;
				if (handshake.isSuccess()) {
					status = SUCCESS;
				}
				else {
					status = ERROR;
				}

				Timer tlsHandshakeTime =
						Timer.builder(name + TLS_HANDSHAKE_TIME)
						     .tags(REMOTE_ADDRESS, remoteAddress, STATUS, status)
						     .description("Time that is spent for TLS handshake")
						     .register(registry);
				tlsHandshakeTimeSample.stop(tlsHandshakeTime);
			}

			super.userEventTriggered(ctx, evt);
		}

	}

	static final class ConnectMetricsHandler extends ChannelOutboundHandlerAdapter {

		Timer.Sample connectTimeSample;


		final MeterRegistry registry;

		final String name;

		final String remoteAddress;

		ConnectMetricsHandler(MeterRegistry registry, String name, String remoteAddress) {
			this.registry = registry;
			this.name = name;
			this.remoteAddress = remoteAddress;
		}

		@Override
		public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
				SocketAddress localAddress, ChannelPromise promise) throws Exception {
			connectTimeSample = Timer.start(registry);
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

				Timer connectTime =
						Timer.builder(name + CONNECT_TIME)
						     .tags(REMOTE_ADDRESS, this.remoteAddress, STATUS, status)
						     .description("Time that is spent for connecting to the remote address")
						     .register(registry);
				connectTimeSample.stop(connectTime);
			});
		}
	}
}
