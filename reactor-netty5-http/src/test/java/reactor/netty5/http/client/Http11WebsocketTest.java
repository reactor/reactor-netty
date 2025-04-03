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

import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import io.netty5.handler.codec.http.websocketx.WebSocketFrame;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.logging.ReactorNettyHttpMessageLogFactory;
import reactor.netty5.http.server.HttpServer;
import reactor.netty5.http.server.WebsocketServerSpec;
import reactor.netty5.tcp.SslProvider;

import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class Http11WebsocketTest extends WebsocketTest {

	@Test
	void simpleTest() {
		doSimpleTest(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void serverWebSocketFailed() {
		doServerWebSocketFailed(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void unidirectional() {
		doUnidirectional(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void webSocketRespondsToRequestsFromClients() {
		doWebSocketRespondsToRequestsFromClients(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void unidirectionalBinary() {
		doUnidirectionalBinary(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void duplexEcho() throws Exception {
		doDuplexEcho(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void simpleSubProtocolServerNoSubProtocol() {
		doSimpleSubProtocolServerNoSubProtocol(createServer(), createClient(() -> disposableServer.address()),
				"Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	void simpleSubProtocolServerNotSupported() {
		doSimpleSubProtocolServerNotSupported(createServer(), createClient(() -> disposableServer.address()),
				"Invalid subprotocol. Actual: null. Expected one of: SUBPROTOCOL,OTHER");
	}

	@Test
	void simpleSubProtocolServerSupported() {
		doSimpleSubProtocolServerSupported(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void simpleSubProtocolSelected() {
		doSimpleSubProtocolSelected(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void noSubProtocolSelected() {
		doNoSubProtocolSelected(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void anySubProtocolSelectsFirstClientProvided() {
		doAnySubProtocolSelectsFirstClientProvided(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void sendToWebsocketSubProtocol() throws InterruptedException {
		doSendToWebsocketSubProtocol(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testMaxFramePayloadLengthFailed() {
		doTestMaxFramePayloadLengthFailed(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testMaxFramePayloadLengthSuccess() {
		doTestMaxFramePayloadLengthSuccess(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testServerMaxFramePayloadLengthFailed() {
		doTestServerMaxFramePayloadLength(createServer(), createClient(() -> disposableServer.address()), 10,
				Flux.just("1", "2", "12345678901", "3"), Flux.just("1", "2"), 2);
	}

	@Test
	void testServerMaxFramePayloadLengthSuccess() {
		doTestServerMaxFramePayloadLength(createServer(), createClient(() -> disposableServer.address()), 11,
				Flux.just("1", "2", "12345678901", "3"), Flux.just("1", "2", "12345678901", "3"), 4);
	}

	@Test
	void closePool() {
		doClosePool(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testCloseWebSocketFrameSentByServer() {
		doTestCloseWebSocketFrameSentByServer(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testCloseWebSocketFrameSentByClient() {
		doTestCloseWebSocketFrameSentByClient(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testConnectionAliveWhenTransformationErrors_1() {
		doTestConnectionAliveWhenTransformationErrors(createServer(), createClient(() -> disposableServer.address()), (in, out) ->
		        out.sendObject(in.aggregateFrames()
		                         .receiveFrames()
		                         .map(WebSocketFrame::binaryData)
		                         //.share()
		                         .publish()
		                         .autoConnect()
		                         .map(buffer -> buffer.readCharSequence(buffer.readableBytes(), Charset.defaultCharset()).toString())
		                         .map(Integer::parseInt)
		                         .map(i -> new TextWebSocketFrame(out.alloc(), i + ""))
		                         .retry()),
		        Flux.just("1", "2"), 2);
	}

	@Test
	void testConnectionAliveWhenTransformationErrors_2() {
		doTestConnectionAliveWhenTransformationErrors(createServer(), createClient(() -> disposableServer.address()), (in, out) ->
		        out.sendObject(in.aggregateFrames()
		                         .receiveFrames()
		                         .map(WebSocketFrame::binaryData)
		                         .concatMap(content ->
		                             Mono.just(content)
		                                 .map(buffer -> buffer.readCharSequence(buffer.readableBytes(), Charset.defaultCharset()).toString())
		                                 .map(Integer::parseInt)
		                                 .map(i -> new TextWebSocketFrame(out.alloc(), i + ""))
		                                 .onErrorResume(t -> Mono.just(new TextWebSocketFrame(out.alloc(), "error"))))),
		        Flux.just("1", "error", "2"), 3);
	}

	@Test
	void testClientOnCloseIsInvokedClientSendClose() throws Exception {
		doTestClientOnCloseIsInvokedClientSendClose(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testClientOnCloseIsInvokedClientDisposed() throws Exception {
		doTestClientOnCloseIsInvokedClientDisposed(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testClientOnCloseIsInvokedServerInitiatedClose() throws Exception {
		doTestClientOnCloseIsInvokedServerInitiatedClose(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue460() {
		doTestIssue460(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue444_1() {
		doTestIssue444(createServer(), createClient(() -> disposableServer.address()), (in, out) ->
				out.sendObject(Flux.error(new Throwable())
						.onErrorResume(ex -> out.sendClose(1001, "Going Away"))
						.cast(WebSocketFrame.class)));
	}

	@Test
	void testIssue444_2() {
		doTestIssue444(createServer(), createClient(() -> disposableServer.address()), (in, out) ->
				out.send(Flux.range(0, 10)
						.map(i -> {
							if (i == 5) {
								out.sendClose(1001, "Going Away").subscribe();
							}
							return out.alloc().copyOf((i + "").getBytes(Charset.defaultCharset()));
						})));
	}

	@Test
	void testIssue444_3() {
		doTestIssue444(createServer(), createClient(() -> disposableServer.address()), (in, out) ->
				out.sendObject(Flux.error(new Throwable())
								.onErrorResume(ex -> Flux.empty())
								.cast(WebSocketFrame.class))
						.then(Mono.defer(() -> out.sendObject(
								new CloseWebSocketFrame(out.alloc(), 1001, "Going Away")).then())));
	}

	// https://bugzilla.mozilla.org/show_bug.cgi?id=691300
	@Test
	void firefoxConnectionTest() {
		disposableServer = createServer()
				.route(r -> r.ws("/ws", (in, out) -> out.sendString(Mono.just("test"))))
				.bindNow();

		HttpClientResponse res =
				createClient(disposableServer.port())
						.headers(h ->
								h.add(HttpHeaderNames.CONNECTION, "keep-alive, Upgrade")
										.add(HttpHeaderNames.UPGRADE, "websocket")
										.add(HttpHeaderNames.ORIGIN, "http://localhost")
										.add(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13")
										.add(HttpHeaderNames.SEC_WEBSOCKET_KEY, "Ex6C3J0T352QOL9CgUjdUQ"))
						.get()
						.uri("/ws")
						.response()
						.block(Duration.ofSeconds(5));
		assertThat(res).isNotNull();
		assertThat(res.status()).isEqualTo(HttpResponseStatus.SWITCHING_PROTOCOLS);
	}

	@Test
	void testIssue821() throws Exception {
		doTestIssue821(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue900_1() throws Exception {
		doTestIssue900_1(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue900_2() throws Exception {
		doTestIssue900_2(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue663_1() throws Exception {
		doTestIssue663_1(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue663_2() throws Exception {
		doTestIssue663_2(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue663_3() throws Exception {
		doTestIssue663_3(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue663_4() throws Exception {
		doTestIssue663_4(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue967() throws Exception {
		doTestIssue967(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue970_WithCompress() {
		doTestWebsocketCompression(createServer(), createClient(() -> disposableServer.address()), true);
	}

	@Test
	void testIssue970_NoCompress() {
		doTestWebsocketCompression(createServer(), createClient(() -> disposableServer.address()), false);
	}

	@Test
	void testIssue2973() {
		doTestWebsocketCompression(createServer(), createClient(() -> disposableServer.address()), true, true);
	}

	@Test
	@SuppressWarnings("NullAway")
	void websocketOperationsBadValues() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations parent = new HttpClientOperations(Connection.from(channel),
				ConnectionObserver.emptyListener(), ReactorNettyHttpMessageLogFactory.INSTANCE);
		WebsocketClientOperations ops = new WebsocketClientOperations(new URI(""),
				WebsocketClientSpec.builder().build(), parent);

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> ops.aggregateFrames(-1))
				.withMessageEndingWith("-1 (expected: >= 0)");

		// Deliberately suppress "NullAway" for testing purposes
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> ops.send(null));

		// Deliberately suppress "NullAway" for testing purposes
		assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> ops.sendString(null, Charset.defaultCharset()));
	}

	@Test
	void testIssue1485_CloseFrameSentByClient() throws Exception {
		doTestIssue1485_CloseFrameSentByClient(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testIssue1485_CloseFrameSentByServer() throws Exception {
		doTestIssue1485_CloseFrameSentByServer(createServer(), createClient(() -> disposableServer.address()));
	}

	@Test
	void testConnectionClosedWhenFailedUpgrade_NoErrorHandling() throws Exception {
		doTestConnectionClosedWhenFailedUpgrade(createServer(), createClient(() -> disposableServer.address()), null);
	}

	@Test
	void testConnectionClosedWhenFailedUpgrade_ClientErrorHandling() throws Exception {
		AtomicReference<Throwable> error = new AtomicReference<>();
		doTestConnectionClosedWhenFailedUpgrade(createServer(),
				createClient(() -> disposableServer.address()).doOnRequestError((req, t) -> error.set(t)), null);
		assertThat(error.get()).isNotNull()
				.isInstanceOf(WebSocketClientHandshakeException.class);
		assertThat(((WebSocketClientHandshakeException) error.get()).response().status())
				.isEqualTo(HttpResponseStatus.NOT_FOUND);
	}

	@Test
	void testConnectionClosedWhenFailedUpgrade_PublisherErrorHandling() throws Exception {
		AtomicReference<Throwable> error = new AtomicReference<>();
		doTestConnectionClosedWhenFailedUpgrade(createServer(), createClient(() -> disposableServer.address()), error::set);
		assertThat(error.get()).isNotNull()
				.isInstanceOf(WebSocketClientHandshakeException.class);
		assertThat(((WebSocketClientHandshakeException) error.get()).response().status())
				.isEqualTo(HttpResponseStatus.NOT_FOUND);
	}

	@ParameterizedTest
	@MethodSource("http11CompatibleProtocols")
	@SuppressWarnings("deprecation")
	public void testIssue3036(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			SslProvider.@Nullable ProtocolSslContextSpec serverCtx, SslProvider.@Nullable ProtocolSslContextSpec clientCtx) {
		WebsocketServerSpec websocketServerSpec = WebsocketServerSpec.builder().compress(true).build();

		HttpServer httpServer = createServer().protocol(serverProtocols);
		if (serverCtx != null) {
			httpServer = httpServer.secure(spec -> spec.sslContext(serverCtx));
		}

		disposableServer =
				httpServer.handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("test")), websocketServerSpec))
				          .bindNow();

		WebsocketClientSpec webSocketClientSpec = WebsocketClientSpec.builder().compress(true).build();

		HttpClient httpClient = createClient(disposableServer::address).protocol(clientProtocols);
		if (clientCtx != null) {
			httpClient = httpClient.secure(spec -> spec.sslContext(clientCtx));
		}

		AtomicReference<String> responseHeaders = new AtomicReference<>();
		httpClient.websocket(webSocketClientSpec)
		          .handle((in, out) -> {
		              responseHeaders.set(getHeader(in.headers(), HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS));
		              return out.sendClose();
		          })
		          .then()
		          .block(Duration.ofSeconds(5));

		assertThat(responseHeaders.get()).isNotNull().contains("permessage-deflate");
	}

	@Test
	void testIssue3295() throws Exception {
		doTestIssue3295(createServer(), createClient(() -> disposableServer.address()));
	}

	static Stream<Arguments> http11CompatibleProtocols() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http11SslContextSpec", serverCtx11), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null)
		);
	}
}
