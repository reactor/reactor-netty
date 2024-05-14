/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;
import reactor.netty.internal.util.Metrics;
import reactor.netty.resources.ConnectionPoolMetrics;
import reactor.netty.resources.ConnectionProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * This test class verifies HTTP functionality works without the optional dependency on Micrometer.
 *
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
		assertThat(Metrics.isMicrometerAvailable()).as("isMicrometerAvailable").isFalse();
	}

	@Test
	void smokeTestNoContextPropagation() {
		assertThatExceptionOfType(ClassNotFoundException.class)
				.isThrownBy(() -> Class.forName("io.micrometer.context.ContextRegistry"));
	}

	@Test
	void clientCreatedWithMetricsDoesntLoadGaugeHttp1() {
		ConnectionProvider provider =
				ConnectionProvider.builder("foo1")
				                  .metrics(true, NoOpMeterRegistrar::new)
				                  .build();
		try {
			doTestClientCreatedWithMetricsDoesntLoadGauge(HttpServer.create(), HttpClient.create(provider));
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@Test
	void clientCreatedWithMetricsDoesntLoadGaugeHttp2() throws Exception {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		ConnectionProvider provider =
				ConnectionProvider.builder("foo2")
				                  .metrics(true, NoOpMeterRegistrar::new)
				                  .build();
		try {
			doTestClientCreatedWithMetricsDoesntLoadGauge(
					HttpServer.create().protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(serverCtx)),
					HttpClient.create(provider).protocol(HttpProtocol.H2).secure(spec -> spec.sslContext(clientCtx)));
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	private void doTestClientCreatedWithMetricsDoesntLoadGauge(HttpServer server, HttpClient client) {
		//note that this test cannot really access and assert the NoClassDefFoundException, which is forcefully logged by Netty
		//But when that error occurs, the channel cannot be initialized and the test will fail.

		disposableServer =
				server.port(0)
				      .route(r -> r.get("/foo", (in, out) -> out.sendString(Flux.just("bar"))))
				      .bindNow();

		final NoOpHttpClientMetricsRecorder metricsRecorder = new NoOpHttpClientMetricsRecorder();

		assertThatCode(() ->
				client.metrics(true, () -> metricsRecorder)
				      .port(disposableServer.port())
				      .baseUrl("/foo")
				      .get()
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block(Duration.ofSeconds(5))
		).doesNotThrowAnyException();

		//we still assert that the custom recorder did receive events, since it is not based on micrometer
		if (client.configuration().sslProvider() == null) {
			assertThat(metricsRecorder.events).containsExactly(
					"connectTime,status=SUCCESS",
					"dataSent",
					"dataReceived");
		}
		else {
			assertThat(metricsRecorder.events).contains(
					"connectTime,status=SUCCESS",
					"tlsHandshakeTime,status=SUCCESS");

		}
	}

	private static class NoOpHttpClientMetricsRecorder implements ChannelMetricsRecorder {

		private final List<String> events = new ArrayList<>();

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
