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

import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;

/**
 * {@link ContextView} aware class for collecting metrics on WebSocket client level.
 *
 * @author LivingLikeKrillin
 * @since 1.3.5
 */
public abstract class ContextAwareWebSocketClientMetricsRecorder extends ContextAwareHttpClientMetricsRecorder
		implements WebSocketClientMetricsRecorder {

	/**
	 * Records the time that is spent for the WebSocket handshake.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param remoteAddress The remote peer
	 * @param uri the requested URI
	 * @param status the WebSocket handshake status
	 * @param time the time in nanoseconds that is spent for the handshake
	 */
	public abstract void recordWebSocketHandshakeTime(ContextView contextView, SocketAddress remoteAddress, String uri,
			String status, Duration time);

	/**
	 * Records the time that is spent for the WebSocket handshake.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param remoteAddress The remote peer
	 * @param proxyAddress the proxy address
	 * @param uri the requested URI
	 * @param status the WebSocket handshake status
	 * @param time the time in nanoseconds that is spent for the handshake
	 */
	public void recordWebSocketHandshakeTime(ContextView contextView, SocketAddress remoteAddress,
			SocketAddress proxyAddress, String uri, String status, Duration time) {
		recordWebSocketHandshakeTime(contextView, remoteAddress, uri, status, time);
	}

	@Override
	public void recordWebSocketHandshakeTime(SocketAddress remoteAddress, String uri, String status, Duration time) {
		recordWebSocketHandshakeTime(Context.empty(), remoteAddress, uri, status, time);
	}

	@Override
	public void recordWebSocketHandshakeTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri,
			String status, Duration time) {
		recordWebSocketHandshakeTime(Context.empty(), remoteAddress, proxyAddress, uri, status, time);
	}

	/**
	 * Records the duration of the WebSocket connection.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param remoteAddress The remote peer
	 * @param uri the requested URI
	 * @param time the duration of the connection
	 */
	public abstract void recordWebSocketConnectionDuration(ContextView contextView, SocketAddress remoteAddress,
			String uri, Duration time);

	/**
	 * Records the duration of the WebSocket connection.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param remoteAddress The remote peer
	 * @param proxyAddress the proxy address
	 * @param uri the requested URI
	 * @param time the duration of the connection
	 */
	public void recordWebSocketConnectionDuration(ContextView contextView, SocketAddress remoteAddress,
			SocketAddress proxyAddress, String uri, Duration time) {
		recordWebSocketConnectionDuration(contextView, remoteAddress, uri, time);
	}

	@Override
	public void recordWebSocketConnectionDuration(SocketAddress remoteAddress, String uri, Duration time) {
		recordWebSocketConnectionDuration(Context.empty(), remoteAddress, uri, time);
	}

	@Override
	public void recordWebSocketConnectionDuration(SocketAddress remoteAddress, SocketAddress proxyAddress,
			String uri, Duration time) {
		recordWebSocketConnectionDuration(Context.empty(), remoteAddress, proxyAddress, uri, time);
	}
}
