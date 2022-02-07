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
package reactor.netty.http;

import io.micrometer.api.instrument.Counter;
import io.micrometer.api.instrument.DistributionSummary;
import io.micrometer.api.instrument.Timer;
import reactor.netty.Metrics;
import reactor.netty.channel.ChannelMeters;
import reactor.netty.channel.MeterKey;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.netty.internal.util.MapUtils;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.ERRORS;
//import static reactor.netty.Metrics.REGISTRY;

/**
 * An {@link HttpMetricsRecorder} implementation for integration with Micrometer.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
public class MicrometerHttpMetricsRecorder extends MicrometerChannelMetricsRecorder implements HttpMetricsRecorder {
	static final io.micrometer.api.instrument.MeterRegistry REGISTRY = io.micrometer.api.instrument.Metrics.globalRegistry;

	protected static final String RESPONSE_TIME_DESCRIPTION = "Total time for the request/response";

	protected final ConcurrentMap<MeterKey, Timer> dataReceivedTimeCache = new ConcurrentHashMap<>();

	protected final ConcurrentMap<MeterKey, Timer> dataSentTimeCache = new ConcurrentHashMap<>();

	protected final ConcurrentMap<MeterKey, Timer> responseTimeCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, DistributionSummary> dataReceivedCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, DistributionSummary> dataSentCache = new ConcurrentHashMap<>();

	private final ConcurrentMap<MeterKey, Counter> errorsCache = new ConcurrentHashMap<>();

	protected MicrometerHttpMetricsRecorder(String name, String protocol) {
		super(name, protocol);
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, null, null);
		DistributionSummary dataReceived = MapUtils.computeIfAbsent(dataReceivedCache, meterKey,
				key -> filter(ChannelMeters.toSummaryBuilder(name() + DATA_RECEIVED, ChannelMeters.DATA_RECEIVED)
				                           .tags(ChannelMeters.DataReceivedTags.REMOTE_ADDRESS.getKey(), address,
				                                 ChannelMeters.DataReceivedTags.URI.getKey(), uri)
				                           .register(REGISTRY)));
		if (dataReceived != null) {
			dataReceived.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, null, null);
		DistributionSummary dataSent = MapUtils.computeIfAbsent(dataSentCache, meterKey,
				key -> filter(ChannelMeters.toSummaryBuilder(name() + DATA_SENT, ChannelMeters.DATA_SENT)
				                           .tags(ChannelMeters.DataSentTags.REMOTE_ADDRESS.getKey(), address,
				                                 ChannelMeters.DataSentTags.URI.getKey(), uri)
				                           .register(REGISTRY)));
		if (dataSent != null) {
			dataSent.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = new MeterKey(uri, address, null, null);
		Counter errors = MapUtils.computeIfAbsent(errorsCache, meterKey,
				key -> filter(ChannelMeters.toCounterBuilder(name() + ERRORS)
				                           .tags(ChannelMeters.ErrorsCountTags.REMOTE_ADDRESS.getKey(), address,
				                                 ChannelMeters.ErrorsCountTags.URI.getKey(), uri)
				                           .register(REGISTRY)));
		if (errors != null) {
			errors.increment();
		}
	}
}
