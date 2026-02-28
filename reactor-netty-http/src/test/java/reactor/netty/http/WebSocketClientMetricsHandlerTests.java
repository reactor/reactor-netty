/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider.ProtocolSslContextSpec;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;

import static reactor.netty.Metrics.CONNECTION_DURATION;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.HANDSHAKE_TIME;
import static reactor.netty.Metrics.NA;
import static reactor.netty.Metrics.PROXY_ADDRESS;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;
import static reactor.netty.Metrics.WEBSOCKET_CLIENT_PREFIX;
import static reactor.netty.Metrics.formatSocketAddress;
import static reactor.netty.micrometer.DistributionSummaryAssert.assertDistributionSummary;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

/**
 * WebSocket client metrics tests.
 *
 * @author LivingLikeKrillin
 * @since 1.3.5
 */
class WebSocketClientMetricsHandlerTests extends BaseHttpTest {

	static final String WS_HANDSHAKE_TIME = WEBSOCKET_CLIENT_PREFIX + HANDSHAKE_TIME;
	static final String WS_DATA_RECEIVED_TIME = WEBSOCKET_CLIENT_PREFIX + DATA_RECEIVED_TIME;
	static final String WS_DATA_SENT_TIME = WEBSOCKET_CLIENT_PREFIX + DATA_SENT_TIME;
	static final String WS_DATA_RECEIVED = WEBSOCKET_CLIENT_PREFIX + DATA_RECEIVED;
	static final String WS_DATA_SENT = WEBSOCKET_CLIENT_PREFIX + DATA_SENT;
	static final String WS_CONNECTION_DURATION = WEBSOCKET_CLIENT_PREFIX + CONNECTION_DURATION;

	HttpServer httpServer;
	HttpClient httpClient;
	private ConnectionProvider provider;
	private MeterRegistry registry;

