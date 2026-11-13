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

import io.netty.channel.Channel;
import org.jspecify.annotations.Nullable;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;

/**
 * {@link AbstractWebSocketServerMetricsHandler} that propagates
 * {@link reactor.util.context.ContextView}.
 *
 * @author LivingLikeKrillin
 * @since 1.3.8
 */
final class ContextAwareWebSocketServerMetricsHandler extends AbstractWebSocketServerMetricsHandler {

	final ContextAwareWebSocketServerMetricsRecorder recorder;

	boolean contextViewAvailable;
	@Nullable String handshakeStatus;
	@Nullable Duration handshakeTime;

	ContextAwareWebSocketServerMetricsHandler(ContextAwareWebSocketServerMetricsRecorder recorder,
			SocketAddress remoteAddress,
			String path,
			ContextView contextView,
			String method) {
		super(remoteAddress, path, contextView, method);
		this.recorder = recorder;
	}

	@Override
	protected ContextAwareWebSocketServerMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	void recordHandshakeComplete(Channel channel, String status) {
		if (handshakeFinalized) {
			return;
		}
		handshakeFinalized = true;
		handshakeTime = Duration.ofNanos(System.nanoTime() - handshakeStartTime);
		handshakeStatus = status;
		recordHandshake();
	}

	@Override
	void recordHandshakeFailure(Channel channel) {
		recordHandshakeComplete(channel, "ERROR");
	}

	void contextView(ContextView contextView) {
		this.contextView = contextView;
		contextViewAvailable = true;
		recordHandshake();
	}

	void recordHandshake() {
		// The handshake can finish before subscription supplies the context. Keep its original duration.
		if (contextViewAvailable && handshakeStatus != null && handshakeTime != null) {
			String status = handshakeStatus;
			Duration time = handshakeTime;
			handshakeStatus = null;
			handshakeTime = null;
			recorder.recordWebSocketHandshakeTime(contextView, remoteAddress, path, status, time);
		}
	}

	@Override
	void recordPendingHandshake() {
		// The upgrade is never subscribed, record the pending handshake with the available context
		contextViewAvailable = true;
		recordHandshake();
	}

	@Override
	protected void recordConnectionClosed() {
		Duration duration = Duration.ofNanos(System.nanoTime() - connectionStartTime);
		recorder.recordWebSocketConnectionDuration(contextView, remoteAddress, path, duration);
	}

	@Override
	protected void recordException() {
		recorder().incrementErrorsCount(contextView, remoteAddress, path);
	}

	@Override
	protected void recordWrite(SocketAddress address, long sentBytes, long sentTimeNanos) {
		Duration duration = Duration.ofNanos(System.nanoTime() - sentTimeNanos);
		recorder.recordDataSentTime(contextView, path, method, "n/a", duration);

		recorder.recordDataSent(contextView, address, path, sentBytes);
	}

	@Override
	protected void recordRead(SocketAddress address) {
		Duration duration = Duration.ofNanos(System.nanoTime() - dataReceivedTime);
		recorder.recordDataReceivedTime(contextView, path, method, duration);

		recorder.recordDataReceived(contextView, address, path, dataReceived);
		dataReceived = 0;
	}
}
