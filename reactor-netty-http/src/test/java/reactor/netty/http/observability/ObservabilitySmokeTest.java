/*
 * Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.observability;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.test.SampleTestRunner;
import io.micrometer.tracing.test.reporter.BuildingBlocks;
import io.micrometer.tracing.test.simple.SpansAssert;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.observability.ReactorNettyTracingObservationHandler;
import reactor.netty.resources.ConnectionProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.Metrics.OBSERVATION_REGISTRY;
import static reactor.netty.ReactorNetty.getChannelContext;

class ObservabilitySmokeTest extends SampleTestRunner {
	static byte[] content;
	static DisposableServer disposableServer;
	static SelfSignedCertificate ssc;

	static MeterRegistry registry;

	ObservabilitySmokeTest() {
		super(SampleTestRunner.SampleRunnerConfig.builder().build());
	}

	@BeforeAll
	static void setUp() throws CertificateException {
		ssc = new SelfSignedCertificate();
		content = new byte[1024 * 8];
		Random rndm = new Random();
		rndm.nextBytes(content);
	}

	@Override
	protected MeterRegistry createMeterRegistry() {
		registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
		return registry;
	}

	@Override
	protected ObservationRegistry createObservationRegistry() {
		return OBSERVATION_REGISTRY;
	}

	@AfterEach
	void cleanRegistry() {
		Metrics.removeRegistry(registry);
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Override
	public BiConsumer<BuildingBlocks, Deque<ObservationHandler<? extends Observation.Context>>> customizeObservationHandlers() {
		return (bb, timerRecordingHandlers) -> {
			ObservationHandler<? extends Observation.Context> defaultHandler = timerRecordingHandlers.removeLast();
			timerRecordingHandlers.addLast(new ReactorNettyTracingObservationHandler(bb.getTracer()));
			timerRecordingHandlers.addLast(defaultHandler);
			timerRecordingHandlers.addFirst(new ReactorNettyPropagatingSenderTracingObservationHandler(bb.getTracer(), bb.getPropagator()));
			timerRecordingHandlers.addFirst(new ReactorNettyPropagatingReceiverTracingObservationHandler(bb.getTracer(), bb.getPropagator()));
		};
	}

	@Override
	@SuppressWarnings("deprecation")
	public SampleTestRunnerConsumer yourCode() {
		return (bb, meterRegistry) -> {
			Http2SslContextSpec serverCtxHttp = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
			disposableServer =
					HttpServer.create()
					          .metrics(true, s -> s.replace("1", "{id}"))
					          .secure(spec -> spec.sslContext(serverCtxHttp))
					          .protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
					          .route(r -> r.post("/post/{id}", (req, res) -> res.send(req.receive().retain())))
					          .bindNow();

			HttpClient client;

			// Default connection pool
			client = HttpClient.create();
			sendHttp11Request(client);
			sendHttp2Request(client);

			// Disabled connection pool
			client = HttpClient.newConnection();
			sendHttp11Request(client);
			sendHttp2Request(client);

			// Custom connection pool
			ConnectionProvider provider = ConnectionProvider.create("observability", 1);
			try {
				client = HttpClient.create(provider);
				sendHttp11Request(client);
				sendHttp2Request(client);
			}
			finally {
				provider.disposeLater()
				        .block(Duration.ofSeconds(5));
			}

			Span current = bb.getTracer().currentSpan();
			assertThat(current).isNotNull();

			SpansAssert.assertThat(bb.getFinishedSpans().stream().filter(f -> f.getTraceId().equals(current.context().traceId()))
			           .collect(Collectors.toList()))
			           .hasASpanWithName("hostname resolution",
			                   spanAssert -> spanAssert.hasTagWithKey("net.peer.name")
			                                           .hasTagWithKey("net.peer.port")
			                                           .hasTagWithKey("reactor.netty.protocol")
			                                           .hasTagWithKey("reactor.netty.status")
			                                           .hasTagWithKey("reactor.netty.type"))
			           .hasASpanWithName("connect",
			                   spanAssert -> spanAssert.hasTagWithKey("net.peer.name")
			                                           .hasTagWithKey("net.peer.port")
			                                           .hasTagWithKey("reactor.netty.protocol")
			                                           .hasTagWithKey("reactor.netty.status")
			                                           .hasTagWithKey("reactor.netty.type"))
			           .hasASpanWithName("tls handshake",
			                   spanAssert -> spanAssert.hasTagWithKey("net.peer.name")
			                                           .hasTagWithKey("net.peer.port")
			                                           .hasTagWithKey("reactor.netty.protocol")
			                                           .hasTagWithKey("reactor.netty.status")
			                                           .hasTagWithKey("reactor.netty.type"))
			           .hasASpanWithName("http POST",
			                   spanAssert -> spanAssert.hasTagWithKey("http.status_code")
			                                           .hasTagWithKey("http.url")
			                                           .hasTagWithKey("net.peer.name")
			                                           .hasTagWithKey("net.peer.port")
			                                           .hasTagWithKey("reactor.netty.type"))
			           .hasASpanWithName("POST_post/{id}",
			                   spanAssert -> spanAssert.hasTagWithKey("http.scheme")
			                                           .hasTagWithKey("http.status_code")
			                                           .hasTagWithKey("net.host.name")
			                                           .hasTagWithKey("net.host.port")
			                                           .hasTagWithKey("reactor.netty.type"));
		};
	}

	@SuppressWarnings("deprecation")
	static void sendHttp2Request(HttpClient client) throws Exception {
		Http2SslContextSpec clientCtxHttp2 =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		sendRequest(client.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.H2));
	}

	@SuppressWarnings("deprecation")
	static void sendHttp11Request(HttpClient client) throws Exception {
		Http11SslContextSpec clientCtxHttp11 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		sendRequest(client.secure(spec -> spec.sslContext(clientCtxHttp11)));
	}

	static void sendRequest(HttpClient client) throws Exception {
		AtomicBoolean contextEqual = new AtomicBoolean(true);
		CountDownLatch latch = new CountDownLatch(2);
		HttpClient localClient =
				client.port(disposableServer.port())
				      .host("localhost")
				      .metrics(true, s -> s.replace("1", "{id}"))
				      .doAfterResponseSuccess((res, conn) -> {
				          if (!(conn.channel() instanceof Http2StreamChannel)) {
				              contextEqual.set(res.currentContextView() == getChannelContext(conn.channel()));
				          }
				          latch.countDown();
				      });

		List<byte[]> responses =
				Flux.range(0, 2)
				    .flatMap(i ->
				        localClient.post()
				                   .uri("/post/1")
				                   .send(Mono.just(Unpooled.wrappedBuffer(content)))
				                   .responseSingle((res, byteBuf) -> byteBuf.asByteArray()))
				    .collectList()
				    .block(Duration.ofSeconds(10));

		assertThat(responses).isNotNull().hasSize(2);
		assertThat(responses.get(0)).isEqualTo(content);
		assertThat(responses.get(1)).isEqualTo(content);
		assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(contextEqual).isTrue();
	}
}