	static X509Bundle ssc;
	static Http2SslContextSpec serverCtx2;
	static Http2SslContextSpec clientCtx2;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
		serverCtx2 = Http2SslContextSpec.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem())
		                                .configure(builder -> builder.sslProvider(SslProvider.JDK));
		clientCtx2 = Http2SslContextSpec.forClient()
		                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                                             .sslProvider(SslProvider.JDK));
	}

	@BeforeEach
	void setUp() {
		httpServer = createServer()
				.host("127.0.0.1")
				.route(r -> r
						.get("/ws", (req, res) -> res.sendWebsocket((in, out) ->
								out.sendString(Mono.just("Hello World!"))))
						.get("/ws-echo", (req, res) -> res.sendWebsocket((in, out) ->
								out.send(in.receive().retain())))
				);

		provider = ConnectionProvider.create("WebSocketClientMetricsHandlerTests", 1);
		httpClient = createClient(provider, () -> disposableServer.address())
				.metrics(true, Function.identity());

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() throws InterruptedException, ExecutionException, TimeoutException {
		if (!provider.isDisposed()) {
			provider.disposeLater()
					.block(Duration.ofSeconds(30));
		}

		Metrics.removeRegistry(registry);
		registry.clear();
		registry.close();

		if (disposableServer != null) {
			disposableServer.disposeNow();
			disposableServer = null;
		}
	}

	@Test
	void testWebSocketHandshakeMetrics() {
		disposableServer = httpServer.bindNow();
		String serverAddress = formatSocketAddress(disposableServer.address());

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		// Verify handshake time timer
		assertTimer(registry, WS_HANDSHAKE_TIME,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/ws",
				STATUS, "101")
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
	}

	@Test
	void testWebSocketDataReceivedMetrics() {
		disposableServer = httpServer.bindNow();
		String serverAddress = formatSocketAddress(disposableServer.address());

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		// Verify data received time timer
		assertTimer(registry, WS_DATA_RECEIVED_TIME,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/ws")
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);

		// Verify data received distribution summary
		assertDistributionSummary(registry, WS_DATA_RECEIVED,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/ws")
				.hasCountGreaterThanOrEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(1);
	}

	@Test
	void testWebSocketDataSentMetrics() {
		disposableServer = httpServer.bindNow();
		String serverAddress = formatSocketAddress(disposableServer.address());

		httpClient.websocket()
		          .uri("/ws-echo")
		          .handle((in, out) ->
				          out.sendString(Mono.just("test message"))
				             .then()
				             .thenMany(in.receive().asString().take(1)))
		          .as(StepVerifier::create)
		          .expectNext("test message")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		// Verify data sent time timer
		assertTimer(registry, WS_DATA_SENT_TIME,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/ws-echo")
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);

		// Verify data sent distribution summary
		assertDistributionSummary(registry, WS_DATA_SENT,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/ws-echo")
				.hasCountGreaterThanOrEqualTo(1)
				.hasTotalAmountGreaterThanOrEqualTo(1);
	}

	@Test
	void testWebSocketMultipleConnections() {
		disposableServer = httpServer.bindNow();
		String serverAddress = formatSocketAddress(disposableServer.address());

		// Connection pool size 1 means sequential connections
		for (int i = 0; i < 3; i++) {
			httpClient.websocket()
			          .uri("/ws")
			          .handle((in, out) -> in.receive().aggregate().asString())
			          .as(StepVerifier::create)
			          .expectNext("Hello World!")
			          .expectComplete()
			          .verify(Duration.ofSeconds(30));
		}

		// Verify handshake time timer count
		assertTimer(registry, WS_HANDSHAKE_TIME,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/ws",
				STATUS, "101")
				.hasCountEqualTo(3)
				.hasTotalTimeGreaterThan(0);
	}

	@Test
	void testWebSocketUriTagValueFunction() {
		disposableServer = httpServer.bindNow();
		String serverAddress = formatSocketAddress(disposableServer.address());

		HttpClient client = createClient(provider, () -> disposableServer.address())
				.metrics(true, s -> "/normalized");

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.receive().aggregate().asString())
		      .as(StepVerifier::create)
		      .expectNext("Hello World!")
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		// Verify URI tag uses the normalized value
		assertTimer(registry, WS_HANDSHAKE_TIME,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/normalized",
				STATUS, "101")
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
	}

	@Test
	void testWebSocketConnectionDuration() {
		disposableServer = httpServer.bindNow();
		String serverAddress = formatSocketAddress(disposableServer.address());

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() ->
				       assertTimer(registry, WS_CONNECTION_DURATION,
						       REMOTE_ADDRESS, serverAddress,
						       PROXY_ADDRESS, NA,
						       URI, "/ws")
						       .hasCountEqualTo(1)
						       .hasTotalTimeGreaterThan(0));
	}

	@Test
	void testWebSocketHandshakeFailure() {
		disposableServer = createServer()
				.host("127.0.0.1")
				.route(r -> r.get("/not-ws", (req, res) -> res.sendString(Mono.just("not a websocket"))))
				.bindNow();
		String serverAddress = formatSocketAddress(disposableServer.address());

		httpClient.websocket()
		          .uri("/not-ws")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectError()
		          .verify(Duration.ofSeconds(30));

		assertTimer(registry, WS_HANDSHAKE_TIME,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/not-ws")
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
	}

	@ParameterizedTest
	@MethodSource("websocketProtocols")
	@SuppressWarnings("deprecation")
	void testWebSocketMetricsAcrossProtocols(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable ProtocolSslContextSpec serverCtx, @Nullable ProtocolSslContextSpec clientCtx) {
		HttpServer server = createServer()
				.host("127.0.0.1")
				.handle((req, res) -> res.sendWebsocket((in, out) ->
						out.sendString(Mono.just("Hello World!"))));
		if (serverCtx != null) {
			server = server.secure(spec -> spec.sslContext(serverCtx));
		}
		server = server.protocol(serverProtocols)
		               .http2Settings(spec -> spec.connectProtocolEnabled(true));
		disposableServer = server.bindNow();
		String serverAddress = formatSocketAddress(disposableServer.address());

		HttpClient client = httpClient;
		if (clientCtx != null) {
			client = client.secure(spec -> spec.sslContext(clientCtx));
		}
		client = client.protocol(clientProtocols);

		client.websocket()
		      .uri("/")
		      .handle((in, out) -> in.receive().aggregate().asString())
		      .as(StepVerifier::create)
		      .expectNext("Hello World!")
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		// Verify handshake time timer exists and is correct
		assertTimer(registry, WS_HANDSHAKE_TIME,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/")
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);

		// Verify data received metrics exist
		assertDistributionSummary(registry, WS_DATA_RECEIVED,
				REMOTE_ADDRESS, serverAddress,
				PROXY_ADDRESS, NA,
				URI, "/")
				.hasCountGreaterThanOrEqualTo(1);
	}

	static Stream<Arguments> websocketProtocols() {
		return Stream.of(
				Arguments.of(
						Named.of("HTTP/1.1", new HttpProtocol[]{HttpProtocol.HTTP11}),
						Named.of("HTTP/1.1", new HttpProtocol[]{HttpProtocol.HTTP11}),
						Named.of("null", null), Named.of("null", null)),
				Arguments.of(
						Named.of("H2", new HttpProtocol[]{HttpProtocol.H2}),
						Named.of("H2", new HttpProtocol[]{HttpProtocol.H2}),
						Named.of("serverCtx2", serverCtx2), Named.of("clientCtx2", clientCtx2))
		);
	}
}
