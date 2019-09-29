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
package reactor.netty.channel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.time.Duration;

import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.URI;

public class MicrometerChannelMetricsRecorder implements ChannelMetricsRecorder {

	protected final static MeterRegistry registry = Metrics.globalRegistry;

	protected final String name;

	protected final String remoteAddress;

	final DistributionSummary.Builder dataReceivedBuilder;
	DistributionSummary dataReceived;

	final DistributionSummary.Builder dataSentBuilder;
	DistributionSummary dataSent;

	final Counter.Builder errorCountBuilder;
	Counter errorCount;


	protected MicrometerChannelMetricsRecorder(String name, @Nullable String remoteAddress, String protocol) {
		this.name = name;
		this.remoteAddress = remoteAddress;
		if (remoteAddress == null && !"udp".equals(protocol)) {
			throw new IllegalArgumentException("remoteAddress is null for protocol " + protocol);
		}

		this.dataReceivedBuilder =
				DistributionSummary.builder(name + DATA_RECEIVED)
				                   .baseUnit("bytes")
				                   .description("Amount of the data that is received, in bytes")
				                   .tag(URI, protocol);

		this.dataSentBuilder =
				DistributionSummary.builder(name + DATA_SENT)
				                   .baseUnit("bytes")
				                   .description("Amount of the data that is sent, in bytes")
				                   .tag(URI, protocol);

		this.errorCountBuilder =
				Counter.builder(name + ERRORS)
				       .description("Number of the errors that are occurred")
				       .tag(URI, protocol);

		if (remoteAddress != null) {
			this.dataReceivedBuilder.tag(REMOTE_ADDRESS, remoteAddress);
			this.dataReceived = dataReceivedBuilder.register(registry);

			this.dataSentBuilder.tag(REMOTE_ADDRESS, remoteAddress);
			this.dataSent = dataSentBuilder.register(registry);

			this.errorCountBuilder.tag(REMOTE_ADDRESS, remoteAddress);
			this.errorCount = errorCountBuilder.register(registry);
		}
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		if (dataReceived != null) {
			dataReceived.record(bytes);
		}
		else {
			dataReceivedBuilder.tag(REMOTE_ADDRESS, reactor.netty.Metrics.formatSocketAddress(remoteAddress))
			                   .register(registry)
			                   .record(bytes);
		}
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		if (dataSent != null) {
			dataSent.record(bytes);
		}
		else {
			dataSentBuilder.tag(REMOTE_ADDRESS, reactor.netty.Metrics.formatSocketAddress(remoteAddress))
			               .register(registry)
			               .record(bytes);
		}
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress) {
		if (errorCount != null) {
			errorCount.increment();
		}
		else {
			errorCountBuilder.tag(REMOTE_ADDRESS, reactor.netty.Metrics.formatSocketAddress(remoteAddress))
			                 .register(registry)
			                 .increment();
		}
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		Timer.builder(name + TLS_HANDSHAKE_TIME)
		     .tags(REMOTE_ADDRESS, reactor.netty.Metrics.formatSocketAddress(remoteAddress), STATUS, status)
		     .description("Time that is spent for TLS handshake")
		     .register(registry)
		     .record(time);
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		Timer.builder(name + CONNECT_TIME)
		     .tags(REMOTE_ADDRESS, reactor.netty.Metrics.formatSocketAddress(remoteAddress), STATUS, status)
		     .description("Time that is spent for connecting to the remote address")
		     .register(registry)
		     .record(time);
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		_recordResolveAddressTime(remoteAddress, time, status);
	}

	public MeterRegistry registry() {
		return registry;
	}

	public String name() {
		return name;
	}

	public String remoteAddress() {
		return remoteAddress;
	}

	static void _recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		Timer.builder("reactor.netty.address.resolver")
		     .tags(REMOTE_ADDRESS, reactor.netty.Metrics.formatSocketAddress(remoteAddress), STATUS, status)
		     .description("Time that is spent for resolving the address")
		     .register(registry)
		     .record(time);
	}
}
