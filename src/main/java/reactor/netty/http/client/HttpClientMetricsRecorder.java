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
package reactor.netty.http.client;

import reactor.netty.http.HttpMetricsRecorder;

import java.net.SocketAddress;
import java.time.Duration;

/**
 * Interface for collecting metrics on HTTP client level
 *
 * @author Violeta Georgieva
 */
public interface HttpClientMetricsRecorder extends HttpMetricsRecorder {

	/**
	 * Records the time that is spent in consuming incoming data
	 *
	 * @param remoteAddress The remote peer
	 * @param uri the requested URI
	 * @param method the HTTP method
	 * @param status the HTTP status
	 * @param time the time in nanoseconds that is spent in consuming incoming data
	 */
	void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time);

	/**
	 * Records the time that is spent in sending outgoing data
	 *
	 * @param remoteAddress The remote peer
	 * @param uri the requested URI
	 * @param method the HTTP method
	 * @param time the time in nanoseconds that is spent in sending outgoing data
	 */
	void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time);

	/**
	 * Records the total time for the request/response
	 *
	 * @param remoteAddress The remote peer
	 * @param uri the requested URI
	 * @param method the HTTP method
	 * @param status the HTTP status
	 * @param time the total time in nanoseconds for the request/response
	 */
	void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time);
}
