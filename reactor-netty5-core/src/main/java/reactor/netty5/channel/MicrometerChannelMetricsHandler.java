/*
 * Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty5.util.concurrent.Future;
import reactor.netty5.ReactorNetty;
import reactor.netty5.observability.ReactorNettyHandlerContext;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Supplier;

import static reactor.netty5.Metrics.CONNECT_TIME;
import static reactor.netty5.Metrics.ERROR;
import static reactor.netty5.Metrics.OBSERVATION_REGISTRY;
import static reactor.netty5.Metrics.SUCCESS;
import static reactor.netty5.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty5.Metrics.UNKNOWN;
import static reactor.netty5.Metrics.updateChannelContext;
import static reactor.netty5.channel.ConnectObservations.ConnectTimeHighCardinalityTags.NET_PEER_NAME;
import static reactor.netty5.channel.ConnectObservations.ConnectTimeHighCardinalityTags.NET_PEER_PORT;
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
		return new TlsMetricsHandler(recorder, onServer, remoteAddress);
	}

	@Override
	public MicrometerChannelMetricsRecorder recorder() {
		return recorder;
	}

	// ConnectMetricsHandler is Observation.Context and ChannelHandler in order to reduce allocations,
	// this is invoked on every connection establishment
	// This handler is not shared and as such it is different object per connection.
	static final class ConnectMetricsHandler extends Observation.Context
			implements ReactorNettyHandlerContext, ChannelHandler, Supplier<Observation.Context> {
		static final String CONTEXTUAL_NAME = "connect";
		static final String TYPE = "client";

		final MicrometerChannelMetricsRecorder recorder;

		// remote address and status are not known beforehand
		String netPeerName;
		String netPeerPort;
		String status = UNKNOWN;
		ContextView parentContextView;

		ConnectMetricsHandler(MicrometerChannelMetricsRecorder recorder) {
			this.recorder = recorder;
		}

		@Override
		public Observation.Context get() {
			return this;
		}

		@Override
		public Timer getTimer() {
			return recorder.getConnectTimer(getName(), netPeerName + ":" + netPeerPort, status);
		}

		@Override
		@SuppressWarnings("try")
		public Future<Void> connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress) {
			// Cannot invoke the recorder anymore:
			// 1. The recorder is one instance only, it is invoked for all connection establishments that can happen
			// 2. The recorder does not have knowledge about connection establishment lifecycle
			//
			// Move the implementation from the recorder here
			if (remoteAddress instanceof InetSocketAddress address) {
				this.netPeerName = address.getHostString();
				this.netPeerPort = address.getPort() + "";
			}
			else {
				this.netPeerName = remoteAddress.toString();
				this.netPeerPort = "";
			}
			Observation observation = Observation.createNotStarted(recorder.name() + CONNECT_TIME, this, OBSERVATION_REGISTRY);
			parentContextView = updateChannelContext(ctx.channel(), observation);
			observation.start();
			return ctx.connect(remoteAddress, localAddress)
			          .addListener(future -> {
			              ctx.pipeline().remove(this);

			              status = future.isSuccess() ? SUCCESS : ERROR;

			       observation.stop();

			       ReactorNetty.setChannelContext(ctx.channel(), parentContextView);
			       parentContextView = null;
			   });
		}

		@Override
		public String getContextualName() {
			return CONTEXTUAL_NAME;
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(NET_PEER_NAME.asString(), netPeerName, NET_PEER_PORT.asString(), netPeerPort,
					REACTOR_NETTY_PROTOCOL.asString(), recorder.protocol(),
					REACTOR_NETTY_STATUS.asString(), status, REACTOR_NETTY_TYPE.asString(), TYPE);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(REMOTE_ADDRESS.asString(), netPeerName + ":" + netPeerPort, STATUS.asString(), status);
		}
	}

	static final class TlsMetricsHandler extends Observation.Context
			implements ReactorNettyHandlerContext, ChannelHandler, Supplier<Observation.Context> {
		static final String CONTEXTUAL_NAME = "tls handshake";
		static final String TYPE_CLIENT = "client";
		static final String TYPE_SERVER = "server";

		final MicrometerChannelMetricsRecorder recorder;
		final SocketAddress remoteAddress;
		final String type;
		Observation observation;

		// remote address and status are not known beforehand
		String netPeerName;
		String netPeerPort;
		String status = UNKNOWN;
		ContextView parentContextView;

		TlsMetricsHandler(MicrometerChannelMetricsRecorder recorder, boolean onServer, @Nullable SocketAddress remoteAddress) {
			this.recorder = recorder;
			this.remoteAddress = remoteAddress;
			this.type = onServer ? TYPE_SERVER : TYPE_CLIENT;
		}

		@Override
		@SuppressWarnings("try")
		public void channelActive(ChannelHandlerContext ctx) {
			SocketAddress rАddr = remoteAddress != null ? remoteAddress : ctx.channel().remoteAddress();
			if (rАddr instanceof InetSocketAddress address) {
				this.netPeerName = address.getHostString();
				this.netPeerPort = address.getPort() + "";
			}
			else {
				this.netPeerName = rАddr.toString();
				this.netPeerPort = "";
			}
			observation = Observation.createNotStarted(recorder.name() + TLS_HANDSHAKE_TIME, this, OBSERVATION_REGISTRY);
			parentContextView = updateChannelContext(ctx.channel(), observation);
			observation.start();
			ctx.pipeline()
			   .get(SslHandler.class)
			   .handshakeFuture()
			   .addListener(f -> {
			           ctx.pipeline().remove(this);
			           status = f.isSuccess() ? SUCCESS : ERROR;
			           observation.stop();

			           ReactorNetty.setChannelContext(ctx.channel(), parentContextView);
			           parentContextView = null;
			   });

			ctx.fireChannelActive();
		}

		@Override
		public Observation.Context get() {
			return this;
		}

		@Override
		public String getContextualName() {
			return CONTEXTUAL_NAME;
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(NET_PEER_NAME.asString(), netPeerName, NET_PEER_PORT.asString(), netPeerPort,
					REACTOR_NETTY_PROTOCOL.asString(), recorder.protocol(),
					REACTOR_NETTY_STATUS.asString(), status, REACTOR_NETTY_TYPE.asString(), type);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(REMOTE_ADDRESS.asString(), netPeerName + ':' + netPeerPort, STATUS.asString(), status);
		}

		@Override
		public Timer getTimer() {
			return recorder.getTlsHandshakeTimer(getName(), netPeerName + ':' + netPeerPort, status);
		}
	}
}
