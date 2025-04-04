/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.client;

import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import io.netty5.handler.codec.http.websocketx.WebSocketFrame;
import io.netty5.handler.codec.http.websocketx.WebSocketVersion;
import io.netty5.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty5.handler.codec.http2.Http2HeadersFrame;
import io.netty5.handler.codec.http2.headers.Http2Headers;
import io.netty5.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty5.http.Http2SslContextSpec;
import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.server.HttpServer;
import reactor.netty5.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Http2WebsocketTest extends WebsocketTest {

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2SimpleTest(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doSimpleTest(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2ServerFailed(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doServerWebSocketFailed(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2Unidirectional(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doUnidirectional(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2ServerRespondsToRequestsFromClients(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doWebSocketRespondsToRequestsFromClients(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2UnidirectionalBinary(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doUnidirectionalBinary(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2DuplexEcho(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doDuplexEcho(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2SimpleSubProtocolServerNoSubProtocol(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doSimpleSubProtocolServerNoSubProtocol(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx),
				"Invalid subprotocol. Actual [null]. Expected one of [SUBPROTOCOL,OTHER]");
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2SimpleSubProtocolServerNotSupported(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doSimpleSubProtocolServerNotSupported(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx),
				"Invalid subprotocol. Actual [null]. Expected one of [SUBPROTOCOL,OTHER]");
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2SimpleSubProtocolServerSupported(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doSimpleSubProtocolServerSupported(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2simpleSubProtocolSelected(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doSimpleSubProtocolSelected(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2NoSubProtocolSelected(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doNoSubProtocolSelected(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2AnySubProtocolSelectsFirstClientProvided(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doAnySubProtocolSelectsFirstClientProvided(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2SendToWebsocketSubProtocol(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws InterruptedException {
		doSendToWebsocketSubProtocol(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestMaxFramePayloadLengthFailed(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestMaxFramePayloadLengthFailed(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestMaxFramePayloadLengthSuccess(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestMaxFramePayloadLengthSuccess(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestServerMaxFramePayloadLengthFailed(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerMaxFramePayloadLength(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx), 10,
				Flux.just("1", "2", "12345678901", "3"), Flux.just("1", "2"), 2);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestServerMaxFramePayloadLengthSuccess(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestServerMaxFramePayloadLength(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx), 11,
				Flux.just("1", "2", "12345678901", "3"), Flux.just("1", "2", "12345678901", "3"), 4);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2ClosePool(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doClosePool(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestCloseWebSocketFrameSentByServer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestCloseWebSocketFrameSentByServer(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestCloseWebSocketFrameSentByClient(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestCloseWebSocketFrameSentByClient(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestStreamAliveWhenTransformationErrors_1(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestConnectionAliveWhenTransformationErrors(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx),
				(in, out) -> out.sendObject(in.aggregateFrames()
				                .receiveFrames()
				                .map(WebSocketFrame::binaryData)
				                //.share()
				                .publish()
				                .autoConnect()
				                .map(byteBuf -> byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
				                .map(Integer::parseInt)
				                .map(i -> new TextWebSocketFrame(out.alloc(), i + ""))
				                .retry()),
				Flux.just("1", "2"), 2);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestStreamAliveWhenTransformationErrors_2(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestConnectionAliveWhenTransformationErrors(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx),
				(in, out) -> out.sendObject(in.aggregateFrames()
				                .receiveFrames()
				                .map(WebSocketFrame::binaryData)
				                .concatMap(content ->
				                    Mono.just(content)
				                        .map(byteBuf -> byteBuf.readCharSequence(byteBuf.readableBytes(), Charset.defaultCharset()).toString())
				                        .map(Integer::parseInt)
				                        .map(i -> new TextWebSocketFrame(out.alloc(), i + ""))
				                        .onErrorResume(t -> Mono.just(new TextWebSocketFrame(out.alloc(), "error"))))),
				Flux.just("1", "error", "2"), 3);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestClientOnCloseIsInvokedClientSendClose(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestClientOnCloseIsInvokedClientSendClose(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestClientOnCloseIsInvokedClientDisposed(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestClientOnCloseIsInvokedClientDisposed(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestClientOnCloseIsInvokedServerInitiatedClose(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestClientOnCloseIsInvokedServerInitiatedClose(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue460(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestIssue460(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue444_1(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestIssue444(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx),
				(in, out) -> out.sendObject(Flux.error(new Throwable())
				                                .onErrorResume(ex -> out.sendClose(1001, "Going Away"))
				                                .cast(WebSocketFrame.class)));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue444_2(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestIssue444(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx),
				(in, out) -> out.send(Flux.range(0, 10)
				                          .map(i -> {
				                              if (i == 5) {
				                                  out.sendClose(1001, "Going Away").subscribe();
				                              }
				                              return out.alloc().copyOf((i + "").getBytes(Charset.defaultCharset()));
				                          })));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue444_3(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestIssue444(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx),
				(in, out) -> out.sendObject(Flux.error(new Throwable())
				                                .onErrorResume(ex -> Flux.empty())
				                                .cast(WebSocketFrame.class))
				                .then(Mono.defer(() -> out.sendObject(new CloseWebSocketFrame(out.alloc(), 1001, "Going Away")).then())));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	@Disabled
	void websocketOverH2TestIssue821(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue821(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue900_1(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue900_1(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue900_2(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue900_2(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue663_1(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue663_1(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue663_2(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue663_2(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue663_3(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue663_3(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue663_4(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue663_4(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue967(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue967(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue970_WithCompress(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestWebsocketCompression(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx), true);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue970_NoCompress(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestWebsocketCompression(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx), false);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue2973(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) {
		doTestWebsocketCompression(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx), true, true);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue1485_CloseFrameSentByClient(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue1485_CloseFrameSentByClient(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue1485_CloseFrameSentByServer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue1485_CloseFrameSentByServer(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestConnectionClosedWhenFailedUpgrade_NoErrorHandling(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestConnectionClosedWhenFailedUpgrade(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx), null);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestConnectionClosedWhenFailedUpgrade_ClientErrorHandling(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		AtomicReference<Throwable> error = new AtomicReference<>();
		doTestConnectionClosedWhenFailedUpgrade(configureServer(serverProtocols, serverCtx),
				configureClient(clientProtocols, clientCtx).doOnRequestError((req, t) -> error.set(t)), null);
		assertThat(error.get()).isNotNull()
				.isInstanceOf(WebSocketClientHandshakeException.class);
		assertThat(((WebSocketClientHandshakeException) error.get()).response().status())
				.isEqualTo(HttpResponseStatus.NOT_FOUND);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestConnectionClosedWhenFailedUpgrade_PublisherErrorHandling(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		AtomicReference<Throwable> error = new AtomicReference<>();
		doTestConnectionClosedWhenFailedUpgrade(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx), error::set);
		assertThat(error.get()).isNotNull()
				.isInstanceOf(WebSocketClientHandshakeException.class);
		assertThat(((WebSocketClientHandshakeException) error.get()).response().status())
				.isEqualTo(HttpResponseStatus.NOT_FOUND);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2TestIssue3295(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		doTestIssue3295(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx));
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	@SuppressWarnings("deprecation")
	void websocketOverH2NotSupportedByServerExplicit(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		TestHttpMeterRegistrarAdapter metricsRegistrar = new TestHttpMeterRegistrarAdapter();

		ConnectionProvider provider =
				ConnectionProvider.builder("clientSendsError")
				                  .maxConnections(10)
				                  .metrics(true, () -> metricsRegistrar)
				                  .build();
		try {
			HttpServer server = serverCtx == null ? createServer().protocol(serverProtocols) :
					createServer().protocol(serverProtocols).secure(spec -> spec.sslContext(serverCtx));
			websocketOverH2Negative(server.http2Settings(spec -> spec.connectProtocolEnabled(false)),
					configureClient(provider, clientProtocols, clientCtx),
					t -> ("Websocket is not supported by the server. " +
							"[SETTINGS_ENABLE_CONNECT_PROTOCOL(0x8)=0] was received.").equals(t.getMessage()));

			HttpConnectionPoolMetrics metrics = metricsRegistrar.metrics;
			assertThat(metrics).isNotNull();
			assertThat(metrics.activeStreamSize()).isEqualTo(0);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	@SuppressWarnings("deprecation")
	void websocketOverH2NotSupportedByServerImplicit(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		TestHttpMeterRegistrarAdapter metricsRegistrar = new TestHttpMeterRegistrarAdapter();

		ConnectionProvider provider =
				ConnectionProvider.builder("clientSendsError")
				                  .maxConnections(10)
				                  .metrics(true, () -> metricsRegistrar)
				                  .build();
		try {
			HttpServer server = serverCtx == null ? createServer().protocol(serverProtocols) :
					createServer().protocol(serverProtocols).secure(spec -> spec.sslContext(serverCtx));
			websocketOverH2Negative(server, configureClient(provider, clientProtocols, clientCtx),
					t -> ("Websocket is not supported by the server. " +
							"Missing SETTINGS_ENABLE_CONNECT_PROTOCOL(0x8).").equals(t.getMessage()));

			HttpConnectionPoolMetrics metrics = metricsRegistrar.metrics;
			assertThat(metrics).isNotNull();
			assertThat(metrics.activeStreamSize()).isEqualTo(0);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2VersionDifferentThan13OnClient(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		websocketOverH2Negative(configureServer(serverProtocols, serverCtx), configureClient(clientProtocols, clientCtx),
				WebsocketClientSpec.builder().version(WebSocketVersion.valueOf("7")).build(),
				t -> "Websocket version [7] is not supported.".equals(t.getMessage()), null);
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2VersionDifferentThan13ReceivedOnServer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		HttpServer server = configureServer(serverProtocols, serverCtx);
		server = server.doOnConnection(conn ->
		                   conn.addHandlerLast("test", new ChannelHandler() {

		                       @Override
		                       public void channelRead(ChannelHandlerContext ctx, Object msg) {
		                           if (msg instanceof HttpRequest) {
		                               ((HttpRequest) msg).headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "7");
		                               ctx.pipeline().remove(this);
		                           }
		                           ctx.fireChannelRead(msg);
		                       }
		                   }));

		websocketOverH2Negative(server, configureClient(clientProtocols, clientCtx),
				t -> "Invalid websocket handshake response status [400 Bad Request].".equals(t.getMessage()),
				"Websocket version [7] is not supported.");
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2MethodDifferentThanConnectReceivedOnServer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		websocketOverH2NegativeServer(serverProtocols, clientProtocols, serverCtx, clientCtx,
				h -> {
					Http2HeadersFrame newHeaders = new DefaultHttp2HeadersFrame(h.headers());
					newHeaders.headers().set(Http2Headers.PseudoHeaderName.METHOD.value(), HttpMethod.GET.name());
					return newHeaders;
				},
				"Invalid websocket request handshake method [GET].");
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2MissingProtocolHeaderReceivedOnServer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		websocketOverH2NegativeServer(serverProtocols, clientProtocols, serverCtx, clientCtx,
				h -> {
					Http2HeadersFrame newHeaders = new DefaultHttp2HeadersFrame(h.headers());
					newHeaders.headers().remove(Http2Headers.PseudoHeaderName.PROTOCOL.value());
					return newHeaders;
				},
				"Invalid websocket request, missing [:protocol=websocket] header.");
	}

	@ParameterizedTest
	@MethodSource("http2CompatibleCombinations")
	void websocketOverH2EndOfStreamReceivedOnServer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx) throws Exception {
		websocketOverH2NegativeServer(serverProtocols, clientProtocols, serverCtx, clientCtx,
				h -> new DefaultHttp2HeadersFrame(h.headers(), true),
				"Failed to upgrade to websocket. End of stream is received.");
	}

	void websocketOverH2NegativeServer(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			@Nullable Http2SslContextSpec serverCtx, @Nullable Http2SslContextSpec clientCtx,
			Function<Http2HeadersFrame, Http2HeadersFrame> headers, String serverError) throws Exception {
		HttpClient client = configureClient(clientProtocols, clientCtx);
		client = client.doOnRequest((req, conn) ->
		                   conn.channel().pipeline().addFirst("websocketOverH2NegativeServer", new ChannelHandler() {

		                       @Override
		                       public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		                           if (msg instanceof Http2HeadersFrame) {
		                               return ctx.write(headers.apply((Http2HeadersFrame) msg));
		                           }
		                           else {
		                               return ctx.write(msg);
		                           }
		                       }
		                   }));

		websocketOverH2Negative(configureServer(serverProtocols, serverCtx), client,
				t -> "Invalid websocket handshake response status [400 Bad Request].".equals(t.getMessage()),
				serverError);
	}

	void websocketOverH2Negative(HttpServer server, HttpClient client, Predicate<Throwable> predicate) throws Exception {
		websocketOverH2Negative(server, client, WebsocketClientSpec.builder().build(), predicate, null);
	}

	void websocketOverH2Negative(HttpServer server, HttpClient client, Predicate<Throwable> predicate,
			String serverError) throws Exception {
		websocketOverH2Negative(server, client, WebsocketClientSpec.builder().build(), predicate, serverError);
	}

	void websocketOverH2Negative(HttpServer server, HttpClient client,
			WebsocketClientSpec clientSpec, Predicate<Throwable> predicate, @Nullable String serverError) throws Exception {
		AtomicReference<Throwable> serverThrowable = new AtomicReference<>();
		disposableServer =
				server.handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("test")))
				                               .doOnError(serverThrowable::set))
				      .bindNow();

		CountDownLatch connClosed = new CountDownLatch(1);
		client.doOnRequest((req, conn) -> conn.channel().closeFuture().addListener(f -> connClosed.countDown()))
		      .websocket(clientSpec)
		      .uri("/test")
		      .handle((i, o) -> i.receive().asString())
		      .collectList()
		      .as(StepVerifier::create)
		      .expectErrorMatches(predicate)
		      .verify(Duration.ofSeconds(30));

		assertThat(connClosed.await(30, TimeUnit.SECONDS)).isTrue();

		if (serverError != null) {
			assertThat(serverThrowable.get())
					.isNotNull()
					.hasMessage(serverError);
		}
	}

	@SuppressWarnings("deprecation")
	HttpClient configureClient(HttpProtocol[] clientProtocols, @Nullable Http2SslContextSpec clientCtx) {
		return clientCtx == null ? createClient(() -> disposableServer.address()).protocol(clientProtocols) :
				createClient(() -> disposableServer.address()).protocol(clientProtocols).secure(spec -> spec.sslContext(clientCtx));
	}

	@SuppressWarnings("deprecation")
	HttpClient configureClient(ConnectionProvider provider, HttpProtocol[] clientProtocols, @Nullable Http2SslContextSpec clientCtx) {
		return clientCtx == null ? createClient(provider, () -> disposableServer.address()).protocol(clientProtocols) :
				createClient(provider, () -> disposableServer.address()).protocol(clientProtocols).secure(spec -> spec.sslContext(clientCtx));
	}

	@SuppressWarnings("deprecation")
	static HttpServer configureServer(HttpProtocol[] serverProtocols, @Nullable Http2SslContextSpec serverCtx) {
		HttpServer server = serverCtx == null ? createServer() : createServer().secure(spec -> spec.sslContext(serverCtx));
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

	static final class TestHttpMeterRegistrarAdapter extends HttpMeterRegistrarAdapter {
		@Nullable HttpConnectionPoolMetrics metrics;

		@Override
		protected void registerMetrics(String poolName, String id, SocketAddress remoteAddress, HttpConnectionPoolMetrics metrics) {
			this.metrics = metrics;
		}

		@Override
		public void deRegisterMetrics(String poolName, String id, SocketAddress remoteAddress) {
		}
	}
}
