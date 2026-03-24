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

import io.netty.channel.Channel;
import org.jspecify.annotations.Nullable;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;

/**
 * {@link AbstractWebSocketClientMetricsHandler} that propagates
 * {@link reactor.util.context.ContextView}.
 *
 * @author LivingLikeKrillin
 * @since 1.3.5
 */
final class ContextAwareWebSocketClientMetricsHandler extends AbstractWebSocketClientMetricsHandler {

	final ContextAwareWebSocketClientMetricsRecorder recorder;

	ContextAwareWebSocketClientMetricsHandler(ContextAwareWebSocketClientMetricsRecorder recorder,
			SocketAddress remoteAddress,
			@Nullable SocketAddress proxyAddress,
			String path,
			ContextView contextView,
			String method) {
		super(remoteAddress, proxyAddress, path, contextView, method);
		this.recorder = recorder;
	}

	@Override
	protected ContextAwareWebSocketClientMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	void recordHandshakeComplete(Channel channel, String status) {
		Duration time = Duration.ofNanos(System.nanoTime() - handshakeStartTime);
		if (proxyAddress == null) {
			recorder.recordWebSocketHandshakeTime(contextView, remoteAddress, path, status, time);
		}
		else {
			recorder.recordWebSocketHandshakeTime(contextView, remoteAddress, proxyAddress, path, status, time);
		}
	}

	@Override
	void recordHandshakeFailure(Channel channel) {
		Duration time = Duration.ofNanos(System.nanoTime() - handshakeStartTime);
		if (proxyAddress == null) {
			recorder.recordWebSocketHandshakeTime(contextView, remoteAddress, path, "ERROR", time);
		}
		else {
			recorder.recordWebSocketHandshakeTime(contextView, remoteAddress, proxyAddress, path, "ERROR", time);
		}
	}

	@Override
	protected void recordConnectionClosed() {
		Duration duration = Duration.ofNanos(System.nanoTime() - connectionStartTime);
		if (proxyAddress == null) {
			recorder.recordWebSocketConnectionDuration(contextView, remoteAddress, path, duration);
		}
		else {
			recorder.recordWebSocketConnectionDuration(contextView, remoteAddress, proxyAddress, path, duration);
		}
	}

	@Override
	protected void recordException() {
		if (proxyAddress == null) {
			recorder().incrementErrorsCount(contextView, remoteAddress, path);
		}
		else {
			recorder().incrementErrorsCount(contextView, remoteAddress, proxyAddress, path);
		}
	}

	@Override
	protected void recordWrite(SocketAddress address) {
		if (proxyAddress == null) {
			recorder.recordDataSentTime(contextView, address, path, method,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder.recordDataSent(contextView, address, path, dataSent);
		}
		else {
			recorder.recordDataSentTime(contextView, address, proxyAddress, path, method,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder.recordDataSent(contextView, address, proxyAddress, path, dataSent);
		}
		dataSent = 0;
	}

	@Override
	protected void recordRead(SocketAddress address) {
		if (proxyAddress == null) {
			recorder.recordDataReceivedTime(contextView, address, path, method, "n/a",
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder.recordDataReceived(contextView, address, path, dataReceived);
		}
		else {
			recorder.recordDataReceivedTime(contextView, address, proxyAddress, path, method, "n/a",
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder.recordDataReceived(contextView, address, proxyAddress, path, dataReceived);
		}
		dataReceived = 0;
	}
}
