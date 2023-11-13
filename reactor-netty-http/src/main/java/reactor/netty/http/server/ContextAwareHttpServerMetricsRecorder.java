/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import reactor.netty.http.ContextAwareHttpMetricsRecorder;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.time.Duration;

/**
 * {@link ContextView} aware class for collecting metrics on HTTP server level.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
public abstract class ContextAwareHttpServerMetricsRecorder extends ContextAwareHttpMetricsRecorder implements HttpServerMetricsRecorder {

	/**
	 * Records the time that is spent in consuming incoming data.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param uri The requested URI
	 * @param method The HTTP method
	 * @param time The time in nanoseconds that is spent in consuming incoming data
	 */
	public abstract void recordDataReceivedTime(ContextView contextView, String uri, String method, Duration time);

	/**
	 * Records the time that is spent in sending outgoing data.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param uri The requested URI
	 * @param method The HTTP method
	 * @param status The HTTP status
	 * @param time The time in nanoseconds that is spent in sending outgoing data
	 */
	public abstract void recordDataSentTime(ContextView contextView, String uri, String method, String status, Duration time);

	/**
	 * Records the total time for the request/response.
	 *
	 * @param contextView The current {@link ContextView} associated with the Mono/Flux
	 * @param uri The requested URI
	 * @param method The HTTP method
	 * @param status The HTTP status
	 * @param time The total time in nanoseconds for the request/response
	 */
	public abstract void recordResponseTime(ContextView contextView, String uri, String method, String status, Duration time);

	@Override
	public void recordDataReceivedTime(String uri, String method, Duration time) {
		recordDataReceivedTime(Context.empty(), uri, method, time);
	}

	@Override
	public void recordDataSentTime(String uri, String method, String status, Duration time) {
		recordDataSentTime(Context.empty(), uri, method, status, time);
	}

	@Override
	public void recordResponseTime(String uri, String method, String status, Duration time) {
		recordResponseTime(Context.empty(), uri, method, status, time);
	}
}
