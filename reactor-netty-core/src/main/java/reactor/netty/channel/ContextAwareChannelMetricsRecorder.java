/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;

/**
 * {@link ContextView} aware class for recording metrics on protocol level.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
public abstract class ContextAwareChannelMetricsRecorder implements ChannelMetricsRecorder {

	/**
	 * Increments the number of the errors that have occurred.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 */
	public abstract void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress);

	/**
	 * Increments the number of the errors that have occurred.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 * @param proxyAddress The proxy address
	 * @since 1.1.17
	 */
	public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress) {
		incrementErrorsCount(contextView, remoteAddress);
	}

	/**
	 * Records the time that is spent for connecting to the remote address.
	 * Relevant only when on the client.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 * @param time The time in nanoseconds that is spent for connecting to the remote address
	 * @param status The status of the operation
	 */
	public abstract void recordConnectTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status);

	/**
	 * Records the time that is spent for connecting to the remote address.
	 * Relevant only when on the client.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 * @param proxyAddress The proxy address
	 * @param time The time in nanoseconds that is spent for connecting to the remote address
	 * @param status The status of the operation
	 * @since 1.1.17
	 */
	public void recordConnectTime(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress, Duration time, String status) {
		recordConnectTime(contextView, remoteAddress, time, status);
	}

	/**
	 * Records the amount of the data that is received, in bytes.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 * @param bytes The amount of the data that is received, in bytes
	 */
	public abstract void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, long bytes);

	/**
	 * Records the amount of the data that is received, in bytes.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 * @param proxyAddress The proxy address
	 * @param bytes The amount of the data that is received, in bytes
	 * @since 1.1.17
	 */
	public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recordDataReceived(contextView, remoteAddress, bytes);
	}

	/**
	 * Records the amount of the data that is sent, in bytes.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 * @param bytes The amount of the data that is sent, in bytes
	 */
	public abstract void recordDataSent(ContextView contextView, SocketAddress remoteAddress, long bytes);

	/**
	 * Records the amount of the data that is sent, in bytes.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 * @param proxyAddress The proxy address
	 * @param bytes The amount of the data that is sent, in bytes
	 * @since 1.1.17
	 */
	public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recordDataSent(contextView, remoteAddress, bytes);
	}

	/**
	 * Records the time that is spent for TLS handshake.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 * @param time The time in nanoseconds that is spent for TLS handshake
	 * @param status The status of the operation
	 */
	public abstract void recordTlsHandshakeTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status);

	/**
	 * Records the time that is spent for TLS handshake.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux pipeline
	 * @param remoteAddress The remote peer
	 * @param proxyAddress The proxy address
	 * @param time The time in nanoseconds that is spent for TLS handshake
	 * @param status The status of the operation
	 * @since 1.1.17
	 */
	public void recordTlsHandshakeTime(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress, Duration time, String status) {
		recordTlsHandshakeTime(contextView, remoteAddress, time, status);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress) {
		incrementErrorsCount(Context.empty(), remoteAddress);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, SocketAddress proxyAddress) {
		incrementErrorsCount(Context.empty(), remoteAddress, proxyAddress);
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		recordConnectTime(Context.empty(), remoteAddress, time, status);
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, SocketAddress proxyAddress, Duration time, String status) {
		recordConnectTime(Context.empty(), remoteAddress, proxyAddress, time, status);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		recordDataReceived(Context.empty(), remoteAddress, bytes);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recordDataReceived(Context.empty(), remoteAddress, proxyAddress, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		recordDataSent(Context.empty(), remoteAddress, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recordDataSent(Context.empty(), remoteAddress, proxyAddress, bytes);
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		recordTlsHandshakeTime(Context.empty(), remoteAddress, time, status);
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, SocketAddress proxyAddress, Duration time, String status) {
		recordTlsHandshakeTime(Context.empty(), remoteAddress, proxyAddress, time, status);
	}
}
