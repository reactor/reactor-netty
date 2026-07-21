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
package reactor.netty.http.server;

import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;

/**
 * {@link ContextAwareWebSocketServerMetricsRecorder} that delegates to a {@link ContextAwareHttpServerMetricsRecorder}.
 *
 * @author LivingLikeKrillin
 * @since 1.3.7
 */
final class DefaultContextAwareWebSocketServerMetricsRecorder extends ContextAwareWebSocketServerMetricsRecorder {

	final ContextAwareHttpServerMetricsRecorder recorder;

	DefaultContextAwareWebSocketServerMetricsRecorder(ContextAwareHttpServerMetricsRecorder recorder) {
		this.recorder = recorder;
	}

	@Override
	public void recordWebSocketHandshakeTime(ContextView contextView, SocketAddress remoteAddress, String uri,
			String status, Duration time) {
	}

	@Override
	public void recordWebSocketConnectionDuration(ContextView contextView, SocketAddress remoteAddress,
			String uri, Duration time) {
	}

	@Override
	public void recordDataReceivedTime(ContextView contextView, String uri, String method, Duration time) {
		recorder.recordDataReceivedTime(contextView, uri, method, time);
	}

	@Override
	public void recordDataSentTime(ContextView contextView, String uri, String method, String status, Duration time) {
		recorder.recordDataSentTime(contextView, uri, method, status, time);
	}

	@Override
	public void recordResponseTime(ContextView contextView, String uri, String method, String status, Duration time) {
		recorder.recordResponseTime(contextView, uri, method, status, time);
	}

	@Override
	public void recordServerConnectionActive(SocketAddress localAddress) {
		recorder.recordServerConnectionActive(localAddress);
	}

	@Override
	public void recordServerConnectionInactive(SocketAddress localAddress) {
		recorder.recordServerConnectionInactive(localAddress);
	}

	@Override
	public void recordStreamOpened(SocketAddress localAddress) {
		recorder.recordStreamOpened(localAddress);
	}

	@Override
	public void recordStreamClosed(SocketAddress localAddress) {
		recorder.recordStreamClosed(localAddress);
	}

	@Override
	public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress, String uri) {
		recorder.incrementErrorsCount(contextView, remoteAddress, uri);
	}

	@Override
	public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
		recorder.recordDataReceived(contextView, remoteAddress, uri, bytes);
	}

	@Override
	public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
		recorder.recordDataSent(contextView, remoteAddress, uri, bytes);
	}

	@Override
	public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress) {
		recorder.incrementErrorsCount(contextView, remoteAddress);
	}

	@Override
	public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress) {
		recorder.incrementErrorsCount(contextView, remoteAddress, proxyAddress);
	}

	@Override
	public void recordConnectTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordConnectTime(contextView, remoteAddress, time, status);
	}

	@Override
	public void recordConnectTime(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress,
			Duration time, String status) {
		recorder.recordConnectTime(contextView, remoteAddress, proxyAddress, time, status);
	}

	@Override
	public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, long bytes) {
		recorder.recordDataReceived(contextView, remoteAddress, bytes);
	}

	@Override
	public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recorder.recordDataReceived(contextView, remoteAddress, proxyAddress, bytes);
	}

	@Override
	public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, long bytes) {
		recorder.recordDataSent(contextView, remoteAddress, bytes);
	}

	@Override
	public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recorder.recordDataSent(contextView, remoteAddress, proxyAddress, bytes);
	}

	@Override
	public void recordTlsHandshakeTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordTlsHandshakeTime(contextView, remoteAddress, time, status);
	}

	@Override
	public void recordTlsHandshakeTime(ContextView contextView, SocketAddress remoteAddress, SocketAddress proxyAddress,
			Duration time, String status) {
		recorder.recordTlsHandshakeTime(contextView, remoteAddress, proxyAddress, time, status);
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordResolveAddressTime(remoteAddress, time, status);
	}
}
