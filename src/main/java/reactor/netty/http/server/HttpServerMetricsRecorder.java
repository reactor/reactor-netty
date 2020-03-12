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

import reactor.netty.http.HttpMetricsRecorder;

import java.time.Duration;

/**
 * Interface for collecting metrics on HTTP server level
 *
 * @author Violeta Georgieva
 */
public interface HttpServerMetricsRecorder extends HttpMetricsRecorder {

	/**
	 * Records the time that is spent in consuming incoming data
	 *
	 * @param uri the requested URI
	 * @param method the HTTP method
	 * @param time the time in nanoseconds that is spent in consuming incoming data
	 */
	void recordDataReceivedTime(String uri, String method, Duration time);

	/**
	 * Records the time that is spent in sending outgoing data
	 *
	 * @param uri the requested URI
	 * @param method the HTTP method
	 * @param status the HTTP status
	 * @param time the time in nanoseconds that is spent in sending outgoing data
	 */
	void recordDataSentTime(String uri, String method, String status, Duration time);

	/**
	 * Records the total time for the request/response
	 *
	 * @param uri the requested URI
	 * @param method the HTTP method
	 * @param status the HTTP status
	 * @param time the total time in nanoseconds for the request/response
	 */
	void recordResponseTime(String uri, String method, String status, Duration time);
}
