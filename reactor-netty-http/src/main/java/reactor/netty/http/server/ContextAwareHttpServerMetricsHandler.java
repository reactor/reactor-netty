/*
 * Copyright (c) 2021-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * {@link AbstractHttpServerMetricsHandler} that propagates {@link ContextView}.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
final class ContextAwareHttpServerMetricsHandler extends AbstractHttpServerMetricsHandler {

	final ContextAwareHttpServerMetricsRecorder recorder;

	ContextAwareHttpServerMetricsHandler(
			ContextAwareHttpServerMetricsRecorder recorder,
			@Nullable Function<String, String> methodTagValue,
			@Nullable Function<String, String> uriTagValue) {
		super(methodTagValue, uriTagValue);
		this.recorder = recorder;
	}

	ContextAwareHttpServerMetricsHandler(ContextAwareHttpServerMetricsHandler copy) {
		super(copy);
		this.recorder = copy.recorder;
	}

	@Override
	protected ContextAwareHttpServerMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void contextView(HttpServerOperations ops) {
		this.contextView = ops.currentContext();
	}

	@Override
	protected void recordException() {
		// Always take the remote address from the operations in order to consider proxy information
		// Use remoteSocketAddress() in order to obtain UDS info
		recorder().incrementErrorsCount(requireNonNull(contextView), requireNonNull(remoteSocketAddress), requireNonNull(path));
	}

	@Override
	protected void recordRead() {
		recorder().recordDataReceivedTime(requireNonNull(contextView), requireNonNull(path), requireNonNull(method),
				Duration.ofNanos(System.nanoTime() - dataReceivedTime));

		// Always take the remote address from the operations in order to consider proxy information
		// Use remoteSocketAddress() in order to obtain UDS info
		recorder().recordDataReceived(contextView, requireNonNull(remoteSocketAddress), path, dataReceived);
	}

	@Override
	protected void recordWrite(Channel channel) {
		recordWrite(requireNonNull(contextView), dataReceivedTime, dataSent, dataSentTime, requireNonNull(method),
				requireNonNull(path), requireNonNull(remoteSocketAddress), requireNonNull(status));
	}

	@Override
	protected void recordWrite(Channel channel, MetricsArgProvider metricsArgProvider) {
		recordWrite(requireNonNull(metricsArgProvider.contextView), metricsArgProvider.dataReceivedTime, metricsArgProvider.dataSent, metricsArgProvider.dataSentTime,
				requireNonNull(metricsArgProvider.method), requireNonNull(metricsArgProvider.path),
				requireNonNull(metricsArgProvider.remoteSocketAddress), requireNonNull(metricsArgProvider.status));
	}

	void recordWrite(
			ContextView contextView,
			long dataReceivedTime,
			long dataSent,
			long dataSentTime,
			String method,
			String path,
			SocketAddress remoteSocketAddress,
			String status) {
		Duration dataSentTimeDuration = Duration.ofNanos(System.nanoTime() - dataSentTime);
		recorder().recordDataSentTime(contextView, path, method, status, dataSentTimeDuration);

		if (dataReceivedTime != 0) {
			recorder().recordResponseTime(contextView, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));
		}
		else {
			recorder().recordResponseTime(contextView, path, method, status, dataSentTimeDuration);
		}

		// Always take the remote address from the operations in order to consider proxy information
		// Use remoteSocketAddress() in order to obtain UDS info
		recorder().recordDataSent(contextView, remoteSocketAddress, path, dataSent);
	}
}
