/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.After;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds HTTP/2 specific tests.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public class Http2Tests {
	private DisposableServer disposableServer;

	@After
	public void tearDown() {
		if (disposableServer != null) {
			disposableServer.disposeNow();
		}
	}

	@Test
	public void testHttpNoSslH2Fails() {
		StepVerifier.create(
		        HttpServer.create()
		                  .protocol(HttpProtocol.H2)
		                  .handle((req, res) -> res.sendString(Mono.just("Hello")))
		                  .wiretap(true)
		                 .bind())
		            .verifyErrorMessage("Configured H2 protocol without TLS. Use" +
		                    " a Clear-Text H2 protocol via HttpServer#protocol or configure TLS" +
		                            " via HttpServer#secure");
	}

	@Test
	public void testHttpSslH2CFails() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverOptions = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());

		StepVerifier.create(
		        HttpServer.create()
		                  .protocol(HttpProtocol.H2C)
		                  .secure(ssl -> ssl.sslContext(serverOptions))
		                  .handle((req, res) -> res.sendString(Mono.just("Hello")))
		                  .wiretap(true)
		                  .bind())
		            .verifyErrorMessage("Configured H2 Clear-Text protocol with TLS. Use" +
		                    " the non Clear-Text H2 protocol via HttpServer#protocol or disable TLS" +
		                            " via HttpServer#noSSL())");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testMaxHttp2ConnectionsNegative() {
		HttpClient.create(ConnectionProvider.create("testMaxHttp2ConnectionsNegative", 1), -1);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testMaxHttp2ConnectionsZero() {
		HttpClient.create(ConnectionProvider.create("testMaxHttp2ConnectionsZero", 1), 0);
	}

	@Test
	public void testCustomConnectionProvider() {
		disposableServer =
				HttpServer.create()
				          .protocol(HttpProtocol.H2C)
				          .route(routes ->
				              routes.post("/echo", (req, res) -> res.send(req.receive().retain())))
				          .port(0)
				          .wiretap(true)
				          .bindNow();

		ConnectionProvider provider = ConnectionProvider.create("testCustomConnectionProvider", 1);
		String response =
				HttpClient.create(provider, 1)
				          .port(disposableServer.port())
				          .protocol(HttpProtocol.H2C)
				          .wiretap(true)
				          .post()
				          .uri("/echo")
				          .send(ByteBufFlux.fromString(Mono.just("testCustomConnectionProvider")))
				          .responseContent()
				          .aggregate()
				          .asString()
				          .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("testCustomConnectionProvider");

		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@Test
	public void testIssue1071MaxContentLengthSpecified() {
		doTestIssue1071(1024, "doTestIssue1071", 200);
	}

	@Test
	public void testIssue1071MaxContentLengthNotSpecified() {
		doTestIssue1071(0, "NO RESPONSE", 413);
	}

	private void doTestIssue1071(int length, String expectedResponse, int expectedCode) {
		disposableServer =
				HttpServer.create()
				          .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
				          .route(routes ->
				              routes.post("/echo", (request, response) -> response.send(request.receive().retain())))
				          .port(8080)
				          .httpRequestDecoder(spec -> spec.h2cMaxContentLength(length))
				          .wiretap(true)
				          .bindNow();

		Tuple2<String, Integer> response =
				HttpClient.create()
				          .port(disposableServer.port())
				          .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
				          .wiretap(true)
				          .post()
				          .uri("/echo")
				          .send(ByteBufFlux.fromString(Mono.just("doTestIssue1071")))
				          .responseSingle((res, bytes) -> bytes.asString().defaultIfEmpty("NO RESPONSE").zipWith(Mono.just(res.status().code())))
				          .block(Duration.ofSeconds(30));

		assertThat(response).isNotNull();
		assertThat(response.getT1()).isEqualTo(expectedResponse);
		assertThat(response.getT2()).isEqualTo(expectedCode);
	}

	@Test
	public void testMaxActiveStreams_1() throws Exception {
		doTestMaxActiveStreams(HttpClient.create(), 1, 1, 1);

		ConnectionProvider provider = ConnectionProvider.create("testMaxActiveStreams_1", 1);
		doTestMaxActiveStreams(HttpClient.create(provider), 1, 1, 1);
		provider.disposeLater()
		        .block();

		doTestMaxActiveStreams(HttpClient.newConnection(), 1, 1, 1);
	}

	@Test
	public void testMaxActiveStreams_2() throws Exception {
		doTestMaxActiveStreams(HttpClient.create(), 2, 2, 0);

		ConnectionProvider provider = ConnectionProvider.create("testMaxActiveStreams_2", 1);
		doTestMaxActiveStreams(HttpClient.create(provider), 2, 2, 0);
		provider.disposeLater()
		        .block();

		doTestMaxActiveStreams(HttpClient.newConnection(), 2, 2, 0);
	}

	public void doTestMaxActiveStreams(HttpClient baseClient, int maxActiveStreams, int expectedOnNext, int expectedOnError) throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContextBuilder clientCtx = SslContextBuilder.forClient()
		                                               .trustManager(InsecureTrustManagerFactory.INSTANCE);
		disposableServer =
				HttpServer.create()
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(serverCtx))
				          .route(routes ->
				              routes.post("/echo", (req, res) -> res.send(req.receive()
				                                                             .aggregate()
				                                                             .retain()
				                                                             .delayElement(Duration.ofMillis(100)))))
				          .port(0)
				          .http2Settings(setting -> setting.maxConcurrentStreams(maxActiveStreams))
				          .wiretap(true)
				          .bindNow();

		HttpClient client =
				baseClient.port(disposableServer.port())
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(clientCtx))
				          .wiretap(true);

		CountDownLatch latch = new CountDownLatch(1);
		List<? extends Signal<? extends String>> list =
				Flux.range(0, 2)
				    .flatMapDelayError(i ->
				            client.post()
				                  .uri("/echo")
				                  .send(ByteBufFlux.fromString(Mono.just("doTestMaxActiveStreams")))
				                  .responseContent()
				                  .aggregate()
				                  .asString()
				                  .materialize(),
				    256, 32)
				    .collectList()
				    .doFinally(fin -> latch.countDown())
				    .block(Duration.ofSeconds(30));

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch 30s").isTrue();

		assertThat(list).isNotNull().hasSize(2);

		int onNext = 0;
		int onError = 0;
		String msg = "Max active streams is reached";
		for (int i = 0; i < 2; i++) {
			Signal<? extends String> signal = list.get(i);
			if (signal.isOnNext()) {
				onNext++;
			}
			else if (signal.getThrowable() instanceof IOException &&
					signal.getThrowable().getMessage().contains(msg)) {
				onError++;
			}
		}

		assertThat(onNext).isEqualTo(expectedOnNext);
		assertThat(onError).isEqualTo(expectedOnError);
	}

	@Test
	public void testConcurrentStreamsH2() throws Exception {
		doTestConcurrentStreams(HttpClient.create(), true, HttpProtocol.H2);

		ConnectionProvider provider = ConnectionProvider.create("testConcurrentStreams", 1);
		doTestConcurrentStreams(HttpClient.create(provider), true, HttpProtocol.H2);
		provider.disposeLater()
		        .block();

		doTestConcurrentStreams(HttpClient.newConnection(), true, HttpProtocol.H2);
	}

	@Test
	public void testConcurrentStreamsH2C() throws Exception {
		doTestConcurrentStreams(HttpClient.create(), false, HttpProtocol.H2C);

		ConnectionProvider provider = ConnectionProvider.create("testConcurrentStreams", 1);
		doTestConcurrentStreams(HttpClient.create(provider), false, HttpProtocol.H2C);
		provider.disposeLater()
		        .block();

		doTestConcurrentStreams(HttpClient.newConnection(), false, HttpProtocol.H2C);
	}

	@Test
	public void testConcurrentStreamsH2CUpgrade() throws Exception {
		doTestConcurrentStreams(HttpClient.create(), false, HttpProtocol.H2C, HttpProtocol.HTTP11);

		ConnectionProvider provider = ConnectionProvider.create("testConcurrentStreamsH2CUpgrade", 1);
		doTestConcurrentStreams(HttpClient.create(provider), false, HttpProtocol.H2C, HttpProtocol.HTTP11);
		provider.disposeLater()
		        .block();

		doTestConcurrentStreams(HttpClient.newConnection(), false, HttpProtocol.H2C, HttpProtocol.HTTP11);
	}

	@Test
	public void testConcurrentStreamsNegotiatedProtocolHTTP11() throws Exception {
		doTestConcurrentStreams(HttpClient.create(), true, new HttpProtocol[]{HttpProtocol.HTTP11},
				new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11});

		ConnectionProvider provider = ConnectionProvider.create("testConcurrentStreamsH2CUpgrade", 1);
		doTestConcurrentStreams(HttpClient.create(provider), true, new HttpProtocol[]{HttpProtocol.HTTP11},
				new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11});
		provider.disposeLater()
		        .block();

		doTestConcurrentStreams(HttpClient.newConnection(), true, new HttpProtocol[]{HttpProtocol.HTTP11},
				new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11});
	}

	@Test
	public void testConcurrentStreamsNegotiatedProtocolH2() throws Exception {
		doTestConcurrentStreams(HttpClient.create(), true, new HttpProtocol[]{HttpProtocol.H2},
				new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11});

		ConnectionProvider provider = ConnectionProvider.create("testConcurrentStreams", 1);
		doTestConcurrentStreams(HttpClient.create(provider), true, new HttpProtocol[]{HttpProtocol.H2},
				new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11});
		provider.disposeLater()
		        .block();

		doTestConcurrentStreams(HttpClient.newConnection(), true, new HttpProtocol[]{HttpProtocol.H2},
				new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11});
	}

	private void doTestConcurrentStreams(HttpClient baseClient, boolean isSecured, HttpProtocol... protocols) throws Exception {
		doTestConcurrentStreams(baseClient, isSecured, protocols, protocols);
	}

	private void doTestConcurrentStreams(HttpClient baseClient, boolean isSecured,
			HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContextBuilder clientCtx = SslContextBuilder.forClient()
		                                               .trustManager(InsecureTrustManagerFactory.INSTANCE);

		HttpServer httpServer =
				HttpServer.create()
				          .port(0)
				          .protocol(serverProtocols).wiretap(true)
				          .handle((req, res) -> res.sendString(Mono.just("test")));
		if (isSecured) {
			httpServer = httpServer.secure(spec -> spec.sslContext(serverCtx));
		}

		disposableServer = httpServer.bindNow();

		HttpClient client;
		if (isSecured) {
			client = baseClient.port(disposableServer.port()).wiretap(true)
			                   .protocol(clientProtocols)
			                   .secure(spec -> spec.sslContext(clientCtx));
		}
		else {
			client = baseClient.port(disposableServer.port())
			                   .protocol(clientProtocols);
		}

		CountDownLatch latch = new CountDownLatch(1);
		List<String> responses =
				Flux.range(0, 10)
				    .flatMapDelayError(i ->
				        client.get()
				              .uri("/")
				              .responseContent()
				              .aggregate()
				              .asString(),
				        256, 32)
				        .collectList()
				        .doFinally(fin -> latch.countDown())
				        .block(Duration.ofSeconds(30));

		assertThat(responses).isNotNull();
		assertThat(responses.size()).isEqualTo(10);
	}

	@Test
	public void testHttp2ForMemoryLeaks() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContextBuilder clientCtx = SslContextBuilder.forClient()
		                                               .trustManager(InsecureTrustManagerFactory.INSTANCE);

		disposableServer =
				HttpServer.create()
				          .port(0)
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(serverCtx))
				          .handle((req, res) ->
				              res.sendString(Flux.range(0, 10)
				                                 .map(i -> "test")
				                                 .delayElements(Duration.ofMillis(4))))
				          .bindNow();


		HttpClient client =
				HttpClient.create()
				          .port(disposableServer.port())
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(clientCtx));
		for(int i = 0; i < 1000; ++i) {
			try {
				client.get()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .timeout(Duration.ofMillis(ThreadLocalRandom.current().nextInt(1, 35)))
				      .block(Duration.ofMillis(100));
			}
			catch (Throwable t) {
				// ignore
			}
		}

		System.gc();
		for (int i = 0; i < 100000; ++i) {
			@SuppressWarnings("UnusedVariable")
			int[] arr = new int[100000];
		}
		System.gc();
	}

	@Test
	public void testHttpClientDefaultSslProvider() {
		HttpClient client = HttpClient.create()
		                              .wiretap(true);

		doTestHttpClientDefaultSslProvider(client.protocol(HttpProtocol.H2));
		doTestHttpClientDefaultSslProvider(client.protocol(HttpProtocol.H2)
		                               .secure());
		doTestHttpClientDefaultSslProvider(client.secure()
		                               .protocol(HttpProtocol.H2));
		doTestHttpClientDefaultSslProvider(client.protocol(HttpProtocol.HTTP11)
		                               .secure()
		                               .protocol(HttpProtocol.H2));
	}

	private void doTestHttpClientDefaultSslProvider(HttpClient client) {
		AtomicBoolean channel = new AtomicBoolean();
		StepVerifier.create(client.doOnRequest((req, conn) -> channel.set(conn.channel().parent() != null))
		                          .get()
		                          .uri("https://example.com/")
		                          .responseContent()
		                          .aggregate()
		                          .asString())
		            .expectNextMatches(s -> s.contains("Example Domain"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		assertThat(channel.get()).isTrue();
	}
}
