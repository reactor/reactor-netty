/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.micrometer.core.instrument.Timer;
import io.netty.util.internal.PlatformDependent;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ConcurrentMap;

import static reactor.netty.Metrics.ADDRESS_RESOLVER;
import static reactor.netty.Metrics.CONNECT_TIME;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.REGISTRY;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.TLS_HANDSHAKE_TIME;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 * @since 0.9
 */
public class MicrometerChannelMetricsRecorder implements ChannelMetricsRecorder {

	final DistributionSummary.Builder dataReceivedBuilder;
	final ConcurrentMap<String, DistributionSummary> dataReceivedCache = PlatformDependent.newConcurrentHashMap();

	final DistributionSummary.Builder dataSentBuilder;
	final ConcurrentMap<String, DistributionSummary> dataSentCache = PlatformDependent.newConcurrentHashMap();

	final Counter.Builder errorCountBuilder;
	final ConcurrentMap<String, Counter> errorsCache = PlatformDependent.newConcurrentHashMap();

	final Timer.Builder connectTimeBuilder;
	final ConcurrentMap<MeterKey, Timer> connectTimeCache = PlatformDependent.newConcurrentHashMap();

	final Timer.Builder tlsHandshakeTimeBuilder;
	final ConcurrentMap<MeterKey, Timer> tlsHandshakeTimeCache = PlatformDependent.newConcurrentHashMap();

	final Timer.Builder addressResolverTimeBuilder;
	final ConcurrentMap<MeterKey, Timer> addressResolverTimeCache = PlatformDependent.newConcurrentHashMap();


	public MicrometerChannelMetricsRecorder(String name, String protocol) {
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

		this.connectTimeBuilder =
				Timer.builder(name + CONNECT_TIME)
				     .description("Time that is spent for connecting to the remote address");

		this.tlsHandshakeTimeBuilder =
				Timer.builder(name + TLS_HANDSHAKE_TIME)
				     .description("Time that is spent for TLS handshake");

		this.addressResolverTimeBuilder =
				Timer.builder(name + ADDRESS_RESOLVER)
				     .description("Time that is spent for resolving the address");
	}

	@Override
	public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		DistributionSummary ds = dataReceivedCache.computeIfAbsent(address,
				key -> dataReceivedBuilder.tag(REMOTE_ADDRESS, address)
				                          .register(REGISTRY));
		ds.record(bytes);
	}

	@Override
	public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		DistributionSummary ds = dataSentCache.computeIfAbsent(address,
				key -> dataSentBuilder.tag(REMOTE_ADDRESS, address)
				                      .register(REGISTRY));
		ds.record(bytes);
	}

	@Override
	public void incrementErrorsCount(SocketAddress remoteAddress) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		Counter c = errorsCache.computeIfAbsent(address,
				key -> errorCountBuilder.tag(REMOTE_ADDRESS, address)
				                        .register(REGISTRY));
		c.increment();
	}

	@Override
	public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		Timer timer = tlsHandshakeTimeCache.computeIfAbsent(new MeterKey(null, address, null, status),
				key -> tlsHandshakeTimeBuilder.tags(REMOTE_ADDRESS, address, STATUS, status)
				                              .register(REGISTRY));
		timer.record(time);
	}

	@Override
	public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		Timer timer = connectTimeCache.computeIfAbsent(new MeterKey(null, address, null, status),
				key -> connectTimeBuilder.tags(REMOTE_ADDRESS, address, STATUS, status)
				                         .register(REGISTRY));
		timer.record(time);
	}

	@Override
	public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		String address = reactor.netty.Metrics.formatSocketAddress(remoteAddress);
		Timer timer = addressResolverTimeCache.computeIfAbsent(new MeterKey(null, address, null, status),
				key -> addressResolverTimeBuilder.tags(REMOTE_ADDRESS, address, STATUS, status)
				                                 .register(REGISTRY));
		timer.record(time);
	}
}
