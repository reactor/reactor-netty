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

import java.net.SocketAddress;
import java.time.Duration;

/**
 * {@link WebSocketClientMetricsRecorder} that delegates to a {@link HttpClientMetricsRecorder}.
 *
 * @author LivingLikeKrillin
 * @since 1.3.5
 */
final class DefaultWebSocketClientMetricsRecorder implements WebSocketClientMetricsRecorder {

	final HttpClientMetricsRecorder recorder;

	DefaultWebSocketClientMetricsRecorder(HttpClientMetricsRecorder recorder) {
		this.recorder = recorder;
	}

	@Override
	public void recordWebSocketHandshakeTime(SocketAddress remoteAddress, String uri, String status, Duration time) {
	}

	@Override
	public void recordWebSocketConnectionDuration(SocketAddress remoteAddress, String uri, Duration time) {
	}

	@Override
	public void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		recorder.recordDataReceivedTime(remoteAddress, uri, method, status, time);
	}

	@Override
	public void recordDataReceivedTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, String method, String status, Duration time) {
		recorder.recordDataReceivedTime(remoteAddress, proxyAddress, uri, method, status, time);
	}

	@Override
	public void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time) {
		recorder.recordDataSentTime(remoteAddress, uri, method, time);
	}

	@Override
	public void recordDataSentTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, String method, Duration time) {
		recorder.recordDataSentTime(remoteAddress, proxyAddress, uri, method, time);
	}

	@Override
	public void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		recorder.recordResponseTime(remoteAddress, uri, method, status, time);
	}

	@Override
	public void recordResponseTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, String method, String status, Duration time) {
		recorder.recordResponseTime(remoteAddress, proxyAddress, uri, method, status, time);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		recorder.incrementErrorsCount(remoteAddress, uri);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri) {
		recorder.incrementErrorsCount(remoteAddress, proxyAddress, uri);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		recorder.recordDataReceived(remoteAddress, uri, bytes);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, long bytes) {
		recorder.recordDataReceived(remoteAddress, proxyAddress, uri, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		recorder.recordDataSent(remoteAddress, uri, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, long bytes) {
		recorder.recordDataSent(remoteAddress, proxyAddress, uri, bytes);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress) {
		recorder.incrementErrorsCount(remoteAddress);
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordConnectTime(remoteAddress, time, status);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		recorder.recordDataReceived(remoteAddress, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		recorder.recordDataSent(remoteAddress, bytes);
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordTlsHandshakeTime(remoteAddress, time, status);
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordResolveAddressTime(remoteAddress, time, status);
	}
}
