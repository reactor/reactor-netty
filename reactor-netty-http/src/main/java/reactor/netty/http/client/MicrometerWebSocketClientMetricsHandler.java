/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.transport.RequestReplySenderContext;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.jspecify.annotations.Nullable;
import reactor.netty.observability.ReactorNettyHandlerContext;
import reactor.util.context.ContextView;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Supplier;

import static reactor.netty.Metrics.HANDSHAKE_TIME;
import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.OBSERVATION_REGISTRY;
import static reactor.netty.Metrics.UNKNOWN;
import static reactor.netty.Metrics.formatSocketAddress;
import static reactor.netty.Metrics.updateChannelContext;
import static reactor.netty.ReactorNetty.setChannelContext;
import static reactor.netty.http.client.WebSocketClientObservations.HandshakeTimeHighCardinalityTags.HTTP_STATUS_CODE;
import static reactor.netty.http.client.WebSocketClientObservations.HandshakeTimeHighCardinalityTags.HTTP_URL;
import static reactor.netty.http.client.WebSocketClientObservations.HandshakeTimeHighCardinalityTags.NET_PEER_NAME;
import static reactor.netty.http.client.WebSocketClientObservations.HandshakeTimeHighCardinalityTags.NET_PEER_PORT;
import static reactor.netty.http.client.WebSocketClientObservations.HandshakeTimeHighCardinalityTags.REACTOR_NETTY_TYPE;
import static reactor.netty.http.client.WebSocketClientObservations.HandshakeTimeLowCardinalityTags.PROXY_ADDRESS;
import static reactor.netty.http.client.WebSocketClientObservations.HandshakeTimeLowCardinalityTags.REMOTE_ADDRESS;
import static reactor.netty.http.client.WebSocketClientObservations.HandshakeTimeLowCardinalityTags.STATUS;
import static reactor.netty.http.client.WebSocketClientObservations.HandshakeTimeLowCardinalityTags.URI;

/**
 * {@link AbstractWebSocketClientMetricsHandler} for Reactor Netty built-in integration with Micrometer.
 *
 * @author LivingLikeKrillin
 * @since 1.3.5
 */
final class MicrometerWebSocketClientMetricsHandler extends AbstractWebSocketClientMetricsHandler {
	final MicrometerWebSocketClientMetricsRecorder recorder;

	@SuppressWarnings("NullAway")
	// Deliberately suppress "NullAway"
	// This is a lazy initialization
	HandshakeTimeHandlerContext handshakeTimeHandlerContext;
	@SuppressWarnings("NullAway")
	// Deliberately suppress "NullAway"
	// This is a lazy initialization
	Observation handshakeTimeObservation;
	@Nullable ContextView parentContextView;

	MicrometerWebSocketClientMetricsHandler(MicrometerWebSocketClientMetricsRecorder recorder,
			SocketAddress remoteAddress,
			@Nullable SocketAddress proxyAddress,
			String path,
			ContextView contextView,
			String method) {
		super(remoteAddress, proxyAddress, path, contextView, method);
		this.recorder = recorder;
	}

	@Override
	protected WebSocketClientMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	void startHandshake(Channel channel) {
		super.startHandshake(channel);
		handshakeTimeHandlerContext = new HandshakeTimeHandlerContext(recorder, path, remoteAddress, proxyAddress);
		handshakeTimeObservation = Observation.createNotStarted(
				recorder.name() + HANDSHAKE_TIME, handshakeTimeHandlerContext, OBSERVATION_REGISTRY);
		parentContextView = updateChannelContext(channel, handshakeTimeObservation);
		handshakeTimeObservation.start();
	}

	@Override
	void recordHandshakeComplete(Channel channel, String status) {
		if (handshakeTimeHandlerContext != null) {
			handshakeTimeHandlerContext.status = status;
		}
		// Cannot invoke the recorder anymore:
		// 1. The recorder is one instance only, it is invoked for all requests that can happen
		// 2. The recorder does not have knowledge about request lifecycle
		//
		// Move the implementation from the recorder here
		if (handshakeTimeObservation != null) {
			handshakeTimeObservation.stop();
		}
		setChannelContext(channel, parentContextView);
	}

	@Override
	void recordHandshakeFailure(Channel channel) {
		if (handshakeTimeHandlerContext != null) {
			handshakeTimeHandlerContext.status = "ERROR";
		}
		if (handshakeTimeObservation != null) {
			handshakeTimeObservation.stop();
		}
		setChannelContext(channel, parentContextView);
	}

	/*
	 * Requirements for WebSocket clients
	 * Following OpenTelemetry semantic conventions for HTTP clients, adapted for WebSocket.
	 */
	static final class HandshakeTimeHandlerContext extends RequestReplySenderContext<HttpRequest, HttpResponse>
			implements ReactorNettyHandlerContext, Supplier<Observation.Context> {
		static final String TYPE = "client";

		final String netPeerName;
		final String netPeerPort;
		final String path;
		final @Nullable String proxyAddress;
		final MicrometerWebSocketClientMetricsRecorder recorder;

		String status = UNKNOWN;

		HandshakeTimeHandlerContext(MicrometerWebSocketClientMetricsRecorder recorder, String path,
				SocketAddress remoteAddress, @Nullable SocketAddress proxyAddress) {
			super((carrier, key, value) -> {});
			this.recorder = recorder;
			if (remoteAddress instanceof InetSocketAddress) {
				InetSocketAddress address = (InetSocketAddress) remoteAddress;
				this.netPeerName = address.getHostString();
				this.netPeerPort = address.getPort() + "";
			}
			else {
				this.netPeerName = remoteAddress.toString();
				this.netPeerPort = "";
			}
			this.path = path;
			this.proxyAddress = proxyAddress != null ? formatSocketAddress(proxyAddress) : null;
			setContextualName("websocket " + path);
		}

		@Override
		public Observation.Context get() {
			return this;
		}

		@Override
		public @Nullable Timer getTimer() {
			return recorder.getHandshakeTimeTimer(recorder.name() + HANDSHAKE_TIME,
					netPeerName + ":" + netPeerPort, proxyAddress == null ? NA : proxyAddress, path, status);
		}

		@Override
		public KeyValues getHighCardinalityKeyValues() {
			return KeyValues.of(REACTOR_NETTY_TYPE.asString(), TYPE,
					HTTP_URL.asString(), path, HTTP_STATUS_CODE.asString(), status,
					NET_PEER_NAME.asString(), netPeerName, NET_PEER_PORT.asString(), netPeerPort);
		}

		@Override
		public KeyValues getLowCardinalityKeyValues() {
			return KeyValues.of(REMOTE_ADDRESS.asString(), netPeerName + ":" + netPeerPort,
					PROXY_ADDRESS.asString(), proxyAddress == null ? NA : proxyAddress,
					STATUS.asString(), status, URI.asString(), path);
		}
	}
}
