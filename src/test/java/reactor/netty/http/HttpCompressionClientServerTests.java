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
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
 */
public class HttpCompressionClientServerTests {

	@Test
	public void trueEnabledIncludeContentEncoding() {

		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress(true);

		DisposableServer runningServer =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap(true)
				      .bindNow(Duration.ofSeconds(10));

		HttpClient.create()
		          .addressSupplier(() -> address(runningServer))
		          .wiretap(true)
		          .compress(true)
		          .headers(h -> Assert.assertTrue(h.contains("Accept-Encoding", "gzip", true)))
		          .get()
		          .uri("/test")
		          .responseContent()
		          .blockLast();

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}

	@Test
	public void serverCompressionDefault() {
		HttpServer server = HttpServer.create()
		                              .port(0);

		DisposableServer runningServer =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap(true)
				      .bindNow(Duration.ofSeconds(10));

		HttpClient client = HttpClient.create()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap(true);
		Tuple2<String, HttpHeaders> resp =
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("content-encoding")).isNull();

		Assert.assertEquals("reply", resp.getT1());

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}

	@Test
	public void serverCompressionDisabled() {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress(false);

		DisposableServer runningServer =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap(true)
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.create()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap(true);
		Tuple2<String, HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("content-encoding")).isNull();

		Assert.assertEquals("reply", resp.getT1());

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}

	@Test
	public void serverCompressionAlwaysEnabled() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress(true);

		DisposableServer runningServer =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap(true)
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.create()
				                      .addressSupplier(() -> address(runningServer))
		                              .compress(false)
				                      .wiretap(true);
		Tuple2<byte[], HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, buf) -> buf.asByteArray()
				                                       .zipWith(Mono.just(res.responseHeaders())))
				      .block();

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

		runningServer.dispose();
		runningServer.onDispose()
		          .block();
	}



	@Test
	public void serverCompressionEnabledSmallResponse() {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress(25);

		DisposableServer connection =
				server.handle((in, out) -> out.header("content-length", "5")
				                              .sendString(Mono.just("reply")))
				      .bindNow();

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.create()
		                              .addressSupplier(() -> address(connection));
		Tuple2<String, HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, byteBufFlux) -> byteBufFlux.asString()
				                                          .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();
		assertThat(resp).isNotNull();

		//check the server didn't send the gzip header, only transfer-encoding
		HttpHeaders headers = resp.getT2();
		assertThat(headers.get("conTENT-encoding")).isNull();

		//check the server sent plain text
		String reply = resp.getT1();
		Assert.assertEquals("reply", reply);

        connection.dispose();
        connection.onDispose()
		            .block();
	}

	@Test
	public void serverCompressionPredicateTrue() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress((req, res) -> true)
		                              .wiretap(true);

		DisposableServer connection =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow();

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.create()
		                              .addressSupplier(() -> address(connection));
		Tuple2<HttpHeaders, byte[]> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .wiretap(true)
				      .get()
				      .uri("/test")
				      .responseSingle((res, byteBufMono) -> Mono.just(res.responseHeaders())
				                                          .zipWith(byteBufMono
								                                          .asByteArray())
						      .log())
				      .block();

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

        connection.dispose();
        connection.onDispose()
		            .block();
	}

	@Test
	public void serverCompressionPredicateFalse() {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress((req, res) -> false);

		DisposableServer runningServer =
				server.handle((in, out) -> out.sendString(Flux.just("reply").hide()))
				      .wiretap(true)
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.create()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap(true);
		Tuple2<String, HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();
		assertThat(resp).isNotNull();

		//check the server didn't send the gzip header, only transfer-encoding
		HttpHeaders headers = resp.getT2();
		assertThat(headers.get("transFER-encoding")).isEqualTo("chunked");
		assertThat(headers.get("conTENT-encoding")).isNull();

		//check the server sent plain text
		Assert.assertEquals("reply", resp.getT1());

		runningServer.dispose();
		runningServer.onDispose()
		            .block(Duration.ofSeconds(15));
	}

	@Test
	public void serverCompressionEnabledBigResponse() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress(4);

		DisposableServer runningServer =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap(true)
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.create()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap(true);
		Tuple2<byte[], HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("accept-encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, buf) -> buf.asByteArray()
				                                       .zipWith(Mono.just(res.responseHeaders())))
				      .block();

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

		runningServer.dispose();
		runningServer.onDispose()
		          .block();
	}

	@Test
	public void compressionServerEnabledClientDisabledIsNone() {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress(true);

		String serverReply = "reply";
		DisposableServer runningServer =
				server.handle((in, out) -> out.sendString(Mono.just(serverReply)))
				      .wiretap(true)
				      .bindNow(Duration.ofSeconds(10));

		HttpClient client = HttpClient.create()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap(true)
				                      .compress(false);

		Tuple2<String, HttpHeaders> resp =
				client.get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("Content-Encoding")).isNull();
		assertThat(resp.getT1()).isEqualTo(serverReply);

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}


	@Test
	public void compressionServerDefaultClientDefaultIsNone() {
		HttpServer server = HttpServer.create()
		                              .port(0);

		DisposableServer runningServer =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap(true)
				      .bindNow(Duration.ofSeconds(10));

		HttpClient client = HttpClient.create()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap(true);

		Tuple2<String, HttpHeaders> resp =
				client.get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("Content-Encoding")).isNull();
		assertThat(resp.getT1()).isEqualTo("reply");

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}

	@Test
	public void compressionActivatedOnClientAddsHeader() {
		AtomicReference<String> zip = new AtomicReference<>("fail");

		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress(true);
		DisposableServer runningServer =
				server.handle((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap(true)
				      .bindNow(Duration.ofSeconds(10));
		HttpClient.create()
		          .addressSupplier(() -> address(runningServer))
		          .wiretap(true)
		          .compress(true)
		          .headers(h -> zip.set(h.get("accept-encoding")))
		          .get()
		          .uri("/test")
		          .responseContent()
		          .blockLast();

		assertThat(zip.get()).isEqualTo("gzip");
		runningServer.dispose();
		runningServer.onDispose()
				.block();
	}

	private InetSocketAddress address(DisposableServer runningServer) {
		return runningServer.address();
	}

	@Test
	public void testIssue282() {
		DisposableServer server =
				HttpServer.create()
				          .compress(2048)
				          .port(0)
				          .handle((req, res) -> res.sendString(Mono.just("testtesttesttesttest")))
				          .bindNow();

		Mono<String> response =
				HttpClient.create()
				          .port(server.address().getPort())
				          .get()
				          .uri("/")
				          .responseContent()
				          .aggregate()
				          .asString();

		StepVerifier.create(response)
		            .expectNextMatches("testtesttesttesttest"::equals)
		            .expectComplete()
		            .verify();

		server.disposeNow();
	}

	@Test
	public void testIssue292() {
		DisposableServer server =
				HttpServer.create()
				          .compress(10)
				          .port(0)
				          .wiretap(true)
				          .handle((req, res) ->
				                  res.sendHeaders().sendString(Flux.just("test", "test", "test", "test")))
				          .bindNow();

		Mono<String> response =
				HttpClient.create()
				          .port(server.address().getPort())
				          .compress(true)
				          .wiretap(true)
				          .get()
				          .uri("/")
				          .responseContent()
				          .aggregate()
				          .asString();

		StepVerifier.create(response)
		            .expectNextMatches("testtesttesttest"::equals)
		            .expectComplete()
		            .verify();

		response = HttpClient.create()
		                     .port(server.address().getPort())
		                     .wiretap(true)
		                     .compress(true)
		                     .get()
		                     .uri("/")
		                     .responseContent()
		                     .aggregate()
		                     .asString();

		StepVerifier.create(response)
		            .expectNextMatches("testtesttesttest"::equals)
		            .expectComplete()
		            .verify();

		server.disposeNow();
	}

	@Test
	public void testIssue825_1() {
		int port1 = SocketUtils.findAvailableTcpPort();
		int port2 = SocketUtils.findAvailableTcpPort();

		AtomicReference<Throwable> error = new AtomicReference<>();
		DisposableServer server1 =
				HttpServer.create()
				          .port(port1)
				          .compress(true)
				          .handle((in, out) -> out.send(
				              HttpClient.create()
				                        .port(port2)
				                        .wiretap(true)
				                        .get()
				                        .uri("/")
				                        .responseContent()
				                        .doOnError(error::set)))
				                        // .retain() deliberately not invoked
				                        // so that .release() in FluxReceive.drainReceiver will fail
				                        //.retain()))
				          .wiretap(true)
				          .bindNow();

		DisposableServer server2 =
				HttpServer.create()
				          .port(port2)
				          .handle((in, out) -> out.sendString(Mono.just("reply")))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
		        HttpClient.create()
		                  .port(port1)
		                  .wiretap(true)
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

		server1.disposeNow();
		server2.disposeNow();
	}

	@Test
	public void testIssue825_2() {
		int port1 = SocketUtils.findAvailableTcpPort();
		int port2 = SocketUtils.findAvailableTcpPort();

		AtomicReference<Throwable> error = new AtomicReference<>();
		DisposableServer server1 =
				HttpServer.create()
				          .port(port1)
				          .compress((req, res) -> {
				              throw new RuntimeException("testIssue825_2");
				          })
				          .handle((in, out) ->
				              HttpClient.create()
				                        .port(port2)
				                        .wiretap(true)
				                        .get()
				                        .uri("/")
				                        .responseContent()
				                        .retain()
				                        .flatMap(b -> out.send(Mono.just(b)))
				                        .doOnError(error::set))
				          .wiretap(true)
				          .bindNow();

		DisposableServer server2 =
				HttpServer.create()
				          .port(port2)
				          .handle((in, out) -> out.sendString(Mono.just("reply")))
				          .wiretap(true)
				          .bindNow();

		StepVerifier.create(
		        HttpClient.create()
		                  .port(port1)
		                  .wiretap(true)
		                  .compress(true)
		                  .get()
		                  .uri("/")
		                  .responseContent())
		            .expectError()
		            .verify(Duration.ofSeconds(30));

		assertThat(error.get()).isNotNull()
		                       .isInstanceOf(RuntimeException.class);

		server1.disposeNow();
		server2.disposeNow();
	}
}
