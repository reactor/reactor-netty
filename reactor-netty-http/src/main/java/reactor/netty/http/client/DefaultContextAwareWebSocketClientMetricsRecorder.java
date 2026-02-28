/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;

/**
 * {@link ContextAwareWebSocketClientMetricsRecorder} that delegates to a {@link ContextAwareHttpClientMetricsRecorder}.
 *
 * @author raccoonback
 * @since 1.3.2
 */
final class DefaultContextAwareWebSocketClientMetricsRecorder extends ContextAwareWebSocketClientMetricsRecorder {

	final ContextAwareHttpClientMetricsRecorder recorder;

	DefaultContextAwareWebSocketClientMetricsRecorder(ContextAwareHttpClientMetricsRecorder recorder) {
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
	public void recordDataReceivedTime(ContextView contextView, SocketAddress remoteAddress, String uri,
			String method, String status, Duration time) {
		recorder.recordDataReceivedTime(contextView, remoteAddress, uri, method, status, time);
	}

	@Override
	public void recordDataReceivedTime(ContextView contextView, SocketAddress remoteAddress,
			SocketAddress proxyAddress, String uri, String method, String status, Duration time) {
		recorder.recordDataReceivedTime(contextView, remoteAddress, proxyAddress, uri, method, status, time);
	}

	@Override
	public void recordDataSentTime(ContextView contextView, SocketAddress remoteAddress, String uri,
			String method, Duration time) {
		recorder.recordDataSentTime(contextView, remoteAddress, uri, method, time);
	}

	@Override
	public void recordDataSentTime(ContextView contextView, SocketAddress remoteAddress,
			SocketAddress proxyAddress, String uri, String method, Duration time) {
		recorder.recordDataSentTime(contextView, remoteAddress, proxyAddress, uri, method, time);
	}

	@Override
	public void recordResponseTime(ContextView contextView, SocketAddress remoteAddress, String uri,
			String method, String status, Duration time) {
		recorder.recordResponseTime(contextView, remoteAddress, uri, method, status, time);
	}

	@Override
	public void recordResponseTime(ContextView contextView, SocketAddress remoteAddress,
			SocketAddress proxyAddress, String uri, String method, String status, Duration time) {
		recorder.recordResponseTime(contextView, remoteAddress, proxyAddress, uri, method, status, time);
	}

	@Override
	public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress, String uri) {
		recorder.incrementErrorsCount(contextView, remoteAddress, uri);
	}

	@Override
	public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress,
			SocketAddress proxyAddress, String uri) {
		recorder.incrementErrorsCount(contextView, remoteAddress, proxyAddress, uri);
	}

	@Override
	public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
		recorder.recordDataReceived(contextView, remoteAddress, uri, bytes);
	}

	@Override
	public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress,
			SocketAddress proxyAddress, String uri, long bytes) {
		recorder.recordDataReceived(contextView, remoteAddress, proxyAddress, uri, bytes);
	}

	@Override
	public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
		recorder.recordDataSent(contextView, remoteAddress, uri, bytes);
	}

	@Override
	public void recordDataSent(ContextView contextView, SocketAddress remoteAddress,
			SocketAddress proxyAddress, String uri, long bytes) {
		recorder.recordDataSent(contextView, remoteAddress, proxyAddress, uri, bytes);
	}

	@Override
	public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress) {
		recorder.incrementErrorsCount(contextView, remoteAddress);
	}

	@Override
	public void recordConnectTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordConnectTime(contextView, remoteAddress, time, status);
	}

	@Override
	public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, long bytes) {
		recorder.recordDataReceived(contextView, remoteAddress, bytes);
	}

	@Override
	public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, long bytes) {
		recorder.recordDataSent(contextView, remoteAddress, bytes);
	}

	@Override
	public void recordTlsHandshakeTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordTlsHandshakeTime(contextView, remoteAddress, time, status);
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		recorder.recordResolveAddressTime(remoteAddress, time, status);
	}
}
