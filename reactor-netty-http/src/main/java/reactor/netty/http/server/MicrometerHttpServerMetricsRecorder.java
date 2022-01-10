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
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import reactor.netty.channel.MeterKey;
import reactor.netty.http.MicrometerHttpMetricsRecorder;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
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
	private final static String PROTOCOL_VALUE_HTTP = "http";

	private final Gauge.Builder<LongAdder> activeConnectionsBuilder;
	private final LongAdder activeConnectionsAdder = new LongAdder();
	private final ConcurrentMap<MeterKey, LongAdder> activeConnectionsCache = new ConcurrentHashMap<>();

	private MicrometerHttpServerMetricsRecorder() {
		super(HTTP_SERVER_PREFIX, PROTOCOL_VALUE_HTTP);

		this.activeConnectionsBuilder =
				Gauge.builder(reactor.netty.Metrics.HTTP_SERVER_PREFIX + ACTIVE_CONNECTIONS, activeConnectionsAdder, LongAdder::doubleValue)
						.description("The number of http connections currently processing requests");
	}

	@Override
	public void recordDataReceivedTime(String uri, String method, Duration time) {
		MeterKey meterKey = new MeterKey(uri, null, method, null);
		Timer dataReceivedTime = dataReceivedTimeCache.get(meterKey);
		dataReceivedTime = dataReceivedTime != null ? dataReceivedTime : dataReceivedTimeCache.computeIfAbsent(meterKey,
				key -> filter(dataReceivedTimeBuilder.tags(URI, uri, METHOD, method)
				                                     .register(REGISTRY)));
		if (dataReceivedTime != null) {
			dataReceivedTime.record(time);
		}
	}

	@Override
	public void recordDataSentTime(String uri, String method, String status, Duration time) {
		MeterKey meterKey = new MeterKey(uri, null, method, status);
		Timer dataSentTime = dataSentTimeCache.get(meterKey);
		dataSentTime = dataSentTime != null ? dataSentTime : dataSentTimeCache.computeIfAbsent(meterKey,
				key -> filter(dataSentTimeBuilder.tags(URI, uri, METHOD, method, STATUS, status)
				                                 .register(REGISTRY)));
		if (dataSentTime != null) {
			dataSentTime.record(time);
		}
	}

	@Override
	public void recordResponseTime(String uri, String method, String status, Duration time) {
		MeterKey meterKey = new MeterKey(uri, null, method, status);
		Timer responseTime = responseTimeCache.get(meterKey);
		responseTime = responseTime != null ? responseTime : responseTimeCache.computeIfAbsent(meterKey,
				key -> filter(responseTimeBuilder.tags(URI, uri, METHOD, method, STATUS, status)
				                                 .register(REGISTRY)));
		if (responseTime != null) {
			responseTime.record(time);
		}
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		MeterKey meterKey = new MeterKey(uri, null, null, null);
		DistributionSummary dataReceived = dataReceivedCache.get(meterKey);
		dataReceived = dataReceived != null ? dataReceived : dataReceivedCache.computeIfAbsent(meterKey,
				key -> filter(dataReceivedBuilder.tags(URI, uri)
				                                 .register(REGISTRY)));
		if (dataReceived != null) {
			dataReceived.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		MeterKey meterKey = new MeterKey(uri, null, null, null);
		DistributionSummary dataSent = dataSentCache.get(meterKey);
		dataSent = dataSent != null ? dataSent : dataSentCache.computeIfAbsent(meterKey,
				key -> filter(dataSentBuilder.tags(URI, uri)
				                             .register(REGISTRY)));
		if (dataSent != null) {
			dataSent.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		MeterKey meterKey = new MeterKey(uri, null, null, null);
		Counter errors = errorsCache.get(meterKey);
		errors = errors != null ? errors : errorsCache.computeIfAbsent(meterKey,
				key -> filter(errorsBuilder.tags(URI, uri)
				                           .register(REGISTRY)));
		if (errors != null) {
			errors.increment();
		}
	}

	@Override
	public void recordServerConnectionActive(String uri, String method) {
		LongAdder adder = getServerConnectionAdder(uri, method);
		if (adder != null) {
			adder.add(1);
		}
	}

	@Override
	public void recordServerConnectionInactive(String uri, String method) {
		LongAdder adder = getServerConnectionAdder(uri, method);
		if (adder != null) {
			adder.add(-1);
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

	private LongAdder getServerConnectionAdder(String uri, String method) {
		MeterKey meterKey = new MeterKey(uri, null, method, null);
		LongAdder adder = activeConnectionsCache.get(meterKey);
		adder = adder != null ? adder : activeConnectionsCache.computeIfAbsent(meterKey,
				key -> {
					Gauge gauge = filter(activeConnectionsBuilder.tags(URI, uri, METHOD, method)
							.register(REGISTRY));
					return gauge != null ? activeConnectionsAdder : null;
				});

		return adder;
	}
}
