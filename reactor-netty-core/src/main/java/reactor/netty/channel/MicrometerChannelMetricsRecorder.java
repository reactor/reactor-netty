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
package reactor.netty.channel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.noop.NoopMeter;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.ADDRESS_RESOLVER;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.LOCAL_ADDRESS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.TOTAL_CONNECTIONS;
import static reactor.netty.Metrics.URI;

/**
 * A {@link ChannelMetricsRecorder} implementation for integration with Micrometer.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
public class MicrometerChannelMetricsRecorder implements ChannelMetricsRecorder {

	final DistributionSummary.Builder dataReceivedBuilder;
	final ConcurrentMap<String, DistributionSummary> dataReceivedCache = new ConcurrentHashMap<>();

	final DistributionSummary.Builder dataSentBuilder;
	final ConcurrentMap<String, DistributionSummary> dataSentCache = new ConcurrentHashMap<>();

	final Counter.Builder errorCountBuilder;
	final ConcurrentMap<String, Counter> errorsCache = new ConcurrentHashMap<>();

	final Timer.Builder connectTimeBuilder;
	final ConcurrentMap<MeterKey, Timer> connectTimeCache = new ConcurrentHashMap<>();

	final Timer.Builder tlsHandshakeTimeBuilder;
	final ConcurrentMap<MeterKey, Timer> tlsHandshakeTimeCache = new ConcurrentHashMap<>();

	final Timer.Builder addressResolverTimeBuilder;
	final ConcurrentMap<MeterKey, Timer> addressResolverTimeCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, Counter> totalConnectionsCache = new ConcurrentHashMap<>();
	private final Counter.Builder totalConnectionsBuilder;

	public MicrometerChannelMetricsRecorder(String name, String protocol) {
		this.dataReceivedBuilder =
				DistributionSummary.builder(name + DATA_RECEIVED)
				                   .baseUnit("bytes")
				                   .description("Amount of the data received, in bytes")
				                   .tag(URI, protocol);

		this.dataSentBuilder =
				DistributionSummary.builder(name + DATA_SENT)
				                   .baseUnit("bytes")
				                   .description("Amount of the data sent, in bytes")
				                   .tag(URI, protocol);

		this.errorCountBuilder =
				Counter.builder(name + ERRORS)
				       .description("Number of errors that occurred")
				       .tag(URI, protocol);

		this.connectTimeBuilder =
				Timer.builder(name + CONNECT_TIME)
				     .description("Time spent for connecting to the remote address");

		this.tlsHandshakeTimeBuilder =
				Timer.builder(name + TLS_HANDSHAKE_TIME)
				     .description("Time spent for TLS handshake");

		this.addressResolverTimeBuilder =
				Timer.builder(name + ADDRESS_RESOLVER)
				     .description("Time spent for resolving the address");

		this.totalConnectionsBuilder =
				Counter.builder(name + TOTAL_CONNECTIONS)
						.description("The number of all opened connections")
						.tag(URI, protocol);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		DistributionSummary ds = dataReceivedCache.get(address);
		ds = ds != null ? ds : dataReceivedCache.computeIfAbsent(address,
				key -> filter(dataReceivedBuilder.tag(REMOTE_ADDRESS, address)
				                                 .register(REGISTRY)));
		if (ds != null) {
			ds.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		DistributionSummary ds = dataSentCache.get(address);
		ds = ds != null ? ds : dataSentCache.computeIfAbsent(address,
				key -> filter(dataSentBuilder.tag(REMOTE_ADDRESS, address)
				                             .register(REGISTRY)));
		if (ds != null) {
			ds.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		Counter c = errorsCache.get(address);
		c = c != null ? c : errorsCache.computeIfAbsent(address,
				key -> filter(errorCountBuilder.tag(REMOTE_ADDRESS, address)
				                               .register(REGISTRY)));
		if (c != null) {
			c.increment();
		}
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(null, address, null, status);
		Timer timer = tlsHandshakeTimeCache.get(meterKey);
		timer = timer != null ? timer : tlsHandshakeTimeCache.computeIfAbsent(meterKey,
				key -> filter(tlsHandshakeTimeBuilder.tags(REMOTE_ADDRESS, address, STATUS, status)
				                                     .register(REGISTRY)));
		if (timer != null) {
			timer.record(time);
		}
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(null, address, null, status);
		Timer timer = connectTimeCache.get(meterKey);
		timer = timer != null ? timer : connectTimeCache.computeIfAbsent(meterKey,
				key -> filter(connectTimeBuilder.tags(REMOTE_ADDRESS, address, STATUS, status)
				                                .register(REGISTRY)));
		if (timer != null) {
			timer.record(time);
		}
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(null, address, null, status);
		Timer timer = addressResolverTimeCache.get(meterKey);
		timer = timer != null ? timer : addressResolverTimeCache.computeIfAbsent(meterKey,
				key -> filter(addressResolverTimeBuilder.tags(REMOTE_ADDRESS, address, STATUS, status)
				                                        .register(REGISTRY)));
		if (timer != null) {
			timer.record(time);
		}
	}

	@Override
	public void incrementServerConnections(SocketAddress serverAddr, int amount) {
		String address = reactor.netty.Metrics.formatSocketAddress(serverAddr);
		MeterKey meterKey = new MeterKey(null, address, null, null);
		Counter totalConnections = totalConnectionsCache.get(meterKey);
		totalConnections = totalConnections != null ? totalConnections : totalConnectionsCache.computeIfAbsent(meterKey,
						key -> filter(totalConnectionsBuilder.tags(LOCAL_ADDRESS, address).register(REGISTRY)));

		if (totalConnections != null) {
			totalConnections.increment(amount);
		}
	}

	@Nullable
	protected static <M extends Meter> M filter(M meter) {
		if (meter instanceof NoopMeter) {
			return null;
		}
		else {
			return meter;
		}
	}
}
