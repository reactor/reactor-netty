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

import java.net.SocketAddress;
import java.time.Duration;

/**
 * {@link WebSocketServerMetricsRecorder} that delegates to a {@link HttpServerMetricsRecorder}.
 *
 * @author LivingLikeKrillin
 * @since 1.3.7
 */
final class DefaultWebSocketServerMetricsRecorder implements WebSocketServerMetricsRecorder {

	final HttpServerMetricsRecorder recorder;

	DefaultWebSocketServerMetricsRecorder(HttpServerMetricsRecorder recorder) {
		this.recorder = recorder;
	}

	@Override
	public void recordWebSocketHandshakeTime(SocketAddress remoteAddress, String uri, String status, Duration time) {
	}

	@Override
	public void recordWebSocketConnectionDuration(SocketAddress remoteAddress, String uri, Duration time) {
	}

	@Override
	public void recordDataReceivedTime(String uri, String method, Duration time) {
		recorder.recordDataReceivedTime(uri, method, time);
	}

	@Override
	public void recordDataSentTime(String uri, String method, String status, Duration time) {
		recorder.recordDataSentTime(uri, method, status, time);
	}

	@Override
	public void recordResponseTime(String uri, String method, String status, Duration time) {
		recorder.recordResponseTime(uri, method, status, time);
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
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		recorder.recordDataReceived(remoteAddress, uri, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		recorder.recordDataSent(remoteAddress, uri, bytes);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		recorder.incrementErrorsCount(remoteAddress, uri);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress) {
		recorder.incrementErrorsCount(remoteAddress);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, SocketAddress proxyAddress) {
		recorder.incrementErrorsCount(remoteAddress, proxyAddress);
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordConnectTime(remoteAddress, time, status);
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, SocketAddress proxyAddress, Duration time, String status) {
		recorder.recordConnectTime(remoteAddress, proxyAddress, time, status);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		recorder.recordDataReceived(remoteAddress, bytes);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recorder.recordDataReceived(remoteAddress, proxyAddress, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		recorder.recordDataSent(remoteAddress, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recorder.recordDataSent(remoteAddress, proxyAddress, bytes);
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordTlsHandshakeTime(remoteAddress, time, status);
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, SocketAddress proxyAddress, Duration time, String status) {
		recorder.recordTlsHandshakeTime(remoteAddress, proxyAddress, time, status);
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordResolveAddressTime(remoteAddress, time, status);
	}
}
