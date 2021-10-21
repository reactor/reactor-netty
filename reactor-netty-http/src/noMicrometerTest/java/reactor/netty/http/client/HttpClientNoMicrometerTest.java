/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionPoolMetrics;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.Metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * @author Simon Baslé
 */
class HttpClientNoMicrometerTest {

	private DisposableServer disposableServer;

	@AfterEach
	void cleanup() {
		if (disposableServer != null) {
			disposableServer.dispose();
		}
	}

	@Test
	void smokeTestNoMicrometer() {
		assertThat(Metrics.isInstrumentationAvailable()).as("isInstrumentationAvailable").isFalse();
	}

	@Test
	void clientCreatedWithMetricsDoesntLoadGauge() {
		//note that this test cannot really access and assert the NoClassDefFoundException, which is forcefully logged by Netty
		//But when that error occurs, the channel cannot be initialized and the test will fail.

		disposableServer =  HttpServer.create()
				.port(0)
				.route(r -> r.get("/foo", (in, out) -> out.sendString(Flux.just("bar"))))
				.bindNow();

		final NoOpHttpClientMetricsRecorder metricsRecorder = new NoOpHttpClientMetricsRecorder();

		assertThatCode(() -> HttpClient
				.create(ConnectionProvider.builder("foo")
						.metrics(true, NoOpMeterRegistrar::new)
						.build())
				.metrics(true, () -> metricsRecorder)
				.port(disposableServer.port())
				.baseUrl("/foo")
				.get()
				.responseContent()
				.aggregate()
				.asString()
				.block()
		).doesNotThrowAnyException();

		//we still assert that the custom recorder did receive events, since it is not based on micrometer
		assertThat(metricsRecorder.events).containsExactly(
				"connectTime,status=SUCCESS",
				"dataSent",
				"dataReceived");
	}

	private static class NoOpHttpClientMetricsRecorder implements ChannelMetricsRecorder {

		private List<String> events = new ArrayList<>();

		@Override
		public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
			events.add("dataReceived");
		}

		@Override
		public void recordDataSent(SocketAddress remoteAddress, long bytes) {
			events.add("dataSent");
		}

		@Override
		public void incrementErrorsCount(SocketAddress remoteAddress) {
			events.add("incrementErrorsCount");
		}

		@Override
		public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
			events.add("tlsHandshakeTime,status=" + status);
		}

		@Override
		public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
			events.add("connectTime,status=" + status);
		}

		@Override
		public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
			events.add("resolveAddressTime,status=" + status);
		}
	}

	private static class NoOpMeterRegistrar implements ConnectionProvider.MeterRegistrar {

		@Override
		public void registerMetrics(String poolName, String id, SocketAddress remoteAddress,
		                            ConnectionPoolMetrics metrics) {

		}
	}
}
