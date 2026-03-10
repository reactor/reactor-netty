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
import io.netty.channel.ChannelHandlerContext;
import org.jspecify.annotations.Nullable;
import reactor.util.context.ContextView;

import java.net.SocketAddress;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

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
			@Nullable String path,
			@Nullable ContextView contextView,
			String method) {
		super(remoteAddress, proxyAddress, path, contextView, method);
		this.recorder = recorder;
	}

	ContextAwareWebSocketClientMetricsHandler(ContextAwareWebSocketClientMetricsHandler copy) {
		super(copy);
		this.recorder = copy.recorder;
	}

	@Override
	protected ContextAwareWebSocketClientMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void recordConnectionClosed(ChannelHandlerContext ctx) {
		if (contextView != null) {
			Duration duration = Duration.ofNanos(System.nanoTime() - connectionStartTime);
			if (proxyAddress == null) {
				recorder.recordWebSocketConnectionDuration(contextView, remoteAddress,
						path != null ? path : "unknown", duration);
			}
			else {
				recorder.recordWebSocketConnectionDuration(contextView, remoteAddress, proxyAddress,
						path != null ? path : "unknown", duration);
			}
		}
		else {
			super.recordConnectionClosed(ctx);
		}
	}

	@Override
	protected void recordException(ChannelHandlerContext ctx) {
		if (contextView != null) {
			if (proxyAddress == null) {
				recorder().incrementErrorsCount(contextView, remoteAddress, path != null ? path : "unknown");
			}
			else {
				recorder().incrementErrorsCount(contextView, remoteAddress, proxyAddress, path != null ? path : "unknown");
			}
		}
		else {
			super.recordException(ctx);
		}
	}

	@Override
	protected void recordWrite(SocketAddress address) {
		if (path == null) {
			return;
		}
		if (contextView != null) {
			if (proxyAddress == null) {
				recorder.recordDataSentTime(contextView, address, requireNonNull(path), method,
						Duration.ofNanos(System.nanoTime() - dataSentTime));

				recorder.recordDataSent(contextView, address, path, dataSent);
			}
			else {
				recorder.recordDataSentTime(contextView, address, proxyAddress, requireNonNull(path), method,
						Duration.ofNanos(System.nanoTime() - dataSentTime));

				recorder.recordDataSent(contextView, address, proxyAddress, path, dataSent);
			}
			dataSent = 0;
		}
		else {
			super.recordWrite(address);
		}
	}

	@Override
	protected void recordRead(Channel channel, SocketAddress address) {
		if (path == null) {
			return;
		}
		if (contextView != null) {
			if (proxyAddress == null) {
				recorder.recordDataReceivedTime(contextView, address, requireNonNull(path), method, "n/a",
						Duration.ofNanos(System.nanoTime() - dataReceivedTime));

				recorder.recordDataReceived(contextView, address, path, dataReceived);
			}
			else {
				recorder.recordDataReceivedTime(contextView, address, proxyAddress, requireNonNull(path), method, "n/a",
						Duration.ofNanos(System.nanoTime() - dataReceivedTime));

				recorder.recordDataReceived(contextView, address, proxyAddress, path, dataReceived);
			}
			dataReceived = 0;
		}
		else {
			super.recordRead(channel, address);
		}
	}
}
