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

import io.micrometer.api.instrument.Tags;
import io.micrometer.api.instrument.Timer;
import io.micrometer.api.instrument.observation.Observation;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.observability.ReactorNettyHandlerContext;

import java.net.SocketAddress;

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
// MicrometerSslReadHandler is Observation.Context and ChannelInboundHandler in order to reduce allocations,
// this is invoked on every TLS negotiation
final class MicrometerSslReadHandler extends Observation.Context implements ReactorNettyHandlerContext, ChannelOutboundHandler, ChannelInboundHandler {

	final String name;

	boolean handshakeDone;

	Observation observation;

	// remote address and status are not known beforehand
	String remoteAddress;
	String status;

	MicrometerSslReadHandler(MicrometerChannelMetricsRecorder recorder) {
		this.name = recorder.name() + TLS_HANDSHAKE_TIME;
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
	public Tags getHighCardinalityTags() {
		return Tags.of("reactor.netty.status", status);
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

	@Override
	public String getContextualName() {
		return "tls handshake";
	}

	@Override
	public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {

	}

	@Override
	public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) throws Exception {
		ctx.connect(remoteAddress, localAddress, promise).addListener(future -> {
			observation = Observation.start(this.name, this, REGISTRY);
		});
	}

	@Override
	public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {

	}

	@Override
	public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {

	}

	@Override
	public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {

	}

	@Override
	public void read(ChannelHandlerContext ctx) throws Exception {

	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

	}

	@Override
	public void flush(ChannelHandlerContext ctx) throws Exception {

	}
}