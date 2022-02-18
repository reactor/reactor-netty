/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.nio.charset.Charset;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Deque;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.api.instrument.Metrics;
import io.micrometer.api.instrument.observation.ObservationHandler;
import io.micrometer.api.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.test.SampleTestRunner;
import io.micrometer.tracing.test.reporter.BuildingBlocks;
import io.micrometer.tracing.test.simple.SpansAssert;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.observability.ReactorNettyTracingObservationHandler;
import reactor.test.StepVerifier;

import static reactor.netty.Metrics.REGISTRY;

@SuppressWarnings("rawtypes")
class ObservabilitySmokeTest extends SampleTestRunner {
	static final SimpleMeterRegistry simpleMeterRegistry = new SimpleMeterRegistry();

	static String content;
	static DisposableServer disposableServer;
	static SelfSignedCertificate ssc;

	ObservabilitySmokeTest() {
		super(SampleTestRunner.SampleRunnerConfig.builder().build(), REGISTRY);
	}

	@BeforeAll
	static void setUp() throws CertificateException {
		ssc = new SelfSignedCertificate();

		byte[] bytes = new byte[1024 * 8];
		Random rndm = new Random();
		rndm.nextBytes(bytes);
		content = new String(bytes, Charset.defaultCharset());

		Metrics.addRegistry(simpleMeterRegistry);
	}

	@AfterEach
	void setupRegistry() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}

		simpleMeterRegistry.clear();
	}

	@AfterAll
	static void tearDown() {
		Metrics.removeRegistry(simpleMeterRegistry);
	}

	@Override
	public BiConsumer<BuildingBlocks, Deque<ObservationHandler>> customizeObservationHandlers() {
		return (bb, timerRecordingHandlers) -> {
			ObservationHandler defaultHandler = timerRecordingHandlers.removeLast();
			timerRecordingHandlers.addLast(new ReactorNettyTracingObservationHandler(bb.getTracer()));
			timerRecordingHandlers.addLast(defaultHandler);
			timerRecordingHandlers.addFirst(new ReactorNettyHttpClientTracingObservationHandler(bb.getTracer(), bb.getHttpClientHandler()));
			timerRecordingHandlers.addFirst(new ReactorNettyHttpServerTracingObservationHandler(bb.getTracer(), bb.getHttpServerHandler()));
		};
	}

	@Override
	public TracingSetup[] getTracingSetup() {
		return new TracingSetup[] {TracingSetup.ZIPKIN_BRAVE};
	}

	@Override
	public SampleTestRunnerConsumer yourCode() {
		return (bb, meterRegistry) -> {
			Http11SslContextSpec serverCtxHttp11 = Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
			disposableServer =
					HttpServer.create()
					          .wiretap(true)
					          .metrics(true, Function.identity())
					          .secure(spec -> spec.sslContext(serverCtxHttp11))
					          .route(r -> r.post("/post", (req, res) -> res.send(req.receive().retain())))
					          .bindNow();

			Http11SslContextSpec clientCtxHttp11 =
					Http11SslContextSpec.forClient()
					                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
			HttpClient client =
					HttpClient.create()
					          .port(disposableServer.port())
					          .host("localhost")
					          .wiretap(true)
					          .metrics(true, Function.identity())
					          .secure(spec -> spec.sslContext(clientCtxHttp11));

			client.post()
			      .uri("/post")
			      .send(ByteBufMono.fromString(Mono.just(content)))
			      .responseSingle((res, bytebuf) -> bytebuf.asString())
			      .as(StepVerifier::create)
			      .expectNext(content)
			      .expectComplete()
			      .verify(Duration.ofSeconds(10));

			Span current = bb.getTracer().currentSpan();

			SpansAssert.assertThat(bb.getFinishedSpans().stream().filter(f -> f.getTraceId().equals(current.context().traceId()))
			           .collect(Collectors.toList()))
			           .hasASpanWithName("connect")
			           .hasASpanWithName("tls handshake")
			           .hasASpanWithName("POST");
		};
	}
}