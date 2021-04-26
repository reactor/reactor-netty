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

import io.netty.buffer.ByteBuf;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider.ProtocolSslContextSpec;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds HTTP/2 specific tests.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
class Http2Tests extends BaseHttpTest {
	private final static String H2_WITHOUT_TLS_SERVER = "Configured H2 protocol without TLS. Use" +
			" a Clear-Text H2 protocol via HttpServer#protocol or configure TLS" +
			" via HttpServer#secure";
	private final static String H2C_WITH_TLS_SERVER = "Configured H2 Clear-Text protocol with TLS. Use" +
			" the non Clear-Text H2 protocol via HttpServer#protocol or disable TLS" +
			" via HttpServer#noSSL())";
	private final static String H2_WITHOUT_TLS_CLIENT = "Configured H2 protocol without TLS. Use H2 Clear-Text " +
			"protocol via HttpClient#protocol or configure TLS via HttpClient#secure";
	private final static String H2C_WITH_TLS_CLIENT = "Configured H2 Clear-Text protocol with TLS. " +
			"Use the non Clear-Text H2 protocol via HttpClient#protocol or disable TLS " +
			"via HttpClient#noSSL()";

	static SelfSignedCertificate ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@Test
	void testHttpNoSslH2Fails() {
		StepVerifier.create(
		        createServer()
		                  .protocol(HttpProtocol.H2)
		                  .handle((req, res) -> res.sendString(Mono.just("Hello")))
		                  .bind())
		            .verifyErrorMessage(H2_WITHOUT_TLS_SERVER);
	}

	@Test
	void testHttpSslH2CFails() {
		Http2SslContextSpec serverOptions = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());

