/*
 * Copyright (c) 2019-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import reactor.netty.channel.MeterKey;
import reactor.netty.http.MicrometerHttpMetricsRecorder;
import reactor.netty.internal.util.MapUtils;

import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.HTTP_SERVER_PREFIX;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.RESPONSE_TIME;
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
		MeterKey meterKey = new MeterKey(uri, null, method, null);
		Timer dataReceivedTime = MapUtils.computeIfAbsent(dataReceivedTimeCache, meterKey,
				key -> filter(Timer.builder(name() + DATA_RECEIVED_TIME)
				                   .description(DATA_RECEIVED_TIME_DESCRIPTION)
				                   .tags(URI, uri, METHOD, method)
				                   .register(REGISTRY)));
		if (dataReceivedTime != null) {
			dataReceivedTime.record(time);
		}
	}

	@Override
	public void recordDataSentTime(String uri, String method, String status, Duration time) {
		MeterKey meterKey = new MeterKey(uri, null, method, status);
		Timer dataSentTime = MapUtils.computeIfAbsent(dataSentTimeCache, meterKey,
				key -> filter(Timer.builder(name() + DATA_SENT_TIME)
				                   .description(DATA_SENT_TIME_DESCRIPTION)
				                   .tags(URI, uri, METHOD, method, STATUS, status)
				                   .register(REGISTRY)));
		if (dataSentTime != null) {
			dataSentTime.record(time);
		}
	}

	@Override
	public void recordResponseTime(String uri, String method, String status, Duration time) {
		MeterKey meterKey = new MeterKey(uri, null, method, status);
		Timer responseTime = MapUtils.computeIfAbsent(responseTimeCache, meterKey,
				key -> filter(Timer.builder(name() + RESPONSE_TIME)
				                   .description(RESPONSE_TIME_DESCRIPTION)
				                   .tags(URI, uri, METHOD, method, STATUS, status)
				                   .register(REGISTRY)));
		if (responseTime != null) {
			responseTime.record(time);
		}
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		MeterKey meterKey = new MeterKey(uri, null, null, null);
		DistributionSummary dataReceived = MapUtils.computeIfAbsent(dataReceivedCache, meterKey,
				key -> filter(DistributionSummary.builder(name() + DATA_RECEIVED)
				                                 .baseUnit(BYTES_UNIT)
				                                 .description(DATA_RECEIVED_DESCRIPTION).tags(URI, uri)
				                                 .register(REGISTRY)));
		if (dataReceived != null) {
			dataReceived.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		MeterKey meterKey = new MeterKey(uri, null, null, null);
		DistributionSummary dataSent = MapUtils.computeIfAbsent(dataSentCache, meterKey,
				key -> filter(DistributionSummary.builder(name() + DATA_SENT)
				                                 .baseUnit(BYTES_UNIT)
				                                 .description(DATA_SENT_DESCRIPTION)
				                                 .tags(URI, uri)
				                                 .register(REGISTRY)));
		if (dataSent != null) {
			dataSent.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		MeterKey meterKey = new MeterKey(uri, null, null, null);
		Counter errors = MapUtils.computeIfAbsent(errorsCache, meterKey,
				key -> filter(Counter.builder(name() + ERRORS)
				                     .description(ERRORS_DESCRIPTION)
				                     .tags(URI, uri)
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
