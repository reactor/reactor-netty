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

import java.io.ByteArrayInputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author mostroverkhov
 * @author smaldini
 * @author Violeta Georgieva
 */
class HttpCompressionClientServerTests extends BaseHttpTest {

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest(name = "{index}: {0}, {1}")
	@MethodSource("data")
	@interface ParameterizedCompressionTest {
	}

	static Object[][] data() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContextBuilder clientCtx = SslContextBuilder.forClient()
		                                               .trustManager(InsecureTrustManagerFactory.INSTANCE);

		HttpServer server = createServer();

		HttpServer h2Server = server.protocol(HttpProtocol.H2)
		                            .secure(spec -> spec.sslContext(serverCtx));

		HttpServer h2Http1Server = server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
		                                 .secure(spec -> spec.sslContext(serverCtx));

		HttpClient client = HttpClient.create()
		                              .wiretap(true);

		HttpClient h2Client = client.protocol(HttpProtocol.H2)
		                            .secure(spec -> spec.sslContext(clientCtx));

		HttpClient h2Http1Client = client.protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
		                                 .secure(spec -> spec.sslContext(clientCtx));

		return new Object[][] {{server, client},
				{server.protocol(HttpProtocol.H2C), client.protocol(HttpProtocol.H2C)},
				{server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11), client.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)},
				{h2Server, h2Client},
				{h2Http1Server, h2Http1Client}};
	}

	@ParameterizedCompressionTest
	void trueEnabledIncludeContentEncoding(HttpServer server, HttpClient client) {
		disposableServer =
				server.compress(true)
				      .handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		client.port(disposableServer.port())
		      .compress(true)
		      .headers(h -> assertThat(h.contains("Accept-Encoding", "gzip", true)).isTrue())
		      .get()
		      .uri("/test")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(10));
	}

	@ParameterizedCompressionTest
	void serverCompressionDefault(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		Tuple2<String, HttpHeaders> resp =
				client.port(disposableServer.port())
				      .headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst(Duration.ofSeconds(10));

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("content-encoding")).isNull();

		assertThat(resp.getT1()).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void serverCompressionDisabled(HttpServer server, HttpClient client) {
		disposableServer =
				server.compress(false)
				      .handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		Tuple2<String, HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.port(disposableServer.port())
				      .headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst(Duration.ofSeconds(10));

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("content-encoding")).isNull();

		assertThat(resp.getT1()).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void serverCompressionAlwaysEnabled(HttpServer server, HttpClient client) throws Exception {
		disposableServer =
				server.compress(true)
				      .handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		Tuple2<byte[], HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.port(disposableServer.port())
				      .compress(false)
				      .headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, buf) -> buf.asByteArray()
				                                       .zipWith(Mono.just(res.responseHeaders())))
				      .block(Duration.ofSeconds(10));

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("content-encoding")).isEqualTo("gzip");

		assertThat(new String(resp.getT1(), Charset.defaultCharset())).isNotEqualTo("reply");

		GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(resp.getT1()));
		byte[] deflatedBuf = new byte[1024];
		int readable = gis.read(deflatedBuf);
		gis.close();

		assertThat(readable).isGreaterThan(0);

		String deflated = new String(deflatedBuf, 0, readable, Charset.defaultCharset());

		assertThat(deflated).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void serverCompressionEnabledSmallResponse(HttpServer server, HttpClient client) {
		disposableServer =
				server.compress(25)
				      .handle((in, out) -> out.header("content-length", "5")
				                              .sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		Tuple2<String, HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.port(disposableServer.port())
				      .headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, byteBufFlux) -> byteBufFlux.asString()
				                                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst(Duration.ofSeconds(10));
		assertThat(resp).isNotNull();

		//check the server didn't send the gzip header, only transfer-encoding
		HttpHeaders headers = resp.getT2();
		assertThat(headers.get("conTENT-encoding")).isNull();

		//check the server sent plain text
		String reply = resp.getT1();
		assertThat(reply).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void serverCompressionPredicateTrue(HttpServer server, HttpClient client) throws Exception {
		disposableServer =
				server.compress((req, res) -> true)
				      .handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		Tuple2<HttpHeaders, byte[]> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.port(disposableServer.port())
				      .headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, byteBufMono) -> Mono.just(res.responseHeaders())
				                                                .zipWith(byteBufMono.asByteArray())
				                                                .log())
				      .block(Duration.ofSeconds(10));

		assertThat(resp).isNotNull();
		assertThat(resp.getT1().get("content-encoding")).isEqualTo("gzip");

		byte[] replyBuffer = resp.getT2();

		assertThat(new String(replyBuffer, Charset.defaultCharset())).isNotEqualTo("reply");

		GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(replyBuffer));
		byte[] deflatedBuf = new byte[1024];
		int readable = gis.read(deflatedBuf);
		gis.close();

		assertThat(readable).isGreaterThan(0);

		String deflated = new String(deflatedBuf, 0, readable, Charset.defaultCharset());

		assertThat(deflated).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void serverCompressionPredicateFalse(HttpServer server, HttpClient client) {
		disposableServer =
				server.compress((req, res) -> false)
				      .handle((in, out) -> out.sendString(Flux.just("reply").hide()))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		Tuple2<String, HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.port(disposableServer.port())
				      .headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst(Duration.ofSeconds(10));
		assertThat(resp).isNotNull();

		//check the server didn't send the gzip header, only transfer-encoding
		HttpHeaders headers = resp.getT2();
		assertThat(headers.get("transFER-encoding")).isEqualTo("chunked");
		assertThat(headers.get("conTENT-encoding")).isNull();

		//check the server sent plain text
		assertThat(resp.getT1()).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void serverCompressionEnabledBigResponse(HttpServer server, HttpClient client) throws Exception {
		disposableServer =
				server.compress(4)
				      .handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		Tuple2<byte[], HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.port(disposableServer.port())
				      .headers(h -> h.add("accept-encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, buf) -> buf.asByteArray()
				                                       .zipWith(Mono.just(res.responseHeaders())))
				      .block(Duration.ofSeconds(10));

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("content-encoding")).isEqualTo("gzip");

		assertThat(new String(resp.getT1(), Charset.defaultCharset())).isNotEqualTo("reply");

		GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(resp.getT1()));
		byte[] deflatedBuf = new byte[1024];
		int readable = gis.read(deflatedBuf);
		gis.close();

		assertThat(readable).isGreaterThan(0);

		String deflated = new String(deflatedBuf, 0, readable, Charset.defaultCharset());

		assertThat(deflated).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void compressionServerEnabledClientDisabledIsNone(HttpServer server, HttpClient client) {
		String serverReply = "reply";
		disposableServer =
				server.compress(true)
				      .handle((in, out) -> out.sendString(Mono.just(serverReply)))
				      .bindNow(Duration.ofSeconds(10));

		Tuple2<String, HttpHeaders> resp =
				client.port(disposableServer.port())
				      .compress(false)
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst(Duration.ofSeconds(10));

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("Content-Encoding")).isNull();
		assertThat(resp.getT1()).isEqualTo(serverReply);
	}

	@ParameterizedCompressionTest
	void compressionServerDefaultClientDefaultIsNone(HttpServer server, HttpClient client) {
		disposableServer =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		Tuple2<String, HttpHeaders> resp =
				client.port(disposableServer.port())
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst(Duration.ofSeconds(10));

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("Content-Encoding")).isNull();
		assertThat(resp.getT1()).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void compressionActivatedOnClientAddsHeader(HttpServer server, HttpClient client) {
		AtomicReference<String> zip = new AtomicReference<>("fail");

		disposableServer =
				server.compress(true)
				      .handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));
		client.port(disposableServer.port())
		      .compress(true)
		      .headers(h -> zip.set(h.get("accept-encoding")))
		      .get()
		      .uri("/test")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(10));

		assertThat(zip.get()).isEqualTo("gzip");
	}

	@ParameterizedCompressionTest
	void testIssue282(HttpServer server, HttpClient client) {
		disposableServer =
				server.compress(2048)
				      .handle((req, res) -> res.sendString(Mono.just("testtesttesttesttest")))
				      .bindNow(Duration.ofSeconds(10));

		Mono<String> response =
				client.port(disposableServer.port())
				      .get()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString();

		StepVerifier.create(response)
		            .expectNextMatches("testtesttesttesttest"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(10));
	}

	@ParameterizedCompressionTest
	void testIssue292(HttpServer server, HttpClient client) {
		disposableServer =
				server.compress(10)
				      .handle((req, res) ->
				              res.sendHeaders().sendString(Flux.just("test", "test", "test", "test")))
				      .bindNow(Duration.ofSeconds(10));

		Mono<String> response =
				client.port(disposableServer.port())
				      .compress(true)
				      .get()
				      .uri("/")
				      .responseContent()
				      .aggregate()
				      .asString();

		StepVerifier.create(response)
		            .expectNextMatches("testtesttesttest"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(10));

		response = client.port(disposableServer.port())
		                 .compress(true)
		                 .get()
		                 .uri("/")
		                 .responseContent()
		                 .aggregate()
		                 .asString();

		StepVerifier.create(response)
		            .expectNextMatches("testtesttesttest"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(10));
	}

	@Test
	void testIssue825_1() {
		int port1 = SocketUtils.findAvailableTcpPort();
		int port2 = SocketUtils.findAvailableTcpPort();

		AtomicReference<Throwable> error = new AtomicReference<>();
		DisposableServer server1 = null;
		DisposableServer server2 = null;
		try {
			server1 =
					createServer(port1)
					          .compress(true)
					          .handle((in, out) -> out.send(
					              createClient(port2)
					                        .get()
					                        .uri("/")
					                        .responseContent()
					                        .doOnError(error::set)))
					          // .retain() deliberately not invoked
					          // so that .release() in FluxReceive.drainReceiver will fail
					          //.retain()))
					          .bindNow();

			server2 =
					createServer(port2)
					          .handle((in, out) -> out.sendString(Mono.just("reply")))
					          .bindNow();

			StepVerifier.create(
			        createClient(port1)
			                  .compress(true)
			                  .get()
			                  .uri("/")
			                  .responseContent()
			                  .aggregate()
			                  .asString())
			            .expectError()
			            .verify(Duration.ofSeconds(30));

			assertThat(error.get()).isNotNull()
			                       .isInstanceOf(RuntimeException.class);
		}
		finally {
			if (server1 != null) {
				server1.disposeNow();
			}
			if (server2 != null) {
				server2.disposeNow();
			}
		}
	}

	@Test
	void testIssue825_2() {
		int port1 = SocketUtils.findAvailableTcpPort();
		int port2 = SocketUtils.findAvailableTcpPort();

		AtomicReference<Throwable> error = new AtomicReference<>();
		DisposableServer server1 = null;
		DisposableServer server2 = null;
		try {
			server1 =
					createServer(port1)
					          .compress((req, res) -> {
					              throw new RuntimeException("testIssue825_2");
					          })
					          .handle((in, out) ->
					              createClient(port2)
					                        .get()
					                        .uri("/")
					                        .responseContent()
					                        .retain()
					                        .flatMap(b -> out.send(Mono.just(b)))
					                        .doOnError(error::set))
					          .bindNow();

			server2 =
					createServer(port2)
					          .handle((in, out) -> out.sendString(Mono.just("reply")))
					          .bindNow();

			StepVerifier.create(
			        createClient(port1)
			                  .compress(true)
			                  .get()
			                  .uri("/")
			                  .responseContent())
			            .expectError()
			            .verify(Duration.ofSeconds(30));

			assertThat(error.get()).isNotNull()
			                       .isInstanceOf(RuntimeException.class);
		}
		finally {
			if (server1 != null) {
				server1.disposeNow();
			}
			if (server2 != null) {
				server2.disposeNow();
			}
		}
	}
}
