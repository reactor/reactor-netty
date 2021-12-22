/*
 * Copyright (c) 2019-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
import reactor.netty.Metrics;
import reactor.netty.channel.MeterKey;
import reactor.netty.http.MicrometerHttpMetricsRecorder;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.ACTIVE_CONNECTIONS;
import static reactor.netty.Metrics.HTTP_SERVER_PREFIX;
import static reactor.netty.Metrics.LOCAL_ADDRESS;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 * @since 0.9
 */
final class MicrometerHttpServerMetricsRecorder extends MicrometerHttpMetricsRecorder implements HttpServerMetricsRecorder {

	final static MicrometerHttpServerMetricsRecorder INSTANCE = new MicrometerHttpServerMetricsRecorder();
	private final static String HTTP = "http";

	protected final Counter.Builder activeConnectionsBuilder;
	protected final ConcurrentMap<MeterKey, Counter> activeConnectionsCache = new ConcurrentHashMap<>();

	protected final Counter.Builder connectionsBuilder;
	protected final ConcurrentMap<SocketAddress, Counter> connectionsCache = new ConcurrentHashMap<>();

	private MicrometerHttpServerMetricsRecorder() {
		super(HTTP_SERVER_PREFIX, HTTP);

		this.activeConnectionsBuilder =
				Counter.builder(reactor.netty.Metrics.HTTP_SERVER_PREFIX + ACTIVE_CONNECTIONS)
						.description("The number of http connections currently processing requests");

		this.connectionsBuilder =
				Counter.builder(HTTP_SERVER_PREFIX + TOTAL_CONNECTIONS)
						.description("The number of all opened connections");
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
	public void incrementActiveConnections(String uri, String method, int amount) {
		Counter activeConnections =
				activeConnectionsCache.computeIfAbsent(new MeterKey(uri, null, method, null),
						key -> filter(activeConnectionsBuilder.tags(URI, uri, METHOD, method).register(REGISTRY)));

		if (activeConnections != null) {
			activeConnections.increment(amount);
		}
	}

	@Override
	public void incrementServerConnections(SocketAddress localAddress, int amount) {
		String address = Metrics.formatSocketAddress(localAddress);
		Counter connections =
				connectionsCache.computeIfAbsent(localAddress,
						key -> filter(connectionsBuilder.tags(URI, HTTP, LOCAL_ADDRESS, address).register(REGISTRY)));

		if (connections != null) {
			connections.increment(amount);
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
