/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import reactor.netty.Metrics;
import reactor.netty.channel.ChannelMeters;
import reactor.netty.channel.MeterKey;
import reactor.netty.http.MicrometerHttpMetricsRecorder;
import reactor.netty.internal.util.MapUtils;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.CONNECTION_DURATION;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.HANDSHAKE_TIME;
import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.PROXY_ADDRESS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;
import static reactor.netty.Metrics.WEBSOCKET_CLIENT_PREFIX;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * {@link WebSocketClientMetricsRecorder} for Reactor Netty built-in integration with Micrometer.
 *
 * @author raccoonback
 * @since 1.3.2
 */
final class MicrometerWebSocketClientMetricsRecorder extends MicrometerHttpMetricsRecorder implements WebSocketClientMetricsRecorder {

	static final MicrometerWebSocketClientMetricsRecorder INSTANCE = new MicrometerWebSocketClientMetricsRecorder();

	private final ConcurrentMap<MeterKey, Timer> handshakeTimeCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, DistributionSummary> dataReceivedCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, DistributionSummary> dataSentCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, Timer> connectionDurationCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, Counter> errorsCache = new ConcurrentHashMap<>();

	private MicrometerWebSocketClientMetricsRecorder() {
		super(WEBSOCKET_CLIENT_PREFIX, "websocket", false);
	}

	@Override
	public void recordWebSocketHandshakeTime(SocketAddress remoteAddress, String uri, String status, Duration time) {
		recordWebSocketHandshakeTime(remoteAddress, NA, uri, status, time);
	}

