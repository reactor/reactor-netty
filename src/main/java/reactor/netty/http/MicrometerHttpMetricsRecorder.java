/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.netty.util.internal.PlatformDependent;
import reactor.netty.Metrics;
import reactor.netty.channel.MeterKey;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.MAX_URI_TAGS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.RESPONSE_TIME;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 */
public class MicrometerHttpMetricsRecorder extends MicrometerChannelMetricsRecorder implements HttpMetricsRecorder {

	protected final Timer.Builder dataReceivedTimeBuilder;
	protected final ConcurrentMap<MeterKey, Timer> dataReceivedTimeCache = PlatformDependent.newConcurrentHashMap();

	protected final Timer.Builder dataSentTimeBuilder;
	protected final ConcurrentMap<MeterKey, Timer> dataSentTimeCache = PlatformDependent.newConcurrentHashMap();

	protected final Timer.Builder responseTimeBuilder;
	protected final ConcurrentMap<MeterKey, Timer> responseTimeCache = PlatformDependent.newConcurrentHashMap();

	final DistributionSummary.Builder dataReceivedBuilder;
	final ConcurrentMap<MeterKey, DistributionSummary> dataReceivedCache = PlatformDependent.newConcurrentHashMap();

	final DistributionSummary.Builder dataSentBuilder;
	final ConcurrentMap<MeterKey, DistributionSummary> dataSentCache = PlatformDependent.newConcurrentHashMap();

	final Counter.Builder errorsBuilder;
	final ConcurrentMap<MeterKey, Counter> errorsCache = PlatformDependent.newConcurrentHashMap();

	protected MicrometerHttpMetricsRecorder(String name, String protocol) {
		super(name, protocol);
		this.dataReceivedTimeBuilder =
				Timer.builder(name + DATA_RECEIVED_TIME)
				     .description("Time that is spent in consuming incoming data");

		this.dataSentTimeBuilder =
				Timer.builder(name + DATA_SENT_TIME)
				     .description("Time that is spent in sending outgoing data");

		this.responseTimeBuilder =
				Timer.builder(name + RESPONSE_TIME)
				     .description("Total time for the request/response");

		this.dataReceivedBuilder =
				DistributionSummary.builder(name + DATA_RECEIVED)
				                   .baseUnit("bytes")
				                   .description("Amount of the data that is received, in bytes");

		this.dataSentBuilder =
				DistributionSummary.builder(name + DATA_SENT)
				                   .baseUnit("bytes")
				                   .description("Amount of the data that is sent, in bytes");

		this.errorsBuilder =
				Counter.builder(name + ERRORS)
				       .description("Number of the errors that are occurred");

		registry.config().meterFilter(maxUriTagsMeterFilter(name));
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = MeterKey.builder().uri(uri).remoteAddress(address).build();
		DistributionSummary dataReceived = dataReceivedCache.computeIfAbsent(meterKey,
				key -> dataReceivedBuilder.tags(REMOTE_ADDRESS, address, URI, uri)
				                          .register(registry));
		dataReceived.record(bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = MeterKey.builder().uri(uri).remoteAddress(address).build();
		DistributionSummary dataSent = dataSentCache.computeIfAbsent(meterKey,
				key -> dataSentBuilder.tags(REMOTE_ADDRESS, address, URI, uri)
				                      .register(registry));
		dataSent.record(bytes);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		String address = Metrics.formatSocketAddress(remoteAddress);
		MeterKey meterKey = MeterKey.builder().uri(uri).remoteAddress(address).build();
		Counter errors = errorsCache.computeIfAbsent(meterKey,
				key -> errorsBuilder.tags(REMOTE_ADDRESS, address, URI, uri)
				                    .register(registry));
		errors.increment();
	}

	static MeterFilter maxUriTagsMeterFilter(String meterNamePrefix) {
		return MeterFilter.maximumAllowableTags(meterNamePrefix, URI, MAX_URI_TAGS, new MaxUriTagsMeterFilter(meterNamePrefix));
	}

	static final class MaxUriTagsMeterFilter extends AtomicBoolean implements MeterFilter {
		final String meterNamePrefix;

		MaxUriTagsMeterFilter(String meterNamePrefix) {
			this.meterNamePrefix = meterNamePrefix;
		}

		@Override
		public MeterFilterReply accept(Meter.Id id) {
			if (logger.isWarnEnabled() && compareAndSet(false, true)) {
				logger.warn("Reached the maximum number of URI tags for meter name prefix [" + meterNamePrefix + "].");
			}
			return MeterFilterReply.DENY;
		}

		static final Logger logger = Loggers.getLogger(MaxUriTagsMeterFilter.class);
	}
}
