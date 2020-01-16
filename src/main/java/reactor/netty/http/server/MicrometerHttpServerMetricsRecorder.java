/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
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

import io.micrometer.core.instrument.Timer;
import reactor.netty.Metrics;
import reactor.netty.channel.MeterKey;
import reactor.netty.http.MicrometerHttpMetricsRecorder;

import java.time.Duration;

import static reactor.netty.Metrics.*;

/**
 * @author Violeta Georgieva
 */
final class MicrometerHttpServerMetricsRecorder extends MicrometerHttpMetricsRecorder implements HttpServerMetricsRecorder {

	final static MicrometerHttpServerMetricsRecorder INSTANCE = new MicrometerHttpServerMetricsRecorder();

	private MicrometerHttpServerMetricsRecorder() {
		this(Metrics.MAX_URI_TAGS);
	}

	public MicrometerHttpServerMetricsRecorder(int maxLabels) {
		super("reactor.netty.http.server", "http", maxLabels);
	}

	@Override
	public void recordDataReceivedTime(String uri, String method, Duration time) {
		Timer dataReceivedTime = dataReceivedTimeCache.computeIfAbsent(new MeterKey(uri, null, method, null),
				key -> dataReceivedTimeBuilder.tags(URI, uri, METHOD, method)
				                              .register(registry));
		dataReceivedTime.record(time);
	}

	@Override
	public void recordDataSentTime(String uri, String method, String status, Duration time) {
		Timer dataSentTime = dataSentTimeCache.computeIfAbsent(new MeterKey(uri, null, method, status),
				key -> dataSentTimeBuilder.tags(URI, uri, METHOD, method, STATUS, status)
				                          .register(registry));
		dataSentTime.record(time);
	}

	@Override
	public void recordResponseTime(String uri, String method, String status, Duration time) {
		Timer responseTime = responseTimeCache.computeIfAbsent(new MeterKey(uri, null, method, status),
				key -> responseTimeBuilder.tags(URI, uri, METHOD, method, STATUS, status)
				                          .register(registry));
		responseTime.record(time);
	}
}
