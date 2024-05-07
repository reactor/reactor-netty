/*
 * Copyright (c) 2019-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.PROXY_ADDRESS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * A {@link ChannelMetricsRecorder} implementation for integration with Micrometer.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
public class MicrometerChannelMetricsRecorder implements ChannelMetricsRecorder {
	final ConcurrentMap<MeterKey, DistributionSummary> dataReceivedCache = new ConcurrentHashMap<>();

	final ConcurrentMap<MeterKey, DistributionSummary> dataSentCache = new ConcurrentHashMap<>();

	final ConcurrentMap<MeterKey, Counter> errorsCache = new ConcurrentHashMap<>();

	final ConcurrentMap<MeterKey, Timer> connectTimeCache = new ConcurrentHashMap<>();

	final ConcurrentMap<MeterKey, Timer> tlsHandshakeTimeCache = new ConcurrentHashMap<>();

	final ConcurrentMap<MeterKey, Timer> addressResolverTimeCache = new ConcurrentHashMap<>();

	final ConcurrentMap<String, LongAdder> totalConnectionsCache = new ConcurrentHashMap<>();

	final String name;
	final String protocol;
	final boolean onServer;

	public MicrometerChannelMetricsRecorder(String name, String protocol) {
		this(name, protocol, true);
	}

	public MicrometerChannelMetricsRecorder(String name, String protocol, boolean onServer) {
		this.name = name;
		this.protocol = protocol;
		this.onServer = onServer;
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		recordDataReceived(remoteAddress, NA, bytes);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recordDataReceived(remoteAddress, formatSocketAddress(proxyAddress), bytes);
	}

	void recordDataReceived(SocketAddress remoteAddress, @Nullable String proxyAddress, long bytes) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(null, address, proxyAddress, null, null);
		DistributionSummary ds = MapUtils.computeIfAbsent(dataReceivedCache, meterKey, key -> {
			DistributionSummary.Builder builder =
					DistributionSummary.builder(name + DATA_RECEIVED)
					                   .baseUnit(ChannelMeters.DATA_RECEIVED.getBaseUnit())
					                   .tags(ChannelMeters.ChannelMetersTags.URI.asString(), protocol,
					                         ChannelMeters.ChannelMetersTags.REMOTE_ADDRESS.asString(), address);
			if (!onServer) {
				builder.tag(ChannelMeters.ChannelMetersTags.PROXY_ADDRESS.asString(), proxyAddress);
			}
			return filter(builder.register(REGISTRY));
		});
		if (ds != null) {
			ds.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		recordDataSent(remoteAddress, NA, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, SocketAddress proxyAddress, long bytes) {
		recordDataSent(remoteAddress, formatSocketAddress(proxyAddress), bytes);
	}

	void recordDataSent(SocketAddress remoteAddress, @Nullable String proxyAddress, long bytes) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(null, address, proxyAddress, null, null);
		DistributionSummary ds = MapUtils.computeIfAbsent(dataSentCache, meterKey, key -> {
			DistributionSummary.Builder builder =
					DistributionSummary.builder(name + DATA_SENT)
					                   .baseUnit(ChannelMeters.DATA_SENT.getBaseUnit())
					                   .tags(ChannelMeters.ChannelMetersTags.URI.asString(), protocol,
					                         ChannelMeters.ChannelMetersTags.REMOTE_ADDRESS.asString(), address);
			if (!onServer) {
				builder.tag(ChannelMeters.ChannelMetersTags.PROXY_ADDRESS.asString(), proxyAddress);
			}
			return filter(builder.register(REGISTRY));
		});
		if (ds != null) {
			ds.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress) {
		incrementErrorsCount(remoteAddress, NA);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, SocketAddress proxyAddress) {
		incrementErrorsCount(remoteAddress, formatSocketAddress(proxyAddress));
	}

	void incrementErrorsCount(SocketAddress remoteAddress, @Nullable String proxyAddress) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(null, address, proxyAddress, null, null);
		Counter c = MapUtils.computeIfAbsent(errorsCache, meterKey, key -> {
			Counter.Builder builder = Counter.builder(name + ERRORS)
			                                 .tags(ChannelMeters.ChannelMetersTags.URI.asString(), protocol,
			                                       ChannelMeters.ChannelMetersTags.REMOTE_ADDRESS.asString(), address);
			if (!onServer) {
				builder.tag(ChannelMeters.ChannelMetersTags.PROXY_ADDRESS.asString(), proxyAddress);
			}
			return filter(builder.register(REGISTRY));
		});
		if (c != null) {
			c.increment();
		}
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		Timer timer = getTlsHandshakeTimer(name + TLS_HANDSHAKE_TIME, formatSocketAddress(remoteAddress), NA, status);
		if (timer != null) {
			timer.record(time);
		}
	}

	/**
	 * Returns TLS handshake timer.
	 *
	 * @param name the timer name
	 * @param address the remote address
	 * @param status the status of the TLS handshake operation
	 * @return TLS handshake timer
	 * @deprecated as of 1.1.19. Prefer the {@link #getTlsHandshakeTimer(String, String, String, String)}.
	 * This method will be removed in version 1.3.0.
	 */
	@Nullable
	@Deprecated
	public final Timer getTlsHandshakeTimer(String name, @Nullable String address, String status) {
		MeterKey meterKey = new MeterKey(null, address, null, null, status);
		return MapUtils.computeIfAbsent(tlsHandshakeTimeCache, meterKey,
				key -> filter(Timer.builder(name)
				                   .tags(REMOTE_ADDRESS, address, STATUS, status)
				                   .register(REGISTRY)));
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, SocketAddress proxyAddress, Duration time, String status) {
		Timer timer = getTlsHandshakeTimer(name + TLS_HANDSHAKE_TIME, formatSocketAddress(remoteAddress), formatSocketAddress(proxyAddress), status);
		if (timer != null) {
			timer.record(time);
		}
	}

	/**
	 * Returns TLS handshake timer.
	 *
	 * @param name the timer name
	 * @param remoteAddress the remote address
	 * @param proxyAddress the proxy address
	 * @param status the status of the TLS handshake operation
	 * @return TLS handshake timer
	 */
	@Nullable
	public final Timer getTlsHandshakeTimer(String name, @Nullable String remoteAddress, @Nullable String proxyAddress, String status) {
		MeterKey meterKey = new MeterKey(null, remoteAddress, proxyAddress, null, status);
		return MapUtils.computeIfAbsent(tlsHandshakeTimeCache, meterKey, key -> {
			Timer.Builder builder = Timer.builder(name).tags(REMOTE_ADDRESS, remoteAddress, STATUS, status);
			if (!onServer) {
				builder.tag(PROXY_ADDRESS, proxyAddress);
			}
			return filter(builder.register(REGISTRY));
		});
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		Timer timer = getConnectTimer(name + CONNECT_TIME, formatSocketAddress(remoteAddress), NA, status);
		if (timer != null) {
			timer.record(time);
		}
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, SocketAddress proxyAddress, Duration time, String status) {
		Timer timer = getConnectTimer(name + CONNECT_TIME, formatSocketAddress(remoteAddress), formatSocketAddress(proxyAddress), status);
		if (timer != null) {
			timer.record(time);
		}
	}

	@Nullable
	final Timer getConnectTimer(String name, @Nullable String remoteAddress, @Nullable String proxyAddress, String status) {
		MeterKey meterKey = new MeterKey(null, remoteAddress, proxyAddress, null, status);
		return MapUtils.computeIfAbsent(connectTimeCache, meterKey,
				key -> filter(Timer.builder(name)
				                   .tags(REMOTE_ADDRESS, remoteAddress, PROXY_ADDRESS, proxyAddress, STATUS, status)
				                   .register(REGISTRY)));
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = formatSocketAddress(remoteAddress);
		Timer timer = getResolveAddressTimer(name + ADDRESS_RESOLVER, address, status);
		if (timer != null) {
			timer.record(time);
		}
	}

	@Nullable
	public final Timer getResolveAddressTimer(String name, @Nullable String address, String status) {
		MeterKey meterKey = new MeterKey(null, address, null, null, status);
		return MapUtils.computeIfAbsent(addressResolverTimeCache, meterKey,
				key -> filter(Timer.builder(name)
				                   .tags(REMOTE_ADDRESS, address, STATUS, status)
				                   .register(REGISTRY)));
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

	public String name() {
		return name;
	}

	public String protocol() {
		return protocol;
	}

	@Nullable
	LongAdder getTotalConnectionsAdder(SocketAddress serverAddress) {
		String address = formatSocketAddress(serverAddress);
		return MapUtils.computeIfAbsent(totalConnectionsCache, address,
				key -> {
					LongAdder totalConnectionsAdder = new LongAdder();
					Gauge gauge = filter(Gauge.builder(name + CONNECTIONS_TOTAL, totalConnectionsAdder, LongAdder::longValue)
					                          .tags(ChannelMeters.ConnectionsTotalMeterTags.URI.asString(), protocol,
					                                ChannelMeters.ConnectionsTotalMeterTags.LOCAL_ADDRESS.asString(), address)
					                          .register(REGISTRY));
					return gauge != null ? totalConnectionsAdder : null;
				});
	}
}
