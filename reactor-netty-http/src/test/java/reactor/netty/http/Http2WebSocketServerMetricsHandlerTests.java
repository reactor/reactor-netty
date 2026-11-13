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

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.util.stream.Stream;

class Http2WebSocketServerMetricsHandlerTests extends WebSocketServerMetricsHandlerTests {

	static X509Bundle ssc;
	static Http11SslContextSpec serverCtx11;
	static Http2SslContextSpec serverCtx2;
	static Http11SslContextSpec clientCtx11;
	static Http2SslContextSpec clientCtx2;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
		serverCtx11 = Http11SslContextSpec.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem());
		serverCtx2 = Http2SslContextSpec.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem());
		clientCtx11 = Http11SslContextSpec.forClient()
		                                  .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		clientCtx2 = Http2SslContextSpec.forClient()
		                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketHandshakeTimeMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketHandshakeTimeMicrometer(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketFailureBeforeHandshakeStart(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketFailureBeforeHandshakeStart(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinationsWithRecordingFailures")
	void testServerWebSocketContextAwareRecorderUpgradeNotSubscribed(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx,
			boolean failHandshakeRecording, boolean failConnectionDurationRecording) throws Exception {
		doTestServerWebSocketContextAwareRecorderUpgradeNotSubscribed(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx), failHandshakeRecording, failConnectionDurationRecording);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketUriTagValueFunctionMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketUriTagValueFunctionMicrometer(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketConnectionDurationMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketConnectionDurationMicrometer(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketDataMetricsMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketDataMetricsMicrometer(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketMultipleMessagesSentMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketMultipleMessagesSentMicrometer(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketMultipleMessagesReceivedMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketMultipleMessagesReceivedMicrometer(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketControlFramesExcludedFromDataMetricsMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketControlFramesExcludedFromDataMetricsMicrometer(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketFragmentedDataReceivedMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketFragmentedDataReceivedMicrometer(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketErrorsOnApplicationErrorMicrometer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketErrorsOnApplicationErrorMicrometer(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketCustomRecorder(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketCustomRecorder(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketHttpRecorder(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketHttpRecorder(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketContextAwareRecorder(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketContextAwareRecorder(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketContextAwareRecorderAfterHandshake(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerWebSocketContextAwareRecorderAfterHandshake(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void testServerWebSocketContextAwareRecorderAfterHandshakeOffEventLoop(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestServerWebSocketContextAwareRecorderAfterHandshakeOffEventLoop(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@Override
	String handshakeStatus() {
		return "200";
	}

	@Override
	String handshakeMethod() {
		return "CONNECT";
	}

	@SuppressWarnings("deprecation")
	HttpClient configureClient(HttpProtocol[] clientProtocols, @Nullable Http2SslContextSpec clientCtx) {
		return clientCtx == null ? httpClient.protocol(clientProtocols) :
				httpClient.protocol(clientProtocols).secure(spec -> spec.sslContext(clientCtx));
	}

	@SuppressWarnings("deprecation")
	HttpServer configureServer(HttpProtocol[] serverProtocols, @Nullable Http2SslContextSpec serverCtx) {
		HttpServer server = serverCtx == null ? httpServer : httpServer.secure(spec -> spec.sslContext(serverCtx));
		return server.protocol(serverProtocols).http2Settings(spec -> spec.connectProtocolEnabled(true));
	}

	static Stream<Arguments> http2CompatibleCombinations() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C}, new HttpProtocol[]{HttpProtocol.H2C}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C}, null, null)
		);
	}

	static Stream<Arguments> http2CompatibleCombinationsWithRecordingFailures() {
		return http2CompatibleCombinations().flatMap(combination ->
				Stream.of(new Object[]{false, false}, new Object[]{true, false}, new Object[]{true, true})
				      .map(failures -> {
				          Object[] args = combination.get();
				          return Arguments.of(args[0], args[1], args[2], args[3], failures[0], failures[1]);
				      }));
	}
}
