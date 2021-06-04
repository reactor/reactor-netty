/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.util.function.Function;

/**
 * @author Violeta Georgieva
 * @since 1.0.8
 */
final class ContextAwareHttpServerMetricsHandler extends AbstractHttpServerMetricsHandler {

	final ContextAwareHttpServerMetricsRecorder recorder;

	ContextAwareHttpServerMetricsHandler(ContextAwareHttpServerMetricsRecorder recorder,
			@Nullable Function<String, String> uriTagValue) {
		super(uriTagValue);
		this.recorder = recorder;
	}

	@Override
	protected ContextAwareHttpServerMetricsRecorder recorder() {
		return recorder;
	}

	@Override
	protected void recordException(HttpServerOperations ops, String path) {
		// Always take the remote address from the operations in order to consider proxy information
		recorder().incrementErrorsCount(ops.currentContext(), ops.remoteAddress(), path);
	}

	@Override
	protected void recordRead(HttpServerOperations ops, String path, String method) {
		ContextView contextView = ops.currentContext();
		recorder().recordDataReceivedTime(contextView, path, method,
				Duration.ofNanos(System.nanoTime() - dataReceivedTime));

		// Always take the remote address from the operations in order to consider proxy information
		recorder().recordDataReceived(contextView, ops.remoteAddress(), path, dataReceived);
	}

	@Override
	protected void recordWrite(HttpServerOperations ops, String path, String method, String status) {
		ContextView contextView = ops.currentContext();
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
		recorder().recordDataSent(contextView, ops.remoteAddress(), path, dataSent);
	}
}
