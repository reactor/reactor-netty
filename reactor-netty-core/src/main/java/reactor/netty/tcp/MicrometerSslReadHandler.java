/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.api.instrument.Tags;
import io.micrometer.api.instrument.observation.Observation;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.observability.ReactorNettyHandlerContext;

import java.net.SocketAddress;

import static reactor.netty.Metrics.ERROR;
//import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
// MicrometerSslReadHandler is Observation.Context and ChannelInboundHandler in order to reduce allocations,
// this is invoked on every TLS negotiation
// This handler is not shared and as such it is different object per connection.
final class MicrometerSslReadHandler extends Observation.Context implements ReactorNettyHandlerContext, ChannelInboundHandler {
	static final io.micrometer.api.instrument.MeterRegistry REGISTRY = io.micrometer.api.instrument.Metrics.globalRegistry;

	static final String CONTEXTUAL_NAME = "tls handshake";
	static final String TYPE_CLIENT = "client";
	static final String TYPE_SERVER = "server";

	final MicrometerChannelMetricsRecorder recorder;
	final String type;

	boolean handshakeDone;

	Observation observation;

	// remote address and status are not known beforehand
	String remoteAddress;
	String status;

	MicrometerSslReadHandler(MicrometerChannelMetricsRecorder recorder, boolean onServer) {
		this.recorder = recorder;
		this.type = onServer ? TYPE_SERVER : TYPE_CLIENT;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		observation = Observation.start(recorder.name() + TLS_HANDSHAKE_TIME, this, REGISTRY);
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
	public String getContextualName() {
		return CONTEXTUAL_NAME;
	}

	@Override
	public Tags getHighCardinalityTags() {
		// TODO cache
		return Tags.of(TlsHandshakeObservations.TlsHandshakeTimeHighCardinalityTags.REACTOR_NETTY_STATUS.of(status),
		               TlsHandshakeObservations.TlsHandshakeTimeHighCardinalityTags.REACTOR_NETTY_TYPE.of(type),
		               TlsHandshakeObservations.TlsHandshakeTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL.of(recorder.protocol()));
	}

	@Override
	public Tags getLowCardinalityTags() {
		// TODO cache
		return Tags.of(TlsHandshakeObservations.TlsHandshakeTimeLowCardinalityTags.REMOTE_ADDRESS.of(remoteAddress),
		               TlsHandshakeObservations.TlsHandshakeTimeLowCardinalityTags.STATUS.of(status));
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
			put(SocketAddress.class, ctx.channel().remoteAddress());
			this.remoteAddress = formatSocketAddress(ctx.channel().remoteAddress());
			if (handshake.isSuccess()) {
				this.status = SUCCESS;
				observation.stop();
				ctx.fireChannelActive();
			}
			else {
				this.status = ERROR;
				observation.stop();
				ctx.fireExceptionCaught(handshake.cause());
			}
		}
		ctx.fireUserEventTriggered(evt);
	}
}
