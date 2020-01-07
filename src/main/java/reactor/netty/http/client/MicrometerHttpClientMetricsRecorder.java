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
package reactor.netty.http.client;

import io.micrometer.core.instrument.Timer;
import reactor.netty.Metrics;
import reactor.netty.channel.MeterKey;
import reactor.netty.http.MicrometerHttpMetricsRecorder;

import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 */
final class MicrometerHttpClientMetricsRecorder extends MicrometerHttpMetricsRecorder implements HttpClientMetricsRecorder {

	final static MicrometerHttpClientMetricsRecorder INSTANCE = new MicrometerHttpClientMetricsRecorder();

	private MicrometerHttpClientMetricsRecorder() {
		super("reactor.netty.http.client", "http");
	}

	@Override
	public void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = MeterKey.builder().uri(uri).remoteAddress(address).method(method).status(status).build();
		Timer dataReceivedTime = dataReceivedTimeCache.computeIfAbsent(meterKey,
				key -> dataReceivedTimeBuilder.tags(REMOTE_ADDRESS, address, URI, uri, METHOD, method, STATUS, status)
				                              .register(registry));
		dataReceivedTime.record(time);
	}

	@Override
	public void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = MeterKey.builder().uri(uri).remoteAddress(address).method(method).build();
		Timer dataSentTime = dataSentTimeCache.computeIfAbsent(meterKey,
				key -> dataSentTimeBuilder.tags(REMOTE_ADDRESS, address, URI, uri, METHOD, method)
				                          .register(registry));
		dataSentTime.record(time);
	}

	@Override
	public void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = MeterKey.builder().uri(uri).remoteAddress(address).method(method).status(status).build();
		Timer responseTime = responseTimeCache.computeIfAbsent(meterKey,
				key -> responseTimeBuilder.tags(REMOTE_ADDRESS, address, URI, uri, METHOD, method, STATUS, status)
				                          .register(registry));
		responseTime.record(time);
	}
}
