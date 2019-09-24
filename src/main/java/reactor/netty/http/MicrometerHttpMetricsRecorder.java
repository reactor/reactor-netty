/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
import reactor.netty.Metrics;
import reactor.netty.channel.MicrometerChannelMetricsRecorder;
import reactor.util.Logger;
import reactor.util.Loggers;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static reactor.netty.Metrics.*;

public class MicrometerHttpMetricsRecorder extends MicrometerChannelMetricsRecorder implements HttpMetricsRecorder {

	protected final Timer.Builder dataReceivedTimeBuilder;

	protected final Timer.Builder dataSentTimeBuilder;

	protected final Timer.Builder responseTimeBuilder;

	protected MicrometerHttpMetricsRecorder(String name, @Nullable String remoteAddress, String protocol) {
		super(name, remoteAddress, protocol);

		dataReceivedTimeBuilder = Timer.builder(name + DATA_RECEIVED_TIME)
		                               .description("Time that is spent in consuming incoming data");
		dataSentTimeBuilder = Timer.builder(name + DATA_SENT_TIME)
		                           .description("Time that is spent in sending outgoing data");
		responseTimeBuilder = Timer.builder(name + RESPONSE_TIME)
		                           .description("Total time for the request/response");

		registry.config()
		        .meterFilter(maxUriTagsMeterFilter(name + DATA_RECEIVED_TIME))
		        .meterFilter(maxUriTagsMeterFilter(name + DATA_SENT_TIME))
		        .meterFilter(maxUriTagsMeterFilter(name + RESPONSE_TIME))
		        .meterFilter(maxUriTagsMeterFilter(name + DATA_RECEIVED))
		        .meterFilter(maxUriTagsMeterFilter(name + DATA_SENT))
		        .meterFilter(maxUriTagsMeterFilter(name + ERRORS));
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		DistributionSummary.builder(name + DATA_RECEIVED)
		                   .baseUnit("bytes")
		                   .description("Amount of the data that is received, in bytes")
		                   .tags(REMOTE_ADDRESS, Metrics.formatSocketAddress(remoteAddress), URI, uri)
		                   .register(registry)
		                   .record(bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		DistributionSummary.builder(name + DATA_SENT)
		                   .baseUnit("bytes")
		                   .description("Amount of the data that is sent, in bytes")
		                   .tags(REMOTE_ADDRESS, Metrics.formatSocketAddress(remoteAddress), URI, uri)
		                   .register(registry)
		                   .record(bytes);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		Counter.builder(name + ERRORS)
		       .description("Number of the errors that are occurred")
		       .tags(REMOTE_ADDRESS, Metrics.formatSocketAddress(remoteAddress), URI, uri)
		       .register(registry)
		       .increment();
	}

	static MeterFilter maxUriTagsMeterFilter(String meterNamePrefix) {
		return MeterFilter.maximumAllowableTags(meterNamePrefix, URI, MAX_URI_TAGS, new MaxUriTagsMeterFilter());
	}

	static final class MaxUriTagsMeterFilter extends AtomicBoolean implements MeterFilter {
		@Override
		public MeterFilterReply accept(Meter.Id id) {
			if (logger.isWarnEnabled() && compareAndSet(false, true)) {
				logger.warn("Reached the maximum number of URI tags for {0}.", id.getName());
			}
			return MeterFilterReply.DENY;
		}

		static final Logger logger = Loggers.getLogger(MaxUriTagsMeterFilter.class);
	}
}
