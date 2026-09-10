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

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static reactor.netty.Metrics.CONNECTION_DURATION;
import static reactor.netty.Metrics.DATA_RECEIVED;
import static reactor.netty.Metrics.DATA_RECEIVED_TIME;
import static reactor.netty.Metrics.DATA_SENT;
import static reactor.netty.Metrics.DATA_SENT_TIME;
import static reactor.netty.Metrics.ERRORS;
import static reactor.netty.Metrics.HANDSHAKE_TIME;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;
import static reactor.netty.Metrics.WEBSOCKET_SERVER_PREFIX;
import static reactor.netty.micrometer.CounterAssert.assertCounter;
import static reactor.netty.micrometer.DistributionSummaryAssert.assertDistributionSummary;
import static reactor.netty.micrometer.TimerAssert.assertTimer;

/**
 * WebSocket server metrics tests.
 * <p>Each test binds its own server, because the configuration under test differs per case
 * ({@code uriTagValue} mapper, {@code HTTP/2}, an injected handler that fails the handshake write).
 *
 * @author LivingLikeKrillin
 * @since 1.3.7
 */
class WebSocketServerMetricsHandlerTests extends BaseHttpTest {

	static final String WS_SERVER_HANDSHAKE_TIME = WEBSOCKET_SERVER_PREFIX + HANDSHAKE_TIME;
	static final String WS_SERVER_CONNECTION_DURATION = WEBSOCKET_SERVER_PREFIX + CONNECTION_DURATION;
	static final String WS_SERVER_DATA_SENT_TIME = WEBSOCKET_SERVER_PREFIX + DATA_SENT_TIME;
	static final String WS_SERVER_DATA_RECEIVED_TIME = WEBSOCKET_SERVER_PREFIX + DATA_RECEIVED_TIME;
	static final String WS_SERVER_DATA_SENT = WEBSOCKET_SERVER_PREFIX + DATA_SENT;
	static final String WS_SERVER_DATA_RECEIVED = WEBSOCKET_SERVER_PREFIX + DATA_RECEIVED;
	static final String WS_SERVER_ERRORS = WEBSOCKET_SERVER_PREFIX + ERRORS;

	private ConnectionProvider provider;
	private HttpClient httpClient;
	private MeterRegistry registry;
	private final ServerCloseHandler serverCloseHandler = new ServerCloseHandler();

	@BeforeEach
	void setUp() {
		provider = ConnectionProvider.create("WebSocketServerMetricsHandlerTests", 1);
		httpClient = createClient(provider, () -> disposableServer.address())
				.metrics(true, Function.identity());

		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
	}

	@AfterEach
	void tearDown() {
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
	void testServerWebSocketHandshakeTimeMicrometer() {
		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, Function.identity())
				              .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				              .bindNow();

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		assertTimer(registry, WS_SERVER_HANDSHAKE_TIME, URI, "/ws", STATUS, "101")
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
	}

