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
import reactor.netty.internal.util.MapUtils;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

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
import static reactor.netty.http.server.HttpServerMeters.CONNECTIONS_ACTIVE;

/**
 * @author Violeta Georgieva
 * @since 0.9
 */
final class MicrometerHttpServerMetricsRecorder extends MicrometerHttpMetricsRecorder implements HttpServerMetricsRecorder {

	final static MicrometerHttpServerMetricsRecorder INSTANCE = new MicrometerHttpServerMetricsRecorder();
	private final static String PROTOCOL_VALUE_HTTP = "http";
	private final LongAdder activeConnectionsAdder = new LongAdder();
	private final ConcurrentMap<String, LongAdder> activeConnectionsCache = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, DistributionSummary> dataReceivedCache = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, DistributionSummary> dataSentCache = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Counter> errorsCache = new ConcurrentHashMap<>();

	private MicrometerHttpServerMetricsRecorder() {
		super(HTTP_SERVER_PREFIX, PROTOCOL_VALUE_HTTP);
	}

	@Override
	public void recordDataReceivedTime(String uri, String method, Duration time) {
		MeterKey meterKey = new MeterKey(uri, null, method, null);
		Timer dataReceivedTime = MapUtils.computeIfAbsent(dataReceivedTimeCache, meterKey,
				key -> filter(Timer.builder(name() + DATA_RECEIVED_TIME)
				                   .tags(HttpServerMeters.DataReceivedTimeTags.URI.getKeyName(), uri,
				                         HttpServerMeters.DataReceivedTimeTags.METHOD.getKeyName(), method)
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
				                   .tags(HttpServerMeters.DataSentTimeTags.URI.getKeyName(), uri,
				                         HttpServerMeters.DataSentTimeTags.METHOD.getKeyName(), method,
				                         HttpServerMeters.DataSentTimeTags.STATUS.getKeyName(), status)
				                   .register(REGISTRY)));
		if (dataSentTime != null) {
			dataSentTime.record(time);
		}
	}

	@Override
	public void recordResponseTime(String uri, String method, String status, Duration time) {
		Timer responseTime = getResponseTimeTimer(name() + RESPONSE_TIME, uri, method, status);
		if (responseTime != null) {
			responseTime.record(time);
		}
	}

	@Nullable
	final Timer getResponseTimeTimer(String name, String uri, String method, String status) {
		MeterKey meterKey = new MeterKey(uri, null, method, status);
		return MapUtils.computeIfAbsent(responseTimeCache, meterKey,
				key -> filter(Timer.builder(name)
						.tags(URI, uri, METHOD, method, STATUS, status)
						.register(REGISTRY)));
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		DistributionSummary dataReceived = MapUtils.computeIfAbsent(dataReceivedCache, uri,
				key -> filter(DistributionSummary.builder(name() + DATA_RECEIVED)
				                                 .baseUnit(HttpServerMeters.HTTP_SERVER_DATA_RECEIVED.getBaseUnit())
				                                 .tags(HttpServerMeters.HttpServerMetersTags.URI.getKeyName(), uri)
				                                 .register(REGISTRY)));
		if (dataReceived != null) {
			dataReceived.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		DistributionSummary dataSent = MapUtils.computeIfAbsent(dataSentCache, uri,
				key -> filter(DistributionSummary.builder(name() + DATA_SENT)
				                                 .baseUnit(HttpServerMeters.HTTP_SERVER_DATA_SENT.getBaseUnit())
				                                 .tags(HttpServerMeters.HttpServerMetersTags.URI.getKeyName(), uri)
				                                 .register(REGISTRY)));
		if (dataSent != null) {
			dataSent.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		Counter errors = MapUtils.computeIfAbsent(errorsCache, uri,
				key -> filter(Counter.builder(name() + ERRORS)
				                     .tags(HttpServerMeters.HttpServerMetersTags.URI.getKeyName(), uri)
				                     .register(REGISTRY)));
		if (errors != null) {
			errors.increment();
		}
	}

	@Override
	public void recordServerConnectionActive(SocketAddress localAddress) {
		LongAdder adder = getServerConnectionAdder(localAddress);
		if (adder != null) {
			adder.increment();
		}
	}

	@Override
	public void recordServerConnectionInactive(SocketAddress localAddress) {
		LongAdder adder = getServerConnectionAdder(localAddress);
		if (adder != null) {
			adder.decrement();
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

	private LongAdder getServerConnectionAdder(SocketAddress localAddress) {
		String address = reactor.netty.Metrics.formatSocketAddress(localAddress);
		return MapUtils.computeIfAbsent(activeConnectionsCache, address,
				key -> {
					Gauge gauge = filter(
							Gauge.builder(CONNECTIONS_ACTIVE.getName(), activeConnectionsAdder, LongAdder::longValue)
							     .tags(HttpServerMeters.ConnectionsActiveTags.URI.getKeyName(), PROTOCOL_VALUE_HTTP,
							           HttpServerMeters.ConnectionsActiveTags.LOCAL_ADDRESS.getKeyName(), address)
							     .register(REGISTRY));
					return gauge != null ? activeConnectionsAdder : null;
				});
	}
}
