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
package reactor.netty;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ThreadLocalAccessor;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.ReactorNetty.getChannelContext;

/**
 * Deterministic coverage for the client response-time Observation bypass. Runs in the
 * {@code contextPropagationTest} JVM, which never customizes the default {@link ObservationRegistry}, so the
 * bypass is reliably active — unlike the shared {@code test} JVM, where {@code ObservabilitySmokeTest}
 * permanently customizes it and the path taken would be class-order dependent.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricsObservationBypassTest {

	static final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	DisposableServer server;

	@BeforeAll
	static void addRegistry() {
		// reactor-netty's REGISTRY is the global composite; without a child, record() fans to nothing.
		io.micrometer.core.instrument.Metrics.addRegistry(meterRegistry);
	}

	@AfterAll
	static void removeRegistry() {
		io.micrometer.core.instrument.Metrics.removeRegistry(meterRegistry);
		meterRegistry.close();
	}

	@BeforeEach
	void bindServer() {
		server = HttpServer.create()
		                   .port(0)
		                   .handle((in, out) -> out.send(in.receive().retain()))
		                   .bindNow();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.disposeNow();
		}
	}

	@Test
	@Order(1)
	void metricsOnlyRecordsResponseTimeAndLeavesNoObservationInChannelContext() {
		assertThat(Metrics.observationLifecycleRequired())
				.as("pristine default registry engages the bypass")
				.isFalse();

		AtomicReference<ContextView> channelContext = new AtomicReference<>();

		String response =
				HttpClient.create()
				          .port(server.port())
				          .metrics(true, Function.identity())
				          .post()
				          .uri("/bypass")
				          .send((req, out) -> out.withConnection(conn -> conn.addHandlerLast(
				                          new ChannelContextAfterReadCapturingHandler(channelContext)))
				                                 .sendString(Mono.just("hello")))
				          .responseContent()
				          .aggregate()
				          .asString()
				          .block(Duration.ofSeconds(10));

		assertThat(response).isEqualTo("hello");

		Timer responseTime =
				meterRegistry.find("reactor.netty.http.client.response.time")
				             .tag("uri", "/bypass")
				             .tag("method", "POST")
				             .tag("status", "200")
				             .timer();
		assertThat(responseTime).as("response.time recorded on the bypass path").isNotNull();
		assertThat(responseTime.count()).isEqualTo(1);

		// The bypass must not write the response-time observation into the channel context.
		ContextView captured = channelContext.get();
		assertThat(captured == null || !captured.hasKey(Metrics.OBSERVATION_KEY))
				.as("bypass leaves no observation in the channel context")
				.isTrue();
	}

	@Test
	@Order(2)
	void tracingReEngagesWhenAnObservationHandlerIsConfigured() {
		RecordingObservationHandler handler = new RecordingObservationHandler();
		ObservationRegistry traced = ObservationRegistry.create();
		traced.observationConfig().observationHandler(handler);
		// Swap (not in-place customization) so the default registry stays pristine for other tests in this JVM.
		ObservationRegistry previous = Metrics.observationRegistry(traced);
		try {
			assertThat(Metrics.observationLifecycleRequired())
					.as("a swapped-in registry disengages the bypass")
					.isTrue();

			String response =
					HttpClient.create()
					          .port(server.port())
					          .metrics(true, Function.identity())
					          .post()
					          .uri("/traced")
					          .send(ByteBufMono.fromString(Mono.just("hello")))
					          .responseContent()
					          .aggregate()
					          .asString()
					          .block(Duration.ofSeconds(10));

			assertThat(response).isEqualTo("hello");
			assertThat(handler.started).as("Observation lifecycle re-engaged (onStart)").hasPositiveValue();
			assertThat(handler.stopped).as("Observation lifecycle re-engaged (onStop)").hasPositiveValue();
		}
		finally {
			Metrics.observationRegistry(previous);
		}
	}

	@Test
	@Order(3)
	void tracedObservationIsPoppedFromChannelContextByRecordRead() {
		RecordingObservationHandler handler = new RecordingObservationHandler();
		ObservationRegistry traced = ObservationRegistry.create();
		traced.observationConfig().observationHandler(handler);
		ObservationRegistry previous = Metrics.observationRegistry(traced);
		AtomicReference<ContextView> channelContextAfterRecordRead = new AtomicReference<>();
		try {
			assertThat(Metrics.observationLifecycleRequired())
					.as("a swapped-in registry disengages the bypass")
					.isTrue();

			// A bodiless GET: reactor-netty's send-path (MonoSendMany) is what seeds the Reactor subscriber
			// Context with a non-empty entry (its onDiscard hook), which is what makes
			// DefaultPooledConnectionProvider.onNext write a parent context onto the channel at acquire time.
			// Without a body, that Context is genuinely empty, onNext skips the write, and parentContextView
			// (captured in startWrite) is null — the case recordRead must still correctly reset.
			String response =
					HttpClient.create()
					          .port(server.port())
					          .metrics(true, Function.identity())
					          .get()
					          .uri("/traced-pop")
					          .responseConnection((res, conn) -> {
					              conn.addHandlerLast(new ChannelContextAfterReadCapturingHandler(channelContextAfterRecordRead));
					              return conn.inbound().receive().aggregate().asString();
					          })
					          .blockLast(Duration.ofSeconds(10));

			assertThat(response).as("bodiless GET completes normally").isNullOrEmpty();
			assertThat(handler.stopped).as("Observation lifecycle ran").hasPositiveValue();

			// recordRead must pop its own (now-stopped) Observation from the channel context immediately —
			// not rely on DefaultPooledConnectionProvider's later release() cleanup to eventually get to it.
			ContextView captured = channelContextAfterRecordRead.get();
			assertThat(captured == null || !captured.hasKey(Metrics.OBSERVATION_KEY))
					.as("recordRead restores the channel context before the connection is released")
					.isTrue();
		}
		finally {
			Metrics.observationRegistry(previous);
		}
	}

	@Test
	@Order(4)
	void bypassDoesNotEraseAlreadyPresentChannelContext() {
		ContextRegistry registry = ContextRegistry.getInstance();
		registry.registerThreadLocalAccessor(new TestThreadLocalAccessor());
		AtomicReference<String> restoredValue = new AtomicReference<>();
		try {
			assertThat(Metrics.observationLifecycleRequired())
					.as("pristine default registry engages the bypass")
					.isFalse();

			TestThreadLocalHolder.value("propagated");

			String response =
					HttpClient.create()
					          .port(server.port())
					          .metrics(true, Function.identity())
					          // Seeds a non-empty Reactor Context so DefaultPooledConnectionProvider.onNext
					          // writes the propagated snapshot onto the channel at connection-acquire time.
					          .mapConnect(mono -> mono.contextWrite(ctx ->
					                  ContextSnapshot.captureAll(registry).updateContext(ctx)))
					          .post()
					          .uri("/bypass-propagation")
					          .send((req, out) -> out.withConnection(conn -> conn.addHandlerLast(
					                          new PropagationCapturingHandler(restoredValue)))
					                                 .sendString(Mono.just("hello")))
					          .responseContent()
					          .aggregate()
					          .asString()
					          .block(Duration.ofSeconds(10));

			assertThat(response).isEqualTo("hello");
			assertThat(restoredValue.get())
					.as("the bypass path must not erase the channel context DefaultPooledConnectionProvider " +
							"populated from context propagation, even though it never writes to that attribute itself")
					.isEqualTo("propagated");
		}
		finally {
			TestThreadLocalHolder.reset();
			registry.removeThreadLocalAccessor(TestThreadLocalAccessor.KEY);
		}
	}

	static final class ChannelContextAfterReadCapturingHandler extends ChannelInboundHandlerAdapter {
		final AtomicReference<ContextView> captured;

		ChannelContextAfterReadCapturingHandler(AtomicReference<ContextView> captured) {
			this.captured = captured;
		}

		@Override
		public boolean isSharable() {
			return false;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			// AbstractHttpClientMetricsHandler calls recordRead(), then reset(), then fireChannelRead() — all
			// before this handler (added last) sees the message, so this observes exactly what recordRead left
			// in the channel context, before the connection pool's own release() cleanup runs.
			if (msg instanceof LastHttpContent) {
				captured.set(getChannelContext(ctx.channel()));
			}
			ctx.fireChannelRead(msg);
		}
	}

	static final class RecordingObservationHandler implements ObservationHandler<Observation.Context> {
		final AtomicInteger started = new AtomicInteger();
		final AtomicInteger stopped = new AtomicInteger();

		@Override
		public void onStart(Observation.Context context) {
			started.incrementAndGet();
		}

		@Override
		public void onStop(Observation.Context context) {
			stopped.incrementAndGet();
		}

		@Override
		public boolean supportsContext(Observation.Context context) {
			return true;
		}
	}

	static final class PropagationCapturingHandler extends ChannelInboundHandlerAdapter {
		final AtomicReference<String> restored;

		PropagationCapturingHandler(AtomicReference<String> restored) {
			this.restored = restored;
		}

		@Override
		public boolean isSharable() {
			return false;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			// AbstractHttpClientMetricsHandler calls recordRead(), then reset(), then fireChannelRead() — all
			// before this handler (added last) sees the message, so this observes exactly what recordRead left
			// in the channel context.
			if (msg instanceof LastHttpContent) {
				try (ContextSnapshot.Scope scope = ContextSnapshot.setAllThreadLocalsFrom(ctx.channel())) {
					restored.set(TestThreadLocalHolder.value());
				}
			}
			ctx.fireChannelRead(msg);
		}
	}

	static final class TestThreadLocalAccessor implements ThreadLocalAccessor<String> {
		static final String KEY = "metricsObservationBypassTest";

		@Override
		public Object key() {
			return KEY;
		}

		@Override
		public String getValue() {
			return TestThreadLocalHolder.value();
		}

		@Override
		public void setValue(String value) {
			TestThreadLocalHolder.value(value);
		}

		@Override
		public void reset() {
			TestThreadLocalHolder.reset();
		}
	}

	static final class TestThreadLocalHolder {
		static final ThreadLocal<String> holder = new ThreadLocal<>();

		static void reset() {
			holder.remove();
		}

		static String value() {
			return holder.get();
		}

		static void value(String value) {
			holder.set(value);
		}
	}
}
