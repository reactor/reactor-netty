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

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Deque;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;

import io.micrometer.api.instrument.MeterRegistry;
import io.micrometer.api.instrument.Metrics;
import io.micrometer.api.instrument.observation.ObservationHandler;
import io.micrometer.api.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.SampleTestRunner;
import io.micrometer.tracing.test.reporter.BuildingBlocks;
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

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
class ObservabilitySmokeTest extends SampleTestRunner {
	static final io.micrometer.api.instrument.MeterRegistry REGISTRY = io.micrometer.api.instrument.Metrics.globalRegistry;
	static final SimpleMeterRegistry simpleMeterRegistry = new SimpleMeterRegistry();

	static SelfSignedCertificate ssc;

	ObservabilitySmokeTest() {
		super(SampleTestRunner.SampleRunnerConfig.builder().build(), REGISTRY);
	}

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
		Metrics.addRegistry(simpleMeterRegistry);
	}

	@AfterEach
	void setupRegistry() {
		simpleMeterRegistry.clear();
	}

	@AfterAll
	static void clean() {
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
	public BiConsumer<Tracer, MeterRegistry> yourCode() {
		byte[] bytes = new byte[1024 * 8];
		Random rndm = new Random();
		rndm.nextBytes(bytes);

		return (tracer, meterRegistry) -> {
			Http11SslContextSpec serverCtxHttp11 = Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
			Http11SslContextSpec clientCtxHttp11 =
					Http11SslContextSpec.forClient()
					                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

			HttpClient client =
					HttpClient.create()
					          .wiretap(true)
					          .metrics(true, Function.identity())
					          .secure(spec -> spec.sslContext(clientCtxHttp11));

			// Make a test to localhost
			DisposableServer server =
					HttpServer.create()
					          .wiretap(true)
					          .metrics(true, Function.identity())
					          .secure(spec -> spec.sslContext(serverCtxHttp11))
					          .route(r -> r.post("/post", (req, res) -> res.send(req.receive().retain())))
					          .bindNow();

			String content = new String(bytes);
			String response =
					client.port(server.port())
					      .host("localhost")
					      .post()
					      .uri("/post")
					      .send(ByteBufMono.fromString(Mono.just(content)))
					      .responseContent()
					      .aggregate()
					      .asString()
					      .block(Duration.ofSeconds(5));

			assertThat(response).isEqualTo(content);

			client.secure()
			      .post()
			      .uri("https://httpbin.org/post")
			      .send(ByteBufMono.fromString(Mono.just(content)))
			      .responseContent()
			      .aggregate()
			      .asString()
			      .block(Duration.ofSeconds(5));
		};
	}
}
