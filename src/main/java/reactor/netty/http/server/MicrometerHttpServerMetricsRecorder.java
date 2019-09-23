/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import reactor.netty.http.MicrometerHttpMetricsRecorder;

import javax.annotation.Nullable;
import java.time.Duration;

import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;

final class MicrometerHttpServerMetricsRecorder extends MicrometerHttpMetricsRecorder implements HttpServerMetricsRecorder {

	MicrometerHttpServerMetricsRecorder(String name, @Nullable String remoteAddress, String protocol) {
		super(name, remoteAddress, protocol);
	}

	@Override
	public void recordDataReceivedTime(String uri, String method, Duration time) {
		dataReceivedTimeBuilder.tags(URI, uri, METHOD, method)
		                       .register(registry)
		                       .record(time);
	}

	@Override
	public void recordDataSentTime(String uri, String method, String status, Duration time) {
		dataSentTimeBuilder.tags(URI, uri, METHOD, method, STATUS, status)
		                   .register(registry)
		                   .record(time);
	}

	@Override
	public void recordResponseTime(String uri, String method, String status, Duration time) {
		responseTimeBuilder.tags(URI, uri, METHOD, method, STATUS, status)
		                   .register(registry)
		                   .record(time);
	}
}
