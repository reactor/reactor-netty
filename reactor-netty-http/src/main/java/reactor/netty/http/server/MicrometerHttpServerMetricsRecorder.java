/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import reactor.netty.channel.MeterKey;
import reactor.netty.http.MicrometerHttpMetricsRecorder;

import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty.Metrics.HTTP_SERVER_PREFIX;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 * @since 0.9
 */
final class MicrometerHttpServerMetricsRecorder extends MicrometerHttpMetricsRecorder implements HttpServerMetricsRecorder {

	final static MicrometerHttpServerMetricsRecorder INSTANCE = new MicrometerHttpServerMetricsRecorder();

	private MicrometerHttpServerMetricsRecorder() {
		super(HTTP_SERVER_PREFIX, "http");
	}

	@Override
	public void recordDataReceivedTime(String uri, String method, Duration time) {
		Timer dataReceivedTime = dataReceivedTimeCache.computeIfAbsent(new MeterKey(uri, null, method, null),
				key -> filter(dataReceivedTimeBuilder.tags(URI, uri, METHOD, method)
				                                     .register(REGISTRY)));
		if (dataReceivedTime != null) {
			dataReceivedTime.record(time);
		}
	}

	@Override
	public void recordDataSentTime(String uri, String method, String status, Duration time) {
		Timer dataSentTime = dataSentTimeCache.computeIfAbsent(new MeterKey(uri, null, method, status),
				key -> filter(dataSentTimeBuilder.tags(URI, uri, METHOD, method, STATUS, status)
				                                 .register(REGISTRY)));
		if (dataSentTime != null) {
			dataSentTime.record(time);
		}
	}

	@Override
	public void recordResponseTime(String uri, String method, String status, Duration time) {
		Timer responseTime = responseTimeCache.computeIfAbsent(new MeterKey(uri, null, method, status),
				key -> filter(responseTimeBuilder.tags(URI, uri, METHOD, method, STATUS, status)
				                                 .register(REGISTRY)));
		if (responseTime != null) {
			responseTime.record(time);
		}
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		DistributionSummary dataReceived = dataReceivedCache.computeIfAbsent(new MeterKey(uri, null, null, null),
				key -> filter(dataReceivedBuilder.tags(URI, uri)
				                                 .register(REGISTRY)));
		if (dataReceived != null) {
			dataReceived.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		DistributionSummary dataSent = dataSentCache.computeIfAbsent(new MeterKey(uri, null, null, null),
				key -> filter(dataSentBuilder.tags(URI, uri)
				                             .register(REGISTRY)));
		if (dataSent != null) {
			dataSent.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		Counter errors = errorsCache.computeIfAbsent(new MeterKey(uri, null, null, null),
				key -> filter(errorsBuilder.tags(URI, uri)
				                           .register(REGISTRY)));
		if (errors != null) {
			errors.increment();
		}
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		// noop
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		// noop
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress) {
		// noop
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		// noop
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		throw new UnsupportedOperationException();
	}
}
