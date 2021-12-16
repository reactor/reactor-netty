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
package reactor.netty.tcp;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;

import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * @author Violeta Georgieva
 * @since 1.1.0
 */
// MicrometerSslReadHandler is Timer.HandlerContext and ChannelInboundHandler in order to reduce allocations,
// this is invoked on every TLS negotiation
final class MicrometerSslReadHandler extends Timer.HandlerContext implements ChannelInboundHandler {

	final Timer.Builder tlsHandshakeTimeBuilder;

	boolean handshakeDone;

	Timer.Sample sample;

	// remote address and status are not known beforehand
	String remoteAddress;
	String status;

	MicrometerSslReadHandler(MicrometerChannelMetricsRecorder recorder) {
		this.tlsHandshakeTimeBuilder =
				Timer.builder(recorder.name() + TLS_HANDSHAKE_TIME)
				     .description("Time spent for TLS handshake");
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		ctx.read(); //consume handshake
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		ctx.fireChannelInactive();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		ctx.fireChannelRead(msg);
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) {
		if (!handshakeDone) {
			ctx.read(); /* continue consuming. */
		}
		ctx.fireChannelReadComplete();
	}

	@Override
	public void channelRegistered(ChannelHandlerContext ctx) {
		sample = Timer.start(REGISTRY, this);

		ctx.fireChannelRegistered();
	}

	@Override
	public void channelUnregistered(ChannelHandlerContext ctx) {
		ctx.fireChannelUnregistered();
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) {
		ctx.fireChannelWritabilityChanged();
	}

	@Override
	@SuppressWarnings("deprecation")
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		ctx.fireExceptionCaught(cause);
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		// noop
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		// noop
	}

	@Override
	public Tags getLowCardinalityTags() {
		return Tags.of(REMOTE_ADDRESS, remoteAddress, STATUS, status);
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
		if (evt instanceof SslHandshakeCompletionEvent) {
			handshakeDone = true;
			if (ctx.pipeline().context(this) != null) {
				ctx.pipeline().remove(this);
			}
			SslHandshakeCompletionEvent handshake = (SslHandshakeCompletionEvent) evt;
			// TODO
			// Cannot invoke the recorder any more:
			// 1. The recorder is one instance only, it is invoked for all TLS negotiations that can happen
			// 2. The recorder does not have knowledge about TLS negotiation lifecycle
			//
			// Move the implementation from the recorder here
			//
			// Important:
			// Cannot cache the Timer anymore - need to test the performance
			// Can we use sample.stop(Timer)
			this.remoteAddress = formatSocketAddress(ctx.channel().remoteAddress());
			if (handshake.isSuccess()) {
				this.status = SUCCESS;
				sample.stop(tlsHandshakeTimeBuilder);
				ctx.fireChannelActive();
			}
			else {
				this.status = ERROR;
				sample.stop(tlsHandshakeTimeBuilder);
				ctx.fireExceptionCaught(handshake.cause());
			}
		}
		ctx.fireUserEventTriggered(evt);
	}
}