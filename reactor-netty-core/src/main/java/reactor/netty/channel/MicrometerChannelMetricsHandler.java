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
package reactor.netty.channel;

import io.micrometer.common.KeyValues;
import io.micrometer.contextpropagation.ContextContainer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.handler.ssl.SslHandler;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;

import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.OBSERVATION_REGISTRY;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.formatSocketAddress;
import static reactor.netty.channel.ConnectObservations.ConnectTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL;
import static reactor.netty.channel.ConnectObservations.ConnectTimeHighCardinalityTags.REACTOR_NETTY_STATUS;
import static reactor.netty.channel.ConnectObservations.ConnectTimeHighCardinalityTags.REACTOR_NETTY_TYPE;
import static reactor.netty.channel.ConnectObservations.ConnectTimeLowCardinalityTags.REMOTE_ADDRESS;
import static reactor.netty.channel.ConnectObservations.ConnectTimeLowCardinalityTags.STATUS;

/**
 * {@link ChannelHandler} for collecting metrics on protocol level.
 * This class is based on {@link ChannelMetricsHandler}, but it utilizes Micrometer's {@link Observation}.
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public final class MicrometerChannelMetricsHandler extends AbstractChannelMetricsHandler {

	final MicrometerChannelMetricsRecorder recorder;

	MicrometerChannelMetricsHandler(MicrometerChannelMetricsRecorder recorder, @Nullable SocketAddress remoteAddress, boolean onServer) {
		super(remoteAddress, onServer);
		this.recorder = recorder;
	}

	@Override
	public ChannelHandler connectMetricsHandler() {
		return new ConnectMetricsHandler(recorder);
	}
	@Override
	public ChannelHandler tlsMetricsHandler() {
		return new TlsMetricsHandler(recorder, onServer);
	}

	@Override
	public MicrometerChannelMetricsRecorder recorder() {
		return recorder;
	}

	// ConnectMetricsHandler is Observation.Context and ChannelOutboundHandler in order to reduce allocations,
	// this is invoked on every connection establishment
	// This handler is not shared and as such it is different object per connection.
	static final class ConnectMetricsHandler extends Observation.Context implements ReactorNettyHandlerContext, ChannelOutboundHandler {
		static final String CONTEXTUAL_NAME = "connect";
		static final String TYPE = "client";

		final MicrometerChannelMetricsRecorder recorder;

		// remote address and status are not known beforehand
		String remoteAddress;
		String status;

		ConnectMetricsHandler(MicrometerChannelMetricsRecorder recorder) {
			this.recorder = recorder;
		}

		@Override
		public Timer getTimer() {
			return recorder.getConnectTimer(getName(), remoteAddress, status);
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.bind(localAddress, promise);
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.close(promise);
		}

		@Override
		@SuppressWarnings("try")
		public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
				SocketAddress localAddress, ChannelPromise promise) {
			// Cannot invoke the recorder anymore:
			// 1. The recorder is one instance only, it is invoked for all connection establishments that can happen
			// 2. The recorder does not have knowledge about connection establishment lifecycle
			//
			// Move the implementation from the recorder here
			this.remoteAddress = formatSocketAddress(remoteAddress);
			ContextContainer container = ContextContainer.restore(ctx.channel());
			Observation observation;
			try (ContextContainer.Scope scope = container.restoreThreadLocalValues()) {
				observation = Observation.start(recorder.name() + CONNECT_TIME, this, OBSERVATION_REGISTRY);
			}
			ctx.connect(remoteAddress, localAddress, promise)
			   .addListener(future -> {
			       ctx.pipeline().remove(this);

			       status = future.isSuccess() ? SUCCESS : ERROR;

			       observation.stop();
			});
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.deregister(promise);
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.disconnect(promise);
		}

		@Override
		@SuppressWarnings("deprecation")
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ctx.fireExceptionCaught(cause);
		}

		@Override
		public void flush(ChannelHandlerContext ctx) {
			ctx.flush();
		}

		@Override
		public String getContextualName() {
			return CONTEXTUAL_NAME;
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(REACTOR_NETTY_PROTOCOL.getKeyName(), recorder.protocol(),
					REACTOR_NETTY_STATUS.getKeyName(), status, REACTOR_NETTY_TYPE.getKeyName(), TYPE);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(REMOTE_ADDRESS.getKeyName(), remoteAddress, STATUS.getKeyName(), status);
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
		public void read(ChannelHandlerContext ctx) {
			ctx.read();
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}
	}

	static final class TlsMetricsHandler extends Observation.Context implements ReactorNettyHandlerContext, ChannelInboundHandler {
		static final String CONTEXTUAL_NAME = "tls handshake";
		static final String TYPE_CLIENT = "client";
		static final String TYPE_SERVER = "server";

		final MicrometerChannelMetricsRecorder recorder;
		final String type;
		Observation observation;

		// remote address and status are not known beforehand
		String remoteAddress;
		String status;

		TlsMetricsHandler(MicrometerChannelMetricsRecorder recorder, boolean onServer) {
			this.recorder = recorder;
			this.type = onServer ? TYPE_SERVER : TYPE_CLIENT;
		}

		@Override
		@SuppressWarnings("try")
		public void channelActive(ChannelHandlerContext ctx) {
			this.remoteAddress = formatSocketAddress(ctx.channel().remoteAddress());
			ContextContainer container = ContextContainer.restore(ctx.channel());
			try (ContextContainer.Scope scope = container.restoreThreadLocalValues()) {
				observation = Observation.start(recorder.name() + TLS_HANDSHAKE_TIME, this, OBSERVATION_REGISTRY);
			}
			ctx.pipeline().get(SslHandler.class)
					.handshakeFuture()
					.addListener(f -> {
						ctx.pipeline().remove(this);
						status = f.isSuccess() ? SUCCESS : ERROR;
						observation.stop();
					});

			ctx.fireChannelActive();
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
		public String getContextualName() {
			return CONTEXTUAL_NAME;
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(REACTOR_NETTY_PROTOCOL.getKeyName(), recorder.protocol(),
					REACTOR_NETTY_STATUS.getKeyName(), status, REACTOR_NETTY_TYPE.getKeyName(), type);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(REMOTE_ADDRESS.getKeyName(), remoteAddress, STATUS.getKeyName(), status);
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
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
			ctx.fireUserEventTriggered(evt);
		}

		@Override
		public Timer getTimer() {
			return recorder.getTlsHandshakeTimer(getName(), remoteAddress, status);
		}
	}
}
