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
package reactor.netty.http.client;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import reactor.netty.Metrics;
import reactor.netty.channel.ChannelMeters;
import reactor.netty.channel.MeterKey;
import reactor.netty.http.MicrometerHttpMetricsRecorder;
import reactor.netty.internal.util.MapUtils;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.HTTP_CLIENT_PREFIX;
import static reactor.netty.Metrics.METHOD;
import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.PROXY_ADDRESS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;
import static reactor.netty.Metrics.formatSocketAddress;

/**
 * {@link HttpClientMetricsRecorder} for Reactor Netty built-in integration with Micrometer.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
final class MicrometerHttpClientMetricsRecorder extends MicrometerHttpMetricsRecorder implements HttpClientMetricsRecorder {

	static final MicrometerHttpClientMetricsRecorder INSTANCE = new MicrometerHttpClientMetricsRecorder();

	private final ConcurrentMap<MeterKey, DistributionSummary> dataReceivedCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, DistributionSummary> dataSentCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, Counter> errorsCache = new ConcurrentHashMap<>();

	private MicrometerHttpClientMetricsRecorder() {
		super(HTTP_CLIENT_PREFIX, "http", false);
	}

	@Override
	public void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		recordDataReceivedTime(remoteAddress, NA, uri, method, status, time);
	}

	@Override
	public void recordDataReceivedTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, String method, String status, Duration time) {
		recordDataReceivedTime(remoteAddress, formatSocketAddress(proxyAddress), uri, method, status, time);
	}

	void recordDataReceivedTime(SocketAddress remoteAddress, @Nullable String proxyAddress, String uri, String method, String status, Duration time) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, proxyAddress, method, status);
		Timer dataReceivedTime = MapUtils.computeIfAbsent(dataReceivedTimeCache, meterKey,
				key -> filter(Timer.builder(name() + DATA_RECEIVED_TIME)
				                   .tags(HttpClientMeters.DataReceivedTimeTags.REMOTE_ADDRESS.asString(), address,
				                         HttpClientMeters.DataReceivedTimeTags.PROXY_ADDRESS.asString(), proxyAddress,
				                         HttpClientMeters.DataReceivedTimeTags.URI.asString(), uri,
				                         HttpClientMeters.DataReceivedTimeTags.METHOD.asString(), method,
				                         HttpClientMeters.DataReceivedTimeTags.STATUS.asString(), status)
				                   .register(REGISTRY)));
		if (dataReceivedTime != null) {
			dataReceivedTime.record(time);
		}
	}

	@Override
	public void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time) {
		recordDataSentTime(remoteAddress, NA, uri, method, time);
	}

	@Override
	public void recordDataSentTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, String method, Duration time) {
		recordDataSentTime(remoteAddress, formatSocketAddress(proxyAddress), uri, method, time);
	}

	void recordDataSentTime(SocketAddress remoteAddress, @Nullable String proxyAddress, String uri, String method, Duration time) {
		String address = formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, proxyAddress, method, null);
		Timer dataSentTime = MapUtils.computeIfAbsent(dataSentTimeCache, meterKey,
				key -> filter(Timer.builder(name() + DATA_SENT_TIME)
				                   .tags(HttpClientMeters.DataSentTimeTags.REMOTE_ADDRESS.asString(), address,
				                         HttpClientMeters.DataSentTimeTags.PROXY_ADDRESS.asString(), proxyAddress,
				                         HttpClientMeters.DataSentTimeTags.URI.asString(), uri,
				                         HttpClientMeters.DataSentTimeTags.METHOD.asString(), method)
				                   .register(REGISTRY)));
		if (dataSentTime != null) {
			dataSentTime.record(time);
		}
	}

	@Override
	public void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		Timer responseTime = getResponseTimeTimer(name() + RESPONSE_TIME, formatSocketAddress(remoteAddress), NA, uri, method, status);
		if (responseTime != null) {
			responseTime.record(time);
		}
	}

	@Override
	public void recordResponseTime(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, String method, String status, Duration time) {
		Timer responseTime = getResponseTimeTimer(name() + RESPONSE_TIME, formatSocketAddress(remoteAddress), formatSocketAddress(proxyAddress), uri, method, status);
		if (responseTime != null) {
			responseTime.record(time);
		}
	}

	@Nullable
	Timer getResponseTimeTimer(String name, @Nullable String remoteAddress, @Nullable String proxyAddress, String uri, String method, String status) {
		MeterKey meterKey = new MeterKey(uri, remoteAddress, proxyAddress, method, status);
		return MapUtils.computeIfAbsent(responseTimeCache, meterKey,
				key -> filter(Timer.builder(name)
				                   .tags(REMOTE_ADDRESS, remoteAddress, PROXY_ADDRESS, proxyAddress, URI, uri, METHOD, method, STATUS, status)
				                   .register(REGISTRY)));
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		recordDataReceived(remoteAddress, NA, uri, bytes);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, SocketAddress proxyAddress, String uri, long bytes) {
		recordDataReceived(remoteAddress, formatSocketAddress(proxyAddress), uri, bytes);
	}

	void recordDataReceived(SocketAddress remoteAddress, @Nullable String proxyAddress, String uri, long bytes) {
		String address = Metrics.formatSocketAddress(remoteAddress);
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

	void recordDataSent(SocketAddress remoteAddress, @Nullable String proxyAddress, String uri, long bytes) {
		String address = Metrics.formatSocketAddress(remoteAddress);
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

	void incrementErrorsCount(SocketAddress remoteAddress, @Nullable String proxyAddress, String uri) {
		String address = Metrics.formatSocketAddress(remoteAddress);
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
