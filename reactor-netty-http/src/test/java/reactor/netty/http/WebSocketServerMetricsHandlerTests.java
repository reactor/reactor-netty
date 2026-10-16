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
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.netty.BaseHttpTest;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.LogTracker;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.ContextAwareWebSocketServerMetricsRecorder;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerMetricsRecorder;
import reactor.netty.http.server.WebSocketServerMetricsRecorder;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 *
 * @author LivingLikeKrillin
 * @since 1.3.8
 */
class WebSocketServerMetricsHandlerTests extends BaseHttpTest {

	static final String WS_SERVER_HANDSHAKE_TIME = WEBSOCKET_SERVER_PREFIX + HANDSHAKE_TIME;
	static final String WS_SERVER_CONNECTION_DURATION = WEBSOCKET_SERVER_PREFIX + CONNECTION_DURATION;
	static final String WS_SERVER_DATA_SENT_TIME = WEBSOCKET_SERVER_PREFIX + DATA_SENT_TIME;
	static final String WS_SERVER_DATA_RECEIVED_TIME = WEBSOCKET_SERVER_PREFIX + DATA_RECEIVED_TIME;
	static final String WS_SERVER_DATA_SENT = WEBSOCKET_SERVER_PREFIX + DATA_SENT;
	static final String WS_SERVER_DATA_RECEIVED = WEBSOCKET_SERVER_PREFIX + DATA_RECEIVED;
	static final String WS_SERVER_ERRORS = WEBSOCKET_SERVER_PREFIX + ERRORS;

	HttpClient httpClient;
	HttpServer httpServer;

	private ConnectionProvider provider;
	private MeterRegistry registry;

	@BeforeEach
	void setUp() {
		provider = ConnectionProvider.create("WebSocketServerMetricsHandlerTests", 1);
		httpClient = createClient(provider, () -> disposableServer.address()).metrics(true, Function.identity());
		httpServer = createServer().host("127.0.0.1").metrics(true, Function.identity());

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

	void doTestServerWebSocketHandshakeTimeMicrometer(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				      .bindNow();

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.receive().aggregate().asString())
		      .as(StepVerifier::create)
		      .expectNext("Hello World!")
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		assertTimer(registry, WS_SERVER_HANDSHAKE_TIME, URI, "/ws", STATUS, handshakeStatus())
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
	}

	void doTestServerWebSocketHandshakeFailureMicrometer(HttpServer server, HttpClient client) {
		// Fail the handshake response write, so that the handshake fails after the WebSocket metrics handler is installed
		disposableServer =
				server.doOnConnection(cnx -> cnx.channel().pipeline().addAfter(NettyPipeline.HttpCodec,
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

		client.websocket()
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

		           assertTimer(registry, WS_SERVER_CONNECTION_DURATION, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalTimeGreaterThan(0);
		       });
	}

	void doTestServerWebSocketSynchronousHandshakeFailureMicrometer(HttpServer server, HttpClient client, boolean onEventLoop) {
		disposableServer =
				server.handle((req, res) -> {
				          if (onEventLoop) {
				              return res.sendWebsocket((in, out) -> Mono.never());
				          }
				          return Mono.defer(() -> res.sendWebsocket((in, out) -> Mono.never()))
				                     .subscribeOn(Schedulers.boundedElastic());
				      })
				      .bindNow();

		sendInvalidWebSocketRequest(client);

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertTimer(registry, WS_SERVER_HANDSHAKE_TIME, URI, "/ws", STATUS, "ERROR")
		                   .hasCountEqualTo(1)
		                   .hasTotalTimeGreaterThan(0);
		           assertTimer(registry, WS_SERVER_CONNECTION_DURATION, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalTimeGreaterThan(0);
		       });
	}

