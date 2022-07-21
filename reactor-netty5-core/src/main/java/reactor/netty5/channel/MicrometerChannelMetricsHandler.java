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
package reactor.netty5.channel;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.ssl.SslHandler;
import io.netty5.util.AttributeKey;
import io.netty5.util.concurrent.Future;
import reactor.netty5.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;

import java.net.SocketAddress;

import static reactor.netty5.Metrics.CONNECT_TIME;
import static reactor.netty5.Metrics.ERROR;
import static reactor.netty5.Metrics.OBSERVATION_KEY;
import static reactor.netty5.Metrics.OBSERVATION_REGISTRY;
import static reactor.netty5.Metrics.SUCCESS;
import static reactor.netty5.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty5.Metrics.formatSocketAddress;
import static reactor.netty5.channel.ConnectObservations.ConnectTimeHighCardinalityTags.REACTOR_NETTY_PROTOCOL;
import static reactor.netty5.channel.ConnectObservations.ConnectTimeHighCardinalityTags.REACTOR_NETTY_STATUS;
import static reactor.netty5.channel.ConnectObservations.ConnectTimeHighCardinalityTags.REACTOR_NETTY_TYPE;
import static reactor.netty5.channel.ConnectObservations.ConnectTimeLowCardinalityTags.REMOTE_ADDRESS;
import static reactor.netty5.channel.ConnectObservations.ConnectTimeLowCardinalityTags.STATUS;

/**
 * {@link ChannelHandler} for collecting metrics on protocol level.
 * This class is based on {@link ChannelMetricsHandler}, but it utilizes Micrometer's {@link Observation}.
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public final class MicrometerChannelMetricsHandler extends AbstractChannelMetricsHandler {
	static final AttributeKey<ContextView> CONTEXT_VIEW = AttributeKey.valueOf("$CONTEXT_VIEW");

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

	// ConnectMetricsHandler is Observation.Context and ChannelHandler in order to reduce allocations,
	// this is invoked on every connection establishment
	// This handler is not shared and as such it is different object per connection.
	static final class ConnectMetricsHandler extends Observation.Context implements ReactorNettyHandlerContext, ChannelHandler {
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
		@SuppressWarnings("try")
		public Future<Void> connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress) {
			// Cannot invoke the recorder anymore:
			// 1. The recorder is one instance only, it is invoked for all connection establishments that can happen
			// 2. The recorder does not have knowledge about connection establishment lifecycle
			//
			// Move the implementation from the recorder here
			this.remoteAddress = formatSocketAddress(remoteAddress);
			Observation observation = Observation.createNotStarted(recorder.name() + CONNECT_TIME, this, OBSERVATION_REGISTRY);
			if (ctx.channel().hasAttr(CONTEXT_VIEW)) {
				ContextView contextView = ctx.channel().attr(CONTEXT_VIEW).get();
				if (contextView.hasKey(OBSERVATION_KEY)) {
					observation.parentObservation(contextView.get(OBSERVATION_KEY));
				}
			}
			observation.start();
			return ctx.connect(remoteAddress, localAddress)
			          .addListener(future -> {
			              ctx.pipeline().remove(this);

			              status = future.isSuccess() ? SUCCESS : ERROR;

			              observation.stop();
			});
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
	}

	static final class TlsMetricsHandler extends Observation.Context implements ReactorNettyHandlerContext, ChannelHandler {
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
			observation = Observation.createNotStarted(recorder.name() + TLS_HANDSHAKE_TIME, this, OBSERVATION_REGISTRY);
			if (ctx.channel().hasAttr(CONTEXT_VIEW)) {
				ContextView contextView = ctx.channel().attr(CONTEXT_VIEW).get();
				if (contextView.hasKey(OBSERVATION_KEY)) {
					observation.parentObservation(contextView.get(OBSERVATION_KEY));
				}
			}
			observation.start();
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
		public Timer getTimer() {
			return recorder.getTlsHandshakeTimer(getName(), remoteAddress, status);
		}
	}
}