	@Test
	void testServerWebSocketHandshakeTimeMicrometerHttp2() {
		disposableServer =
				createServer().host("127.0.0.1")
				              .protocol(HttpProtocol.H2C)
				              .http2Settings(spec -> spec.connectProtocolEnabled(true))
				              .metrics(true, Function.identity())
				              .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				              .bindNow();

		HttpClient client = httpClient.protocol(HttpProtocol.H2C);

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.receive().aggregate().asString())
		      .as(StepVerifier::create)
		      .expectNext("Hello World!")
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		assertTimer(registry, WS_SERVER_HANDSHAKE_TIME, URI, "/ws", STATUS, "200")
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
	}

	@Test
	void testServerWebSocketHandshakeFailureMicrometer() {
		// Fail the write of the handshake response so that the handshake future completes exceptionally
		// after the WebSocket metrics handler has been installed. On the HTTP/1.1 path this is the only
		// way the ERROR status is reached: a request that cannot be upgraded at all is rejected before
		// the handler is there.
		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, Function.identity())
				              .doOnConnection(cnx -> cnx.channel().pipeline().addAfter(NettyPipeline.HttpCodec,
				                      "failHandshakeWrite",
				                      new ChannelDuplexHandler() {
				                          @Override
				                          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
				                              if (msg instanceof HttpResponse &&
				                                      ((HttpResponse) msg).status().code() == 101) {
				                                  ReferenceCountUtil.release(msg);
				                                  promise.setFailure(new IOException("Simulated handshake write failure"));
				                                  ctx.close();
				                              }
				                              else {
				                                  ctx.write(msg, promise);
				                              }
				                          }
				                      }))
				              .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				              .bindNow();

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectError()
		          .verify(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertTimer(registry, WS_SERVER_HANDSHAKE_TIME, URI, "/ws", STATUS, "ERROR")
		                   .hasCountEqualTo(1)
		                   .hasTotalTimeGreaterThan(0);

		           // The handler is installed before the handshake runs, so the duration of the
		           // failed attempt is recorded as well
		           assertTimer(registry, WS_SERVER_CONNECTION_DURATION, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalTimeGreaterThan(0);
		       });
	}

	@Test
	void testServerWebSocketUriTagValueFunctionMicrometer() {
		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, s -> "/normalized")
				              .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				              .bindNow();

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		assertTimer(registry, WS_SERVER_HANDSHAKE_TIME, URI, "/normalized", STATUS, "101")
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
	}

	@Test
	void testServerWebSocketConnectionDurationMicrometer() throws Exception {
		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, Function.identity())
				              .doOnConnection(cnx -> serverCloseHandler.register(cnx.channel()))
				              .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				              .bindNow();

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> in.receive().aggregate().asString())
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		// connection.duration is recorded when the WS metrics handler is removed from the pipeline,
		// which happens on connection close; make sure that has happened server-side first
		assertThat(serverCloseHandler.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() ->
		               assertTimer(registry, WS_SERVER_CONNECTION_DURATION, URI, "/ws")
		                       .hasCountEqualTo(1)
		                       .hasTotalTimeGreaterThan(0));
	}

	@Test
	void testServerWebSocketDataMetricsMicrometer() {
		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, Function.identity())
				              .handle((req, res) -> res.sendWebsocket((in, out) ->
				                      // Send and receive concurrently so that both a data.sent and a
				                      // data.received final data frame are recorded on the server side.
				                      out.sendString(Mono.just("Hello World!"))
				                         .then()
				                         .and(in.receive().asString().take(1).then())))
				              .bindNow();

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> out.sendString(Mono.just("ping"))
		                                  .then()
		                                  .thenMany(in.receive().asString().take(1)))
		          .as(StepVerifier::create)
		          .expectNext("Hello World!")
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertTimer(registry, WS_SERVER_DATA_SENT_TIME, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalTimeGreaterThan(0);

		           assertTimer(registry, WS_SERVER_DATA_RECEIVED_TIME, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalTimeGreaterThan(0);

		           // "Hello World!" out, "ping" in; exact totals also guard against a frame being counted twice
		           assertDistributionSummary(registry, WS_SERVER_DATA_SENT, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalAmountEqualTo(12);

		           assertDistributionSummary(registry, WS_SERVER_DATA_RECEIVED, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalAmountEqualTo(4);
		       });
	}

	@Test
	void testServerWebSocketMultipleMessagesSentMicrometer() {
		int messageCount = 16;
		String message = "abcde";
		int messageSize = message.getBytes(CharsetUtil.UTF_8).length;

		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, Function.identity())
				              .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendObject(
				                      Flux.range(0, messageCount).map(i -> new TextWebSocketFrame(message)))
				                                                                       .then()))
				              .bindNow();

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> in.receive().asString().take(messageCount).then())
		          .as(StepVerifier::create)
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		// Each message must be recorded as its own data.sent record. The write path snapshots the
		// per-message accumulators before the write listener runs; if it read them inside the listener
		// instead, a following message would corrupt this message's byte and time attribution.
		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertDistributionSummary(registry, WS_SERVER_DATA_SENT, URI, "/ws")
		                   .hasCountEqualTo(messageCount)
		                   .hasTotalAmountEqualTo((double) messageSize * messageCount);

		           assertTimer(registry, WS_SERVER_DATA_SENT_TIME, URI, "/ws")
		                   .hasCountEqualTo(messageCount)
		                   .hasTotalTimeGreaterThan(0);
		       });

		// Per-message attribution must be exact: no single record may carry more than one message
		DistributionSummary dataSent = registry.find(WS_SERVER_DATA_SENT).tag(URI, "/ws").summary();
		assertThat(dataSent).isNotNull();
		assertThat(dataSent.max()).isLessThanOrEqualTo(messageSize);

		// And no send time may be a garbage 'now - 0' duration
		Timer dataSentTime = registry.find(WS_SERVER_DATA_SENT_TIME).tag(URI, "/ws").timer();
		assertThat(dataSentTime).isNotNull();
		assertThat(dataSentTime.max(TimeUnit.SECONDS)).isLessThan(10d);
	}

	@Test
	void testServerWebSocketMultipleMessagesReceivedMicrometer() {
		int messageCount = 16;
		String message = "abcde";
		int messageSize = message.getBytes(CharsetUtil.UTF_8).length;

		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, Function.identity())
				              .handle((req, res) -> res.sendWebsocket((in, out) ->
				                      in.receive().asString().take(messageCount).then()))
				              .bindNow();

		// Space the messages out so that a per-message duration and a duration accumulated since the
		// first message differ by orders of magnitude rather than by microseconds.
		Duration gap = Duration.ofMillis(20);

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> out.sendObject(
		                  Flux.range(0, messageCount)
		                      .delayElements(gap)
		                      .map(i -> new TextWebSocketFrame(message)))
		                                  .then())
		          .as(StepVerifier::create)
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		// Each message must be recorded as its own data.received record with its own duration. The read
		// path resets the per-message clock after every message; if it did not, every record after the
		// first would measure from the first message and the durations would grow without bound.
		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertDistributionSummary(registry, WS_SERVER_DATA_RECEIVED, URI, "/ws")
		                   .hasCountEqualTo(messageCount)
		                   .hasTotalAmountEqualTo((double) messageSize * messageCount);

		           assertTimer(registry, WS_SERVER_DATA_RECEIVED_TIME, URI, "/ws")
		                   .hasCountEqualTo(messageCount)
		                   .hasTotalTimeGreaterThan(0);
		       });

		// A per-message duration is the time to process one frame, far below the gap between messages.
		// A duration accumulated since the first message would be a multiple of that gap.
		Timer dataReceivedTime = registry.find(WS_SERVER_DATA_RECEIVED_TIME).tag(URI, "/ws").timer();
		assertThat(dataReceivedTime).isNotNull();
		assertThat(dataReceivedTime.max(TimeUnit.MILLISECONDS))
				.as("per-message receive durations must not accumulate")
				.isLessThan((double) gap.toMillis());
	}

	@Test
	void testServerWebSocketControlFramesExcludedFromDataMetricsMicrometer() {
		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, Function.identity())
				              .handle((req, res) -> res.sendWebsocket((in, out) ->
				                      in.receive().asString().take(1).then()))
				              .bindNow();

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> out.sendObject(Flux.just(new PingWebSocketFrame(),
		                                                        new TextWebSocketFrame("data")))
		                                  .then())
		          .as(StepVerifier::create)
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           // Only the 4-byte text frame is counted; the Ping and the Close frame are not
		           assertDistributionSummary(registry, WS_SERVER_DATA_RECEIVED, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalAmountEqualTo(4);

		           // The server sends no data frame here, only the automatic Pong and the Close frame,
		           // so nothing is recorded on the outbound side either. The meter is not registered
		           // at all unless something was recorded, hence the null-tolerant read.
		           DistributionSummary dataSent = registry.find(WS_SERVER_DATA_SENT).tag(URI, "/ws").summary();
		           assertThat(dataSent == null ? 0 : dataSent.count())
		                   .as("server WS data.sent must not record control frames")
		                   .isZero();
		       });
	}

	@Test
	void testServerWebSocketFragmentedDataReceivedMicrometer() {
		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, Function.identity())
				              .handle((req, res) -> res.sendWebsocket((in, out) ->
				                      in.aggregateFrames().receiveFrames().take(1).then()))
				              .bindNow();

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> out.sendObject(Flux.just(new TextWebSocketFrame(false, 0, "hello"),
		                                                        new ContinuationWebSocketFrame(true, 0, " world")))
		                                  .then())
		          .as(StepVerifier::create)
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           // "hello" + " world" is one message: recorded once, on the final fragment
		           assertDistributionSummary(registry, WS_SERVER_DATA_RECEIVED, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalAmountEqualTo(11);

		           assertTimer(registry, WS_SERVER_DATA_RECEIVED_TIME, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalTimeGreaterThan(0);
		       });
	}

	@Test
	void testServerWebSocketErrorsOnApplicationErrorMicrometer() throws Exception {
		// The handler's outbound Publisher errors. This flows through
		// WebsocketSubscriber.onError -> ChannelOperations.onError -> WebsocketServerOperations
		// .onOutboundError, a path that never reaches exceptionCaught, so it would otherwise never
		// be counted by the errors meter.
		disposableServer =
				createServer().host("127.0.0.1")
				              .metrics(true, Function.identity())
				              .doOnConnection(cnx -> serverCloseHandler.register(cnx.channel()))
				              .handle((req, res) -> res.sendWebsocket((in, out) ->
				                      out.sendString(Flux.error(new RuntimeException("boom")))))
				              .bindNow();

		httpClient.websocket()
		          .uri("/ws")
		          .handle((in, out) -> in.receiveFrames().then())
		          .then()
		          .onErrorResume(e -> Mono.empty())
		          .as(StepVerifier::create)
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));

		assertThat(serverCloseHandler.awaitClientClosedOnServer()).as("awaitClientClosedOnServer timeout").isTrue();

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() ->
		               assertCounter(registry, WS_SERVER_ERRORS, URI, "/ws")
		                       .hasCountEqualTo(1));
	}

	/**
	 * Counts down when the server side of the connection goes inactive, so that a test can wait for
	 * the close before reading meters that are recorded on close.
	 */
	static final class ServerCloseHandler extends ChannelInboundHandlerAdapter {

		static final String HANDLER_NAME = "WebSocketServerMetricsHandlerTests.ServerCloseHandler";

		private @Nullable CountDownLatch latch;

		void register(Channel channel) {
			this.latch = new CountDownLatch(1);
			if (channel instanceof Http2StreamChannel) {
				if (channel.parent().pipeline().get(HANDLER_NAME) == null) {
					channel.parent().pipeline().addLast(HANDLER_NAME, this);
				}
			}
			else {
				channel.pipeline().addBefore(NettyPipeline.ReactiveBridge, HANDLER_NAME, this);
			}
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			if (latch != null) {
				latch.countDown();
			}
			ctx.fireChannelInactive();
		}

		@Override
		public boolean isSharable() {
			return true;
		}

		boolean awaitClientClosedOnServer() throws InterruptedException {
			return latch == null || latch.await(30, TimeUnit.SECONDS);
		}
	}
}
