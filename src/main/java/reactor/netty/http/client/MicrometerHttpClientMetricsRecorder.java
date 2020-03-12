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

import io.micrometer.core.instrument.Timer;
import reactor.netty.Metrics;
import reactor.netty.channel.MeterKey;
import reactor.netty.http.MicrometerHttpMetricsRecorder;

import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty.Metrics.HTTP_CLIENT_PREFIX;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 * @since 0.9
 */
final class MicrometerHttpClientMetricsRecorder extends MicrometerHttpMetricsRecorder implements HttpClientMetricsRecorder {

	final static MicrometerHttpClientMetricsRecorder INSTANCE = new MicrometerHttpClientMetricsRecorder();

	private MicrometerHttpClientMetricsRecorder() {
		super(HTTP_CLIENT_PREFIX, "http");
	}

	@Override
	public void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		Timer dataReceivedTime = dataReceivedTimeCache.computeIfAbsent(new MeterKey(uri, address, method, status),
				key -> dataReceivedTimeBuilder.tags(REMOTE_ADDRESS, address, URI, uri, METHOD, method, STATUS, status)
				                              .register(REGISTRY));
		dataReceivedTime.record(time);
	}

	@Override
	public void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		Timer dataSentTime = dataSentTimeCache.computeIfAbsent(new MeterKey(uri, address, method, null),
				key -> dataSentTimeBuilder.tags(REMOTE_ADDRESS, address, URI, uri, METHOD, method)
				                          .register(REGISTRY));
		dataSentTime.record(time);
	}

	@Override
	public void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		Timer responseTime = responseTimeCache.computeIfAbsent(new MeterKey(uri, address, method, status),
				key -> responseTimeBuilder.tags(REMOTE_ADDRESS, address, URI, uri, METHOD, method, STATUS, status)
				                          .register(REGISTRY));
		responseTime.record(time);
	}
}