	void doTestServerWebSocketSynchronousHandshakeFailureContextAwareRecorder(HttpServer server, HttpClient client, boolean onEventLoop) {
		ServerContextAwareWebSocketRecorder recorder = new ServerContextAwareWebSocketRecorder();
		disposableServer =
				server.metrics(true, () -> recorder, Function.identity())
				      .handle((req, res) -> {
				          if (onEventLoop) {
				              return res.sendWebsocket((in, out) -> Mono.never());
				          }
				          return Mono.defer(() -> res.sendWebsocket((in, out) -> Mono.never()))
				                     .subscribeOn(Schedulers.boundedElastic());
				      })
				      .mapHandle((mono, conn) -> mono.contextWrite(Context.of("testContextAwareRecorder", "OK")))
				      .bindNow();

		sendInvalidWebSocketRequest(client);

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertThat(recorder.handshake).containsExactly("OK");
		           assertThat(recorder.connectionDuration).containsExactly("OK");
		       });
	}

	void doTestServerWebSocketFailureBeforeHandshakeStart(HttpServer server, HttpClient client) {
		ServerWebSocketRecorder recorder = new ServerWebSocketRecorder();
		disposableServer =
				// A handler with the same name makes the WebSocket metrics handler installation fail
				server.metrics(true, () -> recorder, Function.identity())
				      .doOnConnection(cnx -> cnx.channel().pipeline().addBefore(NettyPipeline.ReactiveBridge,
				              NettyPipeline.WsMetricsHandler, new ChannelDuplexHandler()))
				      .handle((req, res) -> res.sendWebsocket((in, out) -> Mono.never()))
				      .bindNow();

		client.disableRetry(true)
		      .websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.receive().then())
		      .as(StepVerifier::create)
		      .expectError()
		      .verify(Duration.ofSeconds(30));

		assertThat(recorder.handshake).isEmpty();
	}

	void doTestServerWebSocketContextAwareRecorderUpgradeNotSubscribed(HttpServer server, HttpClient client,
			boolean failHandshakeRecording, boolean failConnectionDurationRecording) throws Exception {
		ServerContextAwareWebSocketRecorder recorder = new ServerContextAwareWebSocketRecorder();
		recorder.failHandshakeRecording = failHandshakeRecording;
		recorder.failConnectionDurationRecording = failConnectionDurationRecording;
		disposableServer =
				server.metrics(true, () -> recorder, Function.identity())
				      .handle((req, res) -> {
				          // The upgrade happens, but the returned Mono is never subscribed
				          Mono<Void> upgrade = res.sendWebsocket((in, out) -> Mono.never());
				          return Mono.never();
				      })
				      .bindNow();

		int expectedErrors = (failHandshakeRecording ? 1 : 0) + (failConnectionDurationRecording ? 1 : 0);
		try (LogTracker logTracker = new LogTracker("reactor.netty.http.server.AbstractWebSocketServerMetricsHandler",
				expectedErrors, "Exception caught while recording metrics.")) {
			client.websocket()
			      .uri("/ws")
			      .handle((in, out) -> Mono.empty())
			      .as(StepVerifier::create)
			      .expectComplete()
			      .verify(Duration.ofSeconds(30));

			await().atMost(5, TimeUnit.SECONDS)
			       .pollInterval(50, TimeUnit.MILLISECONDS)
			       .untilAsserted(() -> {
			           assertThat(recorder.handshake).containsExactly("KO");
			           assertThat(recorder.connectionDuration).containsExactly("KO");
			       });

			// Each failed recording is logged on its own
			assertThat(logTracker.latch.await(5, TimeUnit.SECONDS)).as("logTracker latch await").isTrue();
			List<String> errors = new ArrayList<>();
			if (failHandshakeRecording) {
				errors.add("Simulated handshake recorder failure");
			}
			if (failConnectionDurationRecording) {
				errors.add("Simulated connection duration recorder failure");
			}
			assertThat(logTracker.actualMessages)
					.extracting(e -> e.getThrowableProxy() == null ? null : e.getThrowableProxy().getMessage())
					.containsExactlyElementsOf(errors);
		}
	}

	void doTestServerWebSocketUriTagValueFunctionMicrometer(HttpServer server, HttpClient client) {
		disposableServer =
				server.metrics(true, s -> "/normalized")
				      .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				      .bindNow();

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.receive().aggregate().asString())
		      .as(StepVerifier::create)
		      .expectNext("Hello World!")
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		assertTimer(registry, WS_SERVER_HANDSHAKE_TIME, URI, "/normalized", STATUS, handshakeStatus())
				.hasCountEqualTo(1)
				.hasTotalTimeGreaterThan(0);
	}

	void doTestServerWebSocketConnectionDurationMicrometer(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				      .bindNow();

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.receive().aggregate().asString())
		      .as(StepVerifier::create)
		      .expectNext("Hello World!")
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() ->
		               assertTimer(registry, WS_SERVER_CONNECTION_DURATION, URI, "/ws")
		                       .hasCountEqualTo(1)
		                       .hasTotalTimeGreaterThan(0));
	}

	void doTestServerWebSocketDataMetricsMicrometer(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) -> res.sendWebsocket((in, out) ->
				              out.sendString(Mono.just("Hello World!"))
				                 .then()
				                 .and(in.receive().asString().take(1).then())))
				      .bindNow();

		client.websocket()
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

		           assertDistributionSummary(registry, WS_SERVER_DATA_SENT, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalAmountEqualTo(12);

		           assertDistributionSummary(registry, WS_SERVER_DATA_RECEIVED, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalAmountEqualTo(4);
		       });
	}

	void doTestServerWebSocketMultipleMessagesSentMicrometer(HttpServer server, HttpClient client) {
		int messageCount = 16;
		String message = "abcde";
		int messageSize = message.getBytes(CharsetUtil.UTF_8).length;

		disposableServer =
				server.handle((req, res) -> res.sendWebsocket((in, out) -> out.sendObject(
				              Flux.range(0, messageCount).map(i -> new TextWebSocketFrame(message))).then()))
				      .bindNow();

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.receive().asString().take(messageCount).then())
		      .as(StepVerifier::create)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

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

		DistributionSummary dataSent = registry.find(WS_SERVER_DATA_SENT).tag(URI, "/ws").summary();
		assertThat(dataSent).isNotNull();
		assertThat(dataSent.max()).isLessThanOrEqualTo(messageSize);

		Timer dataSentTime = registry.find(WS_SERVER_DATA_SENT_TIME).tag(URI, "/ws").timer();
		assertThat(dataSentTime).isNotNull();
		assertThat(dataSentTime.max(TimeUnit.SECONDS)).isLessThan(10d);
	}

	void doTestServerWebSocketMultipleMessagesReceivedMicrometer(HttpServer server, HttpClient client) {
		int messageCount = 16;
		String message = "abcde";
		int messageSize = message.getBytes(CharsetUtil.UTF_8).length;

		disposableServer =
				server.handle((req, res) -> res.sendWebsocket((in, out) ->
				              in.receive().asString().take(messageCount).then()))
				      .bindNow();

		// The gap makes a per-message duration and a duration accumulated since the first message clearly different
		Duration gap = Duration.ofMillis(20);

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> out.sendObject(
		              Flux.range(0, messageCount)
		                  .delayElements(gap)
		                  .map(i -> new TextWebSocketFrame(message))).then())
		      .as(StepVerifier::create)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

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

		Timer dataReceivedTime = registry.find(WS_SERVER_DATA_RECEIVED_TIME).tag(URI, "/ws").timer();
		assertThat(dataReceivedTime).isNotNull();
		assertThat(dataReceivedTime.max(TimeUnit.MILLISECONDS))
				.as("per-message receive durations must not accumulate")
				.isLessThan((double) gap.toMillis());
	}

	void doTestServerWebSocketControlFramesExcludedFromDataMetricsMicrometer(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) -> res.sendWebsocket((in, out) -> in.receive().asString().take(1).then()))
				      .bindNow();

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> out.sendObject(Flux.just(new PingWebSocketFrame(),
		                                                    new TextWebSocketFrame("data"))).then())
		      .as(StepVerifier::create)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertDistributionSummary(registry, WS_SERVER_DATA_RECEIVED, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalAmountEqualTo(4);

		           // The server sends only Pong and Close frames, so the meter may not be registered at all
		           DistributionSummary dataSent = registry.find(WS_SERVER_DATA_SENT).tag(URI, "/ws").summary();
		           assertThat(dataSent == null ? 0 : dataSent.count()).isZero();
		       });
	}

	void doTestServerWebSocketFragmentedDataReceivedMicrometer(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) -> res.sendWebsocket((in, out) ->
				              in.aggregateFrames().receiveFrames().take(1).then()))
				      .bindNow();

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> out.sendObject(Flux.just(new TextWebSocketFrame(false, 0, "hello"),
		                                                    new ContinuationWebSocketFrame(true, 0, " world"))).then())
		      .as(StepVerifier::create)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertDistributionSummary(registry, WS_SERVER_DATA_RECEIVED, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalAmountEqualTo(11);

		           assertTimer(registry, WS_SERVER_DATA_RECEIVED_TIME, URI, "/ws")
		                   .hasCountEqualTo(1)
		                   .hasTotalTimeGreaterThan(0);
		       });
	}

	void doTestServerWebSocketErrorsOnApplicationErrorMicrometer(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((req, res) -> res.sendWebsocket((in, out) ->
				              out.sendString(Flux.error(new RuntimeException("boom")))))
				      .bindNow();

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.receiveFrames().then())
		      .then()
		      .onErrorResume(e -> Mono.empty())
		      .as(StepVerifier::create)
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() ->
		               assertCounter(registry, WS_SERVER_ERRORS, URI, "/ws")
		                       .hasCountEqualTo(1));
	}

	void doTestServerWebSocketCustomRecorder(HttpServer server, HttpClient client) {
		ServerWebSocketRecorder recorder = new ServerWebSocketRecorder();
		disposableServer =
				server.forwarded(true, false)
				      .metrics(true, () -> recorder, Function.identity(), m -> "m_" + m)
				      .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				      .bindNow();

		sendWebSocketRequest(client, "192.0.2.1");

		String method = "m_" + handshakeMethod();
		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertThat(recorder.handshake).containsExactly("192.0.2.1 /ws " + handshakeStatus());
		           assertThat(recorder.connectionDuration).containsExactly("192.0.2.1 /ws");
		           assertThat(recorder.dataSent).containsExactly("192.0.2.1 /ws 12");
		           assertThat(recorder.dataSentTime).containsExactly("/ws " + method + " n/a");
		       });
	}

	void doTestServerWebSocketHttpRecorder(HttpServer server, HttpClient client) {
		ServerHttpRecorder recorder = new ServerHttpRecorder();
		disposableServer =
				server.metrics(true, () -> recorder, Function.identity())
				      .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				      .bindNow();

		sendWebSocketRequest(client, null);

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertThat(recorder.dataSent).containsExactly("127.0.0.1 /ws 12");
		           assertThat(recorder.dataSentTime).containsExactly("/ws " + handshakeMethod() + " n/a");
		       });
	}

	void doTestServerWebSocketContextAwareRecorder(HttpServer server, HttpClient client) {
		ServerContextAwareWebSocketRecorder recorder = new ServerContextAwareWebSocketRecorder();
		disposableServer =
				server.metrics(true, () -> recorder, Function.identity())
				      .handle((req, res) -> res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!"))))
				      .mapHandle((mono, conn) -> mono.contextWrite(Context.of("testContextAwareRecorder", "OK")))
				      .bindNow();

		sendWebSocketRequest(client, null);

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertThat(recorder.handshake).containsExactly("OK");
		           assertThat(recorder.connectionDuration).containsExactly("OK");
		           assertThat(recorder.dataSent).containsExactly("OK");
		       });
	}

	void doTestServerWebSocketContextAwareRecorderAfterHandshake(HttpServer server, HttpClient client) {
		ServerContextAwareWebSocketRecorder recorder = new ServerContextAwareWebSocketRecorder();
		Sinks.Empty<Void> upgraded = Sinks.empty();
		disposableServer =
				server.metrics(true, () -> recorder, Function.identity())
				      .handle((req, res) -> {
				          Mono<Void> upgrade = res.sendWebsocket((in, out) -> out.sendString(Mono.just("Hello World!")));
				          // The client must finish the handshake before the server subscribes to the upgrade.
				          return upgraded.asMono().then(upgrade);
				      })
				      .mapHandle((mono, conn) -> mono.contextWrite(Context.of("testContextAwareRecorder", "OK")))
				      .bindNow();

		client.websocket()
		      .uri("/ws")
		      .handle((in, out) -> {
		          upgraded.tryEmitEmpty();
		          return in.receive().aggregate().asString();
		      })
		      .as(StepVerifier::create)
		      .expectNext("Hello World!")
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));

		await().atMost(5, TimeUnit.SECONDS)
		       .pollInterval(50, TimeUnit.MILLISECONDS)
		       .untilAsserted(() -> {
		           assertThat(recorder.handshake).containsExactly("OK");
		           assertThat(recorder.connectionDuration).containsExactly("OK");
		           assertThat(recorder.dataSent).containsExactly("OK");
		       });
	}

	void doTestServerWebSocketContextAwareRecorderAfterHandshakeOffEventLoop(HttpServer server, HttpClient client)
			throws Exception {
		ServerContextAwareWebSocketRecorder recorder = new ServerContextAwareWebSocketRecorder();
		AtomicReference<Mono<Void>> upgrade = new AtomicReference<>();
		AtomicReference<Channel> serverChannel = new AtomicReference<>();
		AtomicReference<List<String>> handshakeAtHandler = new AtomicReference<>();
		AtomicBoolean releaseEventLoop = new AtomicBoolean();
		CountDownLatch eventLoopOccupied = new CountDownLatch(1);
		disposableServer =
				server.metrics(true, () -> recorder, Function.identity())
				      .handle((req, res) -> {
				          res.withConnection(conn -> serverChannel.set(conn.channel()));
				          upgrade.set(res.sendWebsocket((in, out) -> {
				              handshakeAtHandler.set(new ArrayList<>(recorder.handshake));
				              return Mono.error(new IllegalStateException("Simulated application failure"));
				          }));
				          return Mono.never();
				      })
				      .bindNow();

		Connection connection = client.websocket().uri("/ws").connect().block(Duration.ofSeconds(30));
		assertThat(connection).isNotNull();
		try {
			Channel channel = serverChannel.get();
			// Wait for the server's handshake completion listener before subscribing to the upgrade.
			channel.eventLoop().submit(() -> { }).get(30, TimeUnit.SECONDS);
			Future<?> eventLoopTask = channel.eventLoop().submit(() -> {
				eventLoopOccupied.countDown();
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
				while (!releaseEventLoop.get() && System.nanoTime() < deadline) {
					// Keep the event loop busy until the off-loop subscription returns. Do not use blocking calls.
				}
			});
			assertThat(eventLoopOccupied.await(30, TimeUnit.SECONDS)).isTrue();
			Future<Void> completion;
			try {
				completion = upgrade.get()
				                    .contextWrite(Context.of("testContextAwareRecorder", "OK"))
				                    .toFuture();
			}
			finally {
				releaseEventLoop.set(true);
			}
			eventLoopTask.get(30, TimeUnit.SECONDS);
			completion.get(30, TimeUnit.SECONDS);

			assertThat(handshakeAtHandler.get()).containsExactly("OK");
			assertThat(recorder.handshake).containsExactly("OK");
			assertThat(recorder.errors).containsExactly("OK");
		}
		finally {
			releaseEventLoop.set(true);
			connection.disposeNow();
		}
	}

	static void sendInvalidWebSocketRequest(HttpClient client) {
		// A supported version without Sec-WebSocket-Key fails synchronously inside Netty's handshaker.
		client.disableRetry(true)
		      .observe((conn, state) -> {
		          if (state != ConnectionObserver.State.CONNECTED) {
		              return;
		          }
		          conn.channel().pipeline().addAfter(NettyPipeline.HttpCodec, "doTestUnsupportedWebsocketVersion",
		              new ChannelOutboundHandlerAdapter() {
		                  @Override
		                  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		                      if (msg instanceof HttpRequest) {
		                          HttpRequest request = (HttpRequest) msg;
		                          request.headers().remove(HttpHeaderNames.SEC_WEBSOCKET_KEY);
		                      }
		                      ctx.write(msg, promise);
		                  }
		              });
		      })
		      .websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.aggregateFrames().receive().asString().then())
		      .as(StepVerifier::create)
		      .expectError()
		      .verify(Duration.ofSeconds(30));
	}

	static void sendWebSocketRequest(HttpClient client, @Nullable String forwardedFor) {
		client.headers(h -> {
		          if (forwardedFor != null) {
		              h.set("X-Forwarded-For", forwardedFor);
		          }
		      })
		      .websocket()
		      .uri("/ws")
		      .handle((in, out) -> in.receive().aggregate().asString())
		      .as(StepVerifier::create)
		      .expectNext("Hello World!")
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	String handshakeStatus() {
		return "101";
	}

	String handshakeMethod() {
		return "GET";
	}

	static String host(SocketAddress address) {
		return address instanceof InetSocketAddress ? ((InetSocketAddress) address).getHostString() : address.toString();
	}

	static class ServerHttpRecorder implements HttpServerMetricsRecorder {

		final Queue<String> dataSent = new ConcurrentLinkedQueue<>();
		final Queue<String> dataSentTime = new ConcurrentLinkedQueue<>();

		@Override
		public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
			dataSent.add(host(remoteAddress) + " " + uri + " " + bytes);
		}

		@Override
		public void recordDataSentTime(String uri, String method, String status, Duration time) {
			dataSentTime.add(uri + " " + method + " " + status);
		}

		@Override
		public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		}

		@Override
		public void recordDataReceivedTime(String uri, String method, Duration time) {
		}

		@Override
		public void recordResponseTime(String uri, String method, String status, Duration time) {
		}

		@Override
		public void recordDataReceived(SocketAddress remoteAddress, long bytes) {
		}

		@Override
		public void recordDataSent(SocketAddress remoteAddress, long bytes) {
		}

		@Override
		public void incrementErrorsCount(SocketAddress remoteAddress) {
		}

		@Override
		public void recordTlsHandshakeTime(SocketAddress remoteAddress, Duration time, String status) {
		}

		@Override
		public void recordConnectTime(SocketAddress remoteAddress, Duration time, String status) {
		}

		@Override
		public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		}
	}

	static final class ServerWebSocketRecorder extends ServerHttpRecorder implements WebSocketServerMetricsRecorder {

		final Queue<String> handshake = new ConcurrentLinkedQueue<>();
		final Queue<String> connectionDuration = new ConcurrentLinkedQueue<>();

		@Override
		public void recordWebSocketHandshakeTime(SocketAddress remoteAddress, String uri, String status, Duration time) {
			handshake.add(host(remoteAddress) + " " + uri + " " + status);
		}

		@Override
		public void recordWebSocketConnectionDuration(SocketAddress remoteAddress, String uri, Duration time) {
			connectionDuration.add(host(remoteAddress) + " " + uri);
		}
	}

	static final class ServerContextAwareWebSocketRecorder extends ContextAwareWebSocketServerMetricsRecorder {

		final Queue<String> handshake = new ConcurrentLinkedQueue<>();
		final Queue<String> connectionDuration = new ConcurrentLinkedQueue<>();
		final Queue<String> dataSent = new ConcurrentLinkedQueue<>();
		final Queue<String> errors = new ConcurrentLinkedQueue<>();

		boolean failHandshakeRecording;
		boolean failConnectionDurationRecording;

		@Override
		public void recordWebSocketHandshakeTime(ContextView contextView, SocketAddress remoteAddress, String uri,
				String status, Duration time) {
			handshake.add(contextView.getOrDefault("testContextAwareRecorder", "KO"));
			if (failHandshakeRecording) {
				throw new IllegalStateException("Simulated handshake recorder failure");
			}
		}

		@Override
		public void recordWebSocketConnectionDuration(ContextView contextView, SocketAddress remoteAddress,
				String uri, Duration time) {
			connectionDuration.add(contextView.getOrDefault("testContextAwareRecorder", "KO"));
			if (failConnectionDurationRecording) {
				throw new IllegalStateException("Simulated connection duration recorder failure");
			}
		}

		@Override
		public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
			dataSent.add(contextView.getOrDefault("testContextAwareRecorder", "KO"));
		}

		@Override
		public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress, String uri) {
			errors.add(contextView.getOrDefault("testContextAwareRecorder", "KO"));
		}

		@Override
		public void recordDataReceivedTime(ContextView contextView, String uri, String method, Duration time) {
		}

		@Override
		public void recordDataSentTime(ContextView contextView, String uri, String method, String status, Duration time) {
		}

		@Override
		public void recordResponseTime(ContextView contextView, String uri, String method, String status, Duration time) {
		}

		@Override
		public void incrementErrorsCount(ContextView contextView, SocketAddress remoteAddress) {
		}

		@Override
		public void recordConnectTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status) {
		}

		@Override
		public void recordDataReceived(ContextView contextView, SocketAddress remoteAddress, long bytes) {
		}

		@Override
		public void recordDataSent(ContextView contextView, SocketAddress remoteAddress, long bytes) {
		}

		@Override
		public void recordTlsHandshakeTime(ContextView contextView, SocketAddress remoteAddress, Duration time, String status) {
		}

		@Override
		public void recordResolveAddressTime(SocketAddress remoteAddress, Duration time, String status) {
		}
	}
}
