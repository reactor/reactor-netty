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
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.test.SampleTestRunner;
import io.micrometer.tracing.test.reporter.BuildingBlocks;
import io.micrometer.tracing.test.simple.SpansAssert;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
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
import static reactor.netty.Metrics.REGISTRY;

@SuppressWarnings("rawtypes")
class ObservabilitySmokeTest extends SampleTestRunner {
	static String content;
	static DisposableServer disposableServer;
	static SelfSignedCertificate ssc;

	ObservabilitySmokeTest() {
		super(SampleTestRunner.SampleRunnerConfig.builder().build(), OBSERVATION_REGISTRY, REGISTRY);
	}

	@BeforeAll
	static void setUp() throws CertificateException {
		ssc = new SelfSignedCertificate();

		byte[] bytes = new byte[1024 * 8];
		Random rndm = new Random();
		rndm.nextBytes(bytes);
		content = new String(bytes, Charset.defaultCharset());
	}

	@AfterEach
	void cleanRegistry() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
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
	public SampleTestRunnerConsumer yourCode() {
		return (bb, meterRegistry) -> {
			Http2SslContextSpec serverCtxHttp = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
			disposableServer =
					HttpServer.create()
					          .metrics(true, Function.identity())
					          .secure(spec -> spec.sslContext(serverCtxHttp))
					          .protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
					          .route(r -> r.post("/post", (req, res) -> res.send(req.receive().retain())))
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
			           .hasASpanWithName("hostname resolution")
			           .hasASpanWithName("connect")
			           .hasASpanWithName("tls handshake")
			           .hasASpanWithName("POST");
		};
	}

	static void sendHttp2Request(HttpClient client) {
		Http2SslContextSpec clientCtxHttp2 =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		sendRequest(client.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.H2));
	}

	static void sendHttp11Request(HttpClient client) {
		Http11SslContextSpec clientCtxHttp11 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		sendRequest(client.secure(spec -> spec.sslContext(clientCtxHttp11)));
	}

	static void sendRequest(HttpClient client) {
		HttpClient localClient =
				client.port(disposableServer.port())
				      .host("localhost")
				      .metrics(true, Function.identity());

		List<String> responses =
				Flux.range(0, 2)
				    .flatMap(i ->
				        localClient.post()
				                   .uri("/post")
				                   .send(ByteBufMono.fromString(Mono.just(content)))
				                   .responseSingle((res, byteBuf) -> byteBuf.asString()))
				    .collectList()
				    .block(Duration.ofSeconds(10));

		assertThat(responses).isEqualTo(Arrays.asList(content, content));
	}
}