	@Override
	public void recordWebSocketHandshakeTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri,
			String status, Duration time) {
		recordWebSocketHandshakeTime(remoteAddress, formatSocketAddress(proxyAddress), uri, status, time);
	}

	void recordWebSocketHandshakeTime(SocketAddress remoteAddress, String proxyAddress, String uri, String status, Duration time) {
		String address = formatSocketAddress(remoteAddress);
		Timer handshakeTime = getHandshakeTimeTimer(name() + HANDSHAKE_TIME, address, proxyAddress, uri, status);
		if (handshakeTime != null) {
			handshakeTime.record(time);
		}
	}

	@Nullable Timer getHandshakeTimeTimer(String name, String remoteAddress, String proxyAddress, String uri, String status) {
		MeterKey meterKey = new MeterKey(uri, remoteAddress, proxyAddress, null, status);
		return MapUtils.computeIfAbsent(handshakeTimeCache, meterKey,
				key -> filter(Timer.builder(name)
				                   .tags(WebSocketClientMeters.HandshakeTimeTags.REMOTE_ADDRESS.asString(), remoteAddress,
				                         WebSocketClientMeters.HandshakeTimeTags.PROXY_ADDRESS.asString(), proxyAddress,
				                         WebSocketClientMeters.HandshakeTimeTags.URI.asString(), uri,
				                         WebSocketClientMeters.HandshakeTimeTags.STATUS.asString(), status)
				                   .register(REGISTRY)));
	}

	@Override
	public void recordWebSocketConnectionDuration(SocketAddress remoteAddress, String uri, Duration time) {
		recordWebSocketConnectionDuration(remoteAddress, NA, uri, time);
	}

	@Override
	public void recordWebSocketConnectionDuration(SocketAddress remoteAddress, SocketAddress proxyAddress,
			String uri, Duration time) {
		recordWebSocketConnectionDuration(remoteAddress, formatSocketAddress(proxyAddress), uri, time);
	}

	void recordWebSocketConnectionDuration(SocketAddress remoteAddress, String proxyAddress, String uri, Duration time) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, proxyAddress, null, null);
		Timer connectionDuration = MapUtils.computeIfAbsent(connectionDurationCache, meterKey,
				key -> filter(Timer.builder(name() + CONNECTION_DURATION)
				                   .tags(WebSocketClientMeters.ConnectionDurationTags.REMOTE_ADDRESS.asString(), address,
				                         WebSocketClientMeters.ConnectionDurationTags.PROXY_ADDRESS.asString(), proxyAddress,
				                         WebSocketClientMeters.ConnectionDurationTags.URI.asString(), uri)
				                   .register(REGISTRY)));
		if (connectionDuration != null) {
			connectionDuration.record(time);
		}
	}

	@Override
	public void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		recordDataReceivedTime(remoteAddress, NA, uri, time);
	}

	@Override
	public void recordDataReceivedTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, String method, String status, Duration time) {
		recordDataReceivedTime(remoteAddress, formatSocketAddress(proxyAddress), uri, time);
	}

	void recordDataReceivedTime(SocketAddress remoteAddress, String proxyAddress, String uri, Duration time) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, proxyAddress, null, null);
		Timer dataReceivedTime = MapUtils.computeIfAbsent(dataReceivedTimeCache, meterKey,
				key -> filter(Timer.builder(name() + DATA_RECEIVED_TIME)
				                   .tags(WebSocketClientMeters.DataReceivedTimeTags.REMOTE_ADDRESS.asString(), address,
				                         WebSocketClientMeters.DataReceivedTimeTags.PROXY_ADDRESS.asString(), proxyAddress,
				                         WebSocketClientMeters.DataReceivedTimeTags.URI.asString(), uri)
				                   .register(REGISTRY)));
		if (dataReceivedTime != null) {
			dataReceivedTime.record(time);
		}
	}

	@Override
	public void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time) {
		doRecordDataSentTime(remoteAddress, NA, uri, time);
	}

	@Override
	public void recordDataSentTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, String method, Duration time) {
		doRecordDataSentTime(remoteAddress, formatSocketAddress(proxyAddress), uri, time);
	}

	void doRecordDataSentTime(SocketAddress remoteAddress, String proxyAddress, String uri, Duration time) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, proxyAddress, null, null);
		Timer dataSentTime = MapUtils.computeIfAbsent(dataSentTimeCache, meterKey,
				key -> filter(Timer.builder(name() + DATA_SENT_TIME)
				                   .tags(WebSocketClientMeters.DataSentTimeTags.REMOTE_ADDRESS.asString(), address,
				                         WebSocketClientMeters.DataSentTimeTags.PROXY_ADDRESS.asString(), proxyAddress,
				                         WebSocketClientMeters.DataSentTimeTags.URI.asString(), uri)
				                   .register(REGISTRY)));
		if (dataSentTime != null) {
			dataSentTime.record(time);
		}
	}

	@Override
	public void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
	}

	@Override
	public void recordResponseTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, String method, String status, Duration time) {
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		recordDataReceived(remoteAddress, NA, uri, bytes);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, long bytes) {
		recordDataReceived(remoteAddress, formatSocketAddress(proxyAddress), uri, bytes);
	}

	void recordDataReceived(SocketAddress remoteAddress, String proxyAddress, String uri, long bytes) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, proxyAddress, null, null);
		DistributionSummary dataReceived = MapUtils.computeIfAbsent(dataReceivedCache, meterKey,
				key -> filter(DistributionSummary.builder(name() + DATA_RECEIVED)
				                                 .baseUnit(ChannelMeters.DATA_RECEIVED.getBaseUnit())
				                                 .tags(ChannelMeters.ChannelMetersTags.REMOTE_ADDRESS.asString(), address,
				                                       ChannelMeters.ChannelMetersTags.PROXY_ADDRESS.asString(), proxyAddress,
				                                       ChannelMeters.ChannelMetersTags.URI.asString(), uri)
				                                 .register(REGISTRY)));
		if (dataReceived != null) {
			dataReceived.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		recordDataSent(remoteAddress, NA, uri, bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, long bytes) {
		recordDataSent(remoteAddress, formatSocketAddress(proxyAddress), uri, bytes);
	}

	void recordDataSent(SocketAddress remoteAddress, String proxyAddress, String uri, long bytes) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, proxyAddress, null, null);
		DistributionSummary dataSent = MapUtils.computeIfAbsent(dataSentCache, meterKey,
				key -> filter(DistributionSummary.builder(name() + DATA_SENT)
				                                 .baseUnit(ChannelMeters.DATA_SENT.getBaseUnit())
				                                 .tags(ChannelMeters.ChannelMetersTags.REMOTE_ADDRESS.asString(), address,
				                                       ChannelMeters.ChannelMetersTags.PROXY_ADDRESS.asString(), proxyAddress,
				                                       ChannelMeters.ChannelMetersTags.URI.asString(), uri)
				                                 .register(REGISTRY)));
		if (dataSent != null) {
			dataSent.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		incrementErrorsCount(remoteAddress, NA, uri);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri) {
		incrementErrorsCount(remoteAddress, formatSocketAddress(proxyAddress), uri);
	}

	void incrementErrorsCount(SocketAddress remoteAddress, String proxyAddress, String uri) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, proxyAddress, null, null);
		Counter errors = MapUtils.computeIfAbsent(errorsCache, meterKey,
				key -> filter(Counter.builder(name() + ERRORS)
				                     .tags(ChannelMeters.ChannelMetersTags.REMOTE_ADDRESS.asString(), address,
				                           ChannelMeters.ChannelMetersTags.PROXY_ADDRESS.asString(), proxyAddress,
				                           ChannelMeters.ChannelMetersTags.URI.asString(), uri)
				                     .register(REGISTRY)));
		if (errors != null) {
			errors.increment();
		}
	}
}
