/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.netty.util.internal.PlatformDependent;
import reactor.netty.Metrics;
import reactor.netty.channel.MeterKey;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.URI;

/**
 * An {@link HttpMetricsRecorder} implementation for integration with Micrometer.
 *
 * @author Violeta Georgieva
 * @since 0.9
 */
public class MicrometerHttpMetricsRecorder extends MicrometerChannelMetricsRecorder implements HttpMetricsRecorder {

	protected final Timer.Builder dataReceivedTimeBuilder;
	protected final ConcurrentMap<MeterKey, Timer> dataReceivedTimeCache = PlatformDependent.newConcurrentHashMap();

	protected final Timer.Builder dataSentTimeBuilder;
	protected final ConcurrentMap<MeterKey, Timer> dataSentTimeCache = PlatformDependent.newConcurrentHashMap();

	protected final Timer.Builder responseTimeBuilder;
	protected final ConcurrentMap<MeterKey, Timer> responseTimeCache = PlatformDependent.newConcurrentHashMap();

	protected final DistributionSummary.Builder dataReceivedBuilder;
	protected final ConcurrentMap<MeterKey, DistributionSummary> dataReceivedCache = PlatformDependent.newConcurrentHashMap();

	protected final DistributionSummary.Builder dataSentBuilder;
	protected final ConcurrentMap<MeterKey, DistributionSummary> dataSentCache = PlatformDependent.newConcurrentHashMap();

	protected final Counter.Builder errorsBuilder;
	protected final ConcurrentMap<MeterKey, Counter> errorsCache = PlatformDependent.newConcurrentHashMap();

	protected MicrometerHttpMetricsRecorder(String name, String protocol) {
		super(name, protocol);
		this.dataReceivedTimeBuilder =
				Timer.builder(name + DATA_RECEIVED_TIME)
				     .description("Time spent in consuming incoming data");

		this.dataSentTimeBuilder =
				Timer.builder(name + DATA_SENT_TIME)
				     .description("Time spent in sending outgoing data");

		this.responseTimeBuilder =
				Timer.builder(name + RESPONSE_TIME)
				     .description("Total time for the request/response");

		this.dataReceivedBuilder =
				DistributionSummary.builder(name + DATA_RECEIVED)
				                   .baseUnit("bytes")
				                   .description("Amount of the data received, in bytes");

		this.dataSentBuilder =
				DistributionSummary.builder(name + DATA_SENT)
				                   .baseUnit("bytes")
				                   .description("Amount of the data sent, in bytes");

		this.errorsBuilder =
				Counter.builder(name + ERRORS)
				       .description("Number of errors that occurred");
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		DistributionSummary dataReceived = dataReceivedCache.computeIfAbsent(new MeterKey(uri, address, null, null),
				key -> filter(dataReceivedBuilder.tags(REMOTE_ADDRESS, address, URI, uri)
				                                 .register(REGISTRY)));
		if (dataReceived != null) {
			dataReceived.record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		DistributionSummary dataSent = dataSentCache.computeIfAbsent(new MeterKey(uri, address, null, null),
				key -> filter(dataSentBuilder.tags(REMOTE_ADDRESS, address, URI, uri)
				                             .register(REGISTRY)));
		if (dataSent != null) {
			dataSent.record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		Counter errors = errorsCache.computeIfAbsent(new MeterKey(uri, address, null, null),
				key -> filter(errorsBuilder.tags(REMOTE_ADDRESS, address, URI, uri)
				                           .register(REGISTRY)));
		if (errors != null) {
			errors.increment();
		}
	}
}