		StepVerifier.create(
		        createServer()
		                  .protocol(HttpProtocol.H2C)
		                  .secure(ssl -> ssl.sslContext(serverOptions))
		                  .handle((req, res) -> res.sendString(Mono.just("Hello")))
		                  .bind())
		            .verifyErrorMessage(H2C_WITH_TLS_SERVER);
	}

	@Test
	void testCustomConnectionProvider() {
		disposableServer =
				createServer()
				          .protocol(HttpProtocol.H2C)
				          .route(routes ->
				              routes.post("/echo", (req, res) -> res.send(req.receive().retain())))
				          .bindNow();

		ConnectionProvider provider = ConnectionProvider.create("testCustomConnectionProvider", 1);
		String response =
				createClient(provider, disposableServer.port())
				          .protocol(HttpProtocol.H2C)
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
	void testIssue1071MaxContentLengthSpecified() {
		doTestIssue1071(1024, "doTestIssue1071", 200);
	}

	@Test
	void testIssue1071MaxContentLengthNotSpecified() {
		doTestIssue1071(0, "NO RESPONSE", 413);
	}

	private void doTestIssue1071(int length, String expectedResponse, int expectedCode) {
		disposableServer =
				createServer()
				          .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
				          .route(routes ->
				              routes.post("/echo", (request, response) -> response.send(request.receive().retain())))
				          .httpRequestDecoder(spec -> spec.h2cMaxContentLength(length))
				          .bindNow();

		Tuple2<String, Integer> response =
				createClient(disposableServer.port())
				          .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
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
	void testMaxActiveStreams_1_CustomPool() throws Exception {
		ConnectionProvider provider = ConnectionProvider.create("testMaxActiveStreams_1", 1);
		doTestMaxActiveStreams(HttpClient.create(provider), 1, 1, 1);
		provider.disposeLater()
		        .block();
	}

	@Test
	void testMaxActiveStreams_1_NoPool() throws Exception {
		doTestMaxActiveStreams(HttpClient.newConnection(), 1, 1, 1);
	}

	@Test
	void testMaxActiveStreams_2_DefaultPool() throws Exception {
		doTestMaxActiveStreams(HttpClient.create(), 2, 2, 0);
	}

	@Test
	void testMaxActiveStreams_2_CustomPool() throws Exception {
		ConnectionProvider provider = ConnectionProvider.create("testMaxActiveStreams_2", 1);
		doTestMaxActiveStreams(HttpClient.create(provider), 2, 2, 0);
		provider.disposeLater()
		        .block();
	}

	@Test
	void testMaxActiveStreams_2_NoPool() throws Exception {
		doTestMaxActiveStreams(HttpClient.newConnection(), 2, 2, 0);
	}

	void doTestMaxActiveStreams(HttpClient baseClient, int maxActiveStreams, int expectedOnNext, int expectedOnError) throws Exception {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		disposableServer =
				createServer()
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(serverCtx))
				          .route(routes ->
				              routes.post("/echo", (req, res) -> res.send(req.receive()
				                                                             .aggregate()
				                                                             .retain()
				                                                             .delayElement(Duration.ofMillis(100)))))
				          .http2Settings(setting -> setting.maxConcurrentStreams(maxActiveStreams))
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
	void testHttp2ForMemoryLeaks() {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

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
		for (int i = 0; i < 1000; ++i) {
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
	void testHttpClientDefaultSslProvider() {
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

	@Test
	void testMonoRequestBodySentAsFullRequest_Flux() {
		// sends the message and then last http content
		doTestMonoRequestBodySentAsFullRequest(ByteBufFlux.fromString(Mono.just("test")), 2);
	}

	@Test
	void testMonoRequestBodySentAsFullRequest_Mono() {
		// sends "full" request
		doTestMonoRequestBodySentAsFullRequest(ByteBufMono.fromString(Mono.just("test")), 1);
	}

	private void doTestMonoRequestBodySentAsFullRequest(Publisher<? extends ByteBuf> body, int expectedMsg) {
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		AtomicInteger counter = new AtomicInteger();
		disposableServer =
				createServer()
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(serverCtx))
				          .handle((req, res) -> req.receiveContent()
				                                   .doOnNext(httpContent -> counter.getAndIncrement())
				                                   .then(res.send()))
				          .bindNow(Duration.ofSeconds(30));

		createClient(disposableServer.port())
		          .protocol(HttpProtocol.H2)
		          .secure(spec -> spec.sslContext(clientCtx))
		          .post()
		          .uri("/")
		          .send(body)
		          .responseContent()
		          .aggregate()
		          .block(Duration.ofSeconds(30));

		assertThat(counter.get()).isEqualTo(expectedMsg);
	}

	@Test
	void testIssue1394_SchemeHttpConfiguredH2CNegotiatedH2C() {
		// "prior-knowledge" is used and stream id is 3
		doTestIssue1394_SchemeHttp("3", HttpProtocol.H2C);
	}

	@Test
	void testIssue1394_SchemeHttpConfiguredH2CAndH2NegotiatedH2C() {
		// "prior-knowledge" is used and stream id is 3
		doTestIssue1394_SchemeHttp("3", HttpProtocol.H2, HttpProtocol.H2C);
	}

	@Test
	void testIssue1394_SchemeHttpConfiguredH2CAndHTTP11NegotiatedH2C() {
		// "Upgrade" header is used and stream id is 1
		doTestIssue1394_SchemeHttp("1", HttpProtocol.HTTP11, HttpProtocol.H2C);
	}

	@Test
	void testIssue1394_SchemeHttpConfiguredH2CAndHTTP11AndH2NegotiatedH2C() {
		// "Upgrade" header is used and stream id is 1
		doTestIssue1394_SchemeHttp("1", HttpProtocol.HTTP11, HttpProtocol.H2, HttpProtocol.H2C);
	}

	@Test
	void testIssue1394_SchemeHttpConfiguredHTTP11NegotiatedHTTP11() {
		// there is no header with stream id information
		doTestIssue1394_SchemeHttp("null", HttpProtocol.HTTP11);
	}

	@Test
	void testIssue1394_SchemeHttpConfiguredHTTP11AndH2NegotiatedHTTP11() {
		// there is no header with stream id information
		doTestIssue1394_SchemeHttp("null", HttpProtocol.HTTP11, HttpProtocol.H2);
	}

	private void doTestIssue1394_SchemeHttp(String expectedStreamId, HttpProtocol... protocols) {
		disposableServer =
				createServer()
				          .host("localhost")
				          .protocol(HttpProtocol.HTTP11, HttpProtocol.H2C)
				          .handle((req, res) -> res.sendString(Mono.just("testIssue1394")))
				          .bindNow(Duration.ofSeconds(30));

		ProtocolSslContextSpec clientCtx;
		if (protocols.length == 1 && protocols[0] == HttpProtocol.HTTP11) {
			clientCtx = Http11SslContextSpec.forClient();
		}
		else {
			clientCtx = Http2SslContextSpec.forClient();
		}
		HttpClient.create()
		          .protocol(protocols)
		          .secure(spec -> spec.sslContext(clientCtx))
		          .wiretap(true)
		          .get()
		          .uri("http://localhost:" + disposableServer.port() + "/")
		          .responseSingle((res, bytes) -> Mono.just(res.responseHeaders().get("x-http2-stream-id", "null")))
		          .as(StepVerifier::create)
		          .expectNext(expectedStreamId)
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue1394_SchemeHttpsConfiguredH2NegotiatedH2() {
		doTestIssue1394_SchemeHttps(s -> !"null".equals(s), HttpProtocol.H2);
	}

	@Test
	void testIssue1394_SchemeHttpsConfiguredH2AndH2CNegotiatedH2() {
		doTestIssue1394_SchemeHttps(s -> !"null".equals(s), HttpProtocol.H2, HttpProtocol.H2C);
	}

	@Test
	void testIssue1394_SchemeHttpsConfiguredH2AndH2CAndHTTP11NegotiatedH2() {
		doTestIssue1394_SchemeHttps(s -> !"null".equals(s), HttpProtocol.HTTP11, HttpProtocol.H2, HttpProtocol.H2C);
	}

	@Test
	void testIssue1394_SchemeHttpsConfiguredH2AndHTTP11NegotiatedH2() {
		doTestIssue1394_SchemeHttps(s -> !"null".equals(s), HttpProtocol.HTTP11, HttpProtocol.H2);
	}

	@Test
	void testIssue1394_SchemeHttpsConfiguredHTTP11NegotiatedHTTP11() {
		doTestIssue1394_SchemeHttps("null"::equals, HttpProtocol.HTTP11);
	}

	@Test
	void testIssue1394_SchemeHttpsConfiguredHTTP11AndH2CNegotiatedHTTP11() {
		doTestIssue1394_SchemeHttps("null"::equals, HttpProtocol.HTTP11, HttpProtocol.H2C);
	}

	private void doTestIssue1394_SchemeHttps(Predicate<String> predicate, HttpProtocol... protocols) {
		HttpClient.create()
		          .protocol(protocols)
		          .wiretap(true)
		          .get()
		          .uri("https://example.com")
		          .responseSingle((res, bytes) -> Mono.just(res.responseHeaders().get("x-http2-stream-id", "null")))
		          .as(StepVerifier::create)
		          .expectNextMatches(predicate)
		          .expectComplete()
		          .verify(Duration.ofSeconds(30));
	}

	@Test
	void testIssue1394_SchemeHttpConfiguredH2() {
		doTestIssue1394_ProtocolSchemeNotCompatible(HttpProtocol.H2, "http", H2_WITHOUT_TLS_CLIENT);
	}

	@Test
	void testIssue1394_SchemeHttpsConfiguredH2C() {
		doTestIssue1394_ProtocolSchemeNotCompatible(HttpProtocol.H2C, "https", H2C_WITH_TLS_CLIENT);
	}

	private void doTestIssue1394_ProtocolSchemeNotCompatible(HttpProtocol protocol, String scheme, String expectedMessage) {
		HttpClient.create()
		          .protocol(protocol)
		          .wiretap(true)
		          .get()
		          .uri(scheme + "://example.com")
		          .responseSingle((res, bytes) -> Mono.just(res.responseHeaders().get("x-http2-stream-id")))
		          .as(StepVerifier::create)
		          .expectErrorMessage(expectedMessage)
		          .verify(Duration.ofSeconds(30));
	}
}
