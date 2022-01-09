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
package reactor.netty.channel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.noop.NoopMeter;
import reactor.netty.internal.util.MapUtils;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

import static reactor.netty.Metrics.ADDRESS_RESOLVER;
import static reactor.netty.Metrics.CONNECTIONS_TOTAL;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.LOCAL_ADDRESS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.URI;

/**
 * A {@link ChannelMetricsRecorder} implementation for integration with Micrometer.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
public class MicrometerChannelMetricsRecorder implements ChannelMetricsRecorder {
	static final String ADDRESS_RESOLVER_TIME_DESCRIPTION = "Time spent for resolving the address";
	protected static final String BYTES_UNIT = "bytes";
	static final String CONNECT_TIME_DESCRIPTION = "Time spent for connecting to the remote address";
	protected static final String DATA_RECEIVED_DESCRIPTION = "Amount of the data received, in bytes";
	protected static final String DATA_SENT_DESCRIPTION = "Amount of the data sent, in bytes";
	protected static final String ERRORS_DESCRIPTION = "Number of errors that occurred";
	static final String TLS_HANDSHAKE_TIME_DESCRIPTION = "Time spent for TLS handshake";
	static final String TOTAL_CONNECTIONS_DESCRIPTION = "The number of all opened connections";

	final ConcurrentMap<String, DistributionSummary> dataReceivedCache = new ConcurrentHashMap<>();

	final ConcurrentMap<String, DistributionSummary> dataSentCache = new ConcurrentHashMap<>();

	final ConcurrentMap<String, Counter> errorsCache = new ConcurrentHashMap<>();

	final ConcurrentMap<MeterKey, Timer> connectTimeCache = new ConcurrentHashMap<>();

	final ConcurrentMap<MeterKey, Timer> tlsHandshakeTimeCache = new ConcurrentHashMap<>();

	final ConcurrentMap<MeterKey, Timer> addressResolverTimeCache = new ConcurrentHashMap<>();

	final ConcurrentMap<String, LongAdder> totalConnectionsCache = new ConcurrentHashMap<>();
	final LongAdder totalConnectionsAdder = new LongAdder();
	final String name;
	final String protocol;

	public MicrometerChannelMetricsRecorder(String name, String protocol) {
		this.name = name;
		this.protocol = protocol;
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		DistributionSummary ds = MapUtils.computeIfAbsent(dataReceivedCache, address,
				key -> filter(DistributionSummary.builder(name + DATA_RECEIVED)
				                                 .baseUnit(BYTES_UNIT)
				                                 .description(DATA_RECEIVED_DESCRIPTION)
				                                 .tags(URI, protocol, REMOTE_ADDRESS, address)
				                                 .register(REGISTRY)));
		if (ds != null) {
			ds.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		DistributionSummary ds = MapUtils.computeIfAbsent(dataSentCache, address,
				key -> filter(DistributionSummary.builder(name + DATA_SENT)
				                                 .baseUnit(BYTES_UNIT)
				                                 .description(DATA_SENT_DESCRIPTION)
				                                 .tags(URI, protocol, REMOTE_ADDRESS, address)
				                                 .register(REGISTRY)));
		if (ds != null) {
			ds.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		Counter c = MapUtils.computeIfAbsent(errorsCache, address,
				key -> filter(Counter.builder(name + ERRORS)
				                     .description(ERRORS_DESCRIPTION)
				                     .tags(URI, protocol, REMOTE_ADDRESS, address)
				                     .register(REGISTRY)));
		if (c != null) {
			c.increment();
		}
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(null, address, null, status);
		Timer timer = MapUtils.computeIfAbsent(tlsHandshakeTimeCache, meterKey,
				key -> filter(Timer.builder(name + TLS_HANDSHAKE_TIME)
				                   .description(TLS_HANDSHAKE_TIME_DESCRIPTION)
				                   .tags(REMOTE_ADDRESS, address, STATUS, status)
				                   .register(REGISTRY)));
		if (timer != null) {
			timer.record(time);
		}
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(null, address, null, status);
		Timer timer = MapUtils.computeIfAbsent(connectTimeCache, meterKey,
				key -> filter(Timer.builder(name + CONNECT_TIME)
				                   .description(CONNECT_TIME_DESCRIPTION)
				                   .tags(REMOTE_ADDRESS, address, STATUS, status)
				                   .register(REGISTRY)));
		if (timer != null) {
			timer.record(time);
		}
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(null, address, null, status);
		Timer timer = MapUtils.computeIfAbsent(addressResolverTimeCache, meterKey,
				key -> filter(Timer.builder(name + ADDRESS_RESOLVER)
				                   .description(ADDRESS_RESOLVER_TIME_DESCRIPTION)
				                   .tags(REMOTE_ADDRESS, address, STATUS, status)
				                   .register(REGISTRY)));
		if (timer != null) {
			timer.record(time);
		}
	}

	@Override
	public void recordServerConnectionOpened(SocketAddress serverAddress) {
		LongAdder totalConnectionAdder = getTotalConnectionsAdder(serverAddress);
		if (totalConnectionAdder != null) {
			totalConnectionAdder.increment();
		}
	}

	@Override
	public void recordServerConnectionClosed(SocketAddress serverAddress) {
		LongAdder totalConnectionAdder = getTotalConnectionsAdder(serverAddress);
		if (totalConnectionAdder != null) {
			totalConnectionAdder.decrement();
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

	protected String name() {
		return name;
	}

	protected String protocol() {
		return protocol;
	}

	private LongAdder getTotalConnectionsAdder(SocketAddress serverAddress) {
		String address = reactor.netty.Metrics.formatSocketAddress(serverAddress);
		LongAdder adder = totalConnectionsCache.get(address);
		adder = adder != null ? adder : totalConnectionsCache.computeIfAbsent(address,
				key -> {
					Gauge gauge = filter(Gauge.builder(name + CONNECTIONS_TOTAL, totalConnectionsAdder, LongAdder::longValue)
							.description(TOTAL_CONNECTIONS_DESCRIPTION)
							.tags(URI, protocol, LOCAL_ADDRESS, address)
							.register(REGISTRY));
					return gauge != null ? totalConnectionsAdder : null;
				});
		return adder;
	}
}
