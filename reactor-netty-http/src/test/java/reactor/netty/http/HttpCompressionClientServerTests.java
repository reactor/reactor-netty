/*
 * Copyright (c) 2017-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.io.ByteArrayInputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.zip.GZIPInputStream;

import com.aayushatharva.brotli4j.decoder.DecoderJNI;
import com.aayushatharva.brotli4j.decoder.DirectDecompress;
import com.google.common.collect.ImmutableList;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.compression.Brotli;
import io.netty.handler.codec.compression.Zstd;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.netty.BaseHttpTest;
import reactor.netty.DisposableServer;
import reactor.netty.SocketUtils;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerResponse;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.NettyPipeline.HttpCodec;

/**
 * This test class verifies HTTP compression.
 *
 * @author mostroverkhov
 * @author smaldini
 * @author Violeta Georgieva
 */
class HttpCompressionClientServerTests extends BaseHttpTest {

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest(name = "{displayName}({0}, {1})")
	@MethodSource("data")
	@interface ParameterizedCompressionTest {
	}

	@SuppressWarnings("deprecation")
	static Object[][] data() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(cert.certificate(), cert.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

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
	void brotliServerCompressionEnabled(HttpServer server, HttpClient client) throws Exception {
		assertThat(Brotli.isAvailable()).isTrue();
		disposableServer =
				server.compress(true)
				      .handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		Tuple2<byte[], HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.port(disposableServer.port())
				      .compress(false)
				      .headers(h -> h.add("Accept-Encoding", "br, gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, buf) -> buf.asByteArray()
				                                       .zipWith(Mono.just(res.responseHeaders())))
				      .block(Duration.ofSeconds(10));

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("content-encoding")).isEqualTo("br");

		final byte[] compressedData = resp.getT1();
		assertThat(new String(compressedData, Charset.defaultCharset())).isNotEqualTo("reply");

		DirectDecompress directDecompress = DirectDecompress.decompress(compressedData);
		assertThat(directDecompress.getResultStatus()).isEqualTo(DecoderJNI.Status.DONE);
		final byte[] decompressedData = directDecompress.getDecompressedData();
		assertThat(decompressedData).isNotEmpty();
		assertThat(new String(decompressedData, Charset.defaultCharset())).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void zstdServerCompressionEnabled(HttpServer server, HttpClient client) {
		assertThat(Zstd.isAvailable()).isTrue();
		disposableServer =
				server.compress(true)
				      .handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		Tuple2<byte[], HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.port(disposableServer.port())
				      .compress(false)
				      .headers(h -> h.add("Accept-Encoding", "zstd, gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, buf) -> buf.asByteArray()
				                                       .zipWith(Mono.just(res.responseHeaders())))
				      .block(Duration.ofSeconds(10));

		assertThat(resp).isNotNull();
		assertThat(resp.getT2().get("content-encoding")).isEqualTo("zstd");

		final byte[] compressedData = resp.getT1();
		assertThat(new String(compressedData, Charset.defaultCharset())).isNotEqualTo("reply");

		final byte[] decompressedData = com.github.luben.zstd.Zstd.decompress(compressedData, 1_000);
		assertThat(decompressedData).isNotEmpty();
		assertThat(new String(decompressedData, Charset.defaultCharset())).isEqualTo("reply");
	}

	@ParameterizedCompressionTest
	void serverCompressionEnabledSmallResponse(HttpServer server, HttpClient client) {
		disposableServer =
				server.compress(25)
				      .route(r -> r.get("/1", (in, out) -> out.header("content-length", "5") //explicit 'content-length'
				                                              .sendString(Mono.just("reply")))
				                   .get("/2", (in, out) -> out.sendString(Mono.just("reply")))
				                   .get("/3", (in, out) -> out.header("content-length", "5") //explicit 'content-length'
				                                              .sendObject(Unpooled.wrappedBuffer("reply".getBytes(Charset.defaultCharset()))))
				                   .get("/4", (in, out) -> out.sendObject(Unpooled.wrappedBuffer("reply".getBytes(Charset.defaultCharset()))))
				                   .get("/5", (in, out) -> out.header("content-length", "5") //explicit 'content-length'))
				                                              .sendString(Flux.just("r", "e", "p", "l", "y"))))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		//edit the header manually to attempt to trigger compression on server side
		Flux.range(1, 5)
		    .flatMap(i ->
		            client.port(disposableServer.port())
		                  .headers(h -> h.add("Accept-Encoding", "gzip"))
		                  .get()
		                  .uri("/" + i)
		                  .responseSingle((res, byteBufFlux) -> byteBufFlux.asString()
		                                                                   .zipWith(Mono.just(res.responseHeaders()))))
		    .collectList()
		    .as(StepVerifier::create)
		    .assertNext(list -> assertThat(list).allMatch(t ->
		        //check the server didn't send the gzip header, only 'content-length'
		        t.getT2().get("conTENT-encoding") == null &&
		                //check the server sent plain text
		                "reply".equals(t.getT1())))
		    .expectComplete()
		    .verify(Duration.ofSeconds(10));
	}

	@ParameterizedCompressionTest
	void serverCompressionPredicateTrue(HttpServer server, HttpClient client) throws Exception {
		testServerCompressionPredicateTrue(server, client, false);
	}

	/* https://github.com/spring-projects/spring-boot/issues/27176 */
	@ParameterizedCompressionTest
	void serverCompressionPredicateTrue_WithScheduledResponse(HttpServer server, HttpClient client) throws Exception {
		testServerCompressionPredicateTrue(server, client, true);
	}

	private void testServerCompressionPredicateTrue(HttpServer server, HttpClient client, boolean useScheduler)
			throws Exception {
		disposableServer =
				server.compress((req, res) -> true)
				      .handle((in, out) ->
				              useScheduler ? out.sendString(Mono.just("reply")
				                                                .subscribeOn(Schedulers.boundedElastic())) :
				                             out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		Tuple2<HttpHeaders, byte[]> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.port(disposableServer.port())
				      .headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, byteBufMono) -> Mono.just(res.responseHeaders())
				                                                .zipWith(byteBufMono.asByteArray()))
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
		AtomicReference<List<String>> acceptEncodingHeaderValues = new AtomicReference<>(ImmutableList.of("fail"));

		disposableServer =
				server.compress(true)
				      .handle((in, out) -> out.sendString(Mono.just("reply")))
				      .bindNow(Duration.ofSeconds(10));
		client.port(disposableServer.port())
		      .compress(true)
		      .headers(h -> acceptEncodingHeaderValues.set(h.getAll("accept-encoding")))
		      .get()
		      .uri("/test")
		      .responseContent()
		      .blockLast(Duration.ofSeconds(10));

		assertThat(acceptEncodingHeaderValues.get()).isEqualTo(ImmutableList.of("br", "gzip"));
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
	void testIssue825SendMono() {
		doTestIssue825_2((b, out) -> out.send(Mono.just(b)));
	}

	@Test
	void testIssue825SendObject() {
		doTestIssue825_2((b, out) -> out.sendObject(b));
	}

	@Test
	void testIssue825SendHeaders() {
		doTestIssue825_2((b, out) -> {
			b.release();
			return out.header(HttpHeaderNames.CONTENT_LENGTH, "0").sendHeaders();
		});
	}

	private void doTestIssue825_2(BiFunction<ByteBuf, HttpServerResponse, Publisher<Void>> serverFn) {
		int port1 = SocketUtils.findAvailableTcpPort();
		int port2 = SocketUtils.findAvailableTcpPort();

		AtomicReference<Throwable> error = new AtomicReference<>();
		AtomicReference<Throwable> bufferReleasedError = new AtomicReference<>();
		DisposableServer server1 = null;
		DisposableServer server2 = null;
		Sinks.Empty<Void> bufferReleased = Sinks.empty();
		try {
			server1 =
					createServer(port1)
					          .compress((req, res) -> {
					              throw new RuntimeException("testIssue825_2");
					          })
					          .handle((in, out) ->
					              createClient(port2)
					                        .doOnChannelInit((obs, ch, addr) ->
					                            ch.pipeline().addAfter(HttpCodec, "doTestIssue825_2", new ChannelInboundHandlerAdapter() {
					                                @Override
					                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
					                                    LastHttpContent last = null;
					                                    int expectedRefCount = 0;
					                                    if (msg instanceof LastHttpContent) {
					                                        last = (LastHttpContent) msg;
					                                        expectedRefCount = last.content().refCnt() - 1;
					                                    }
					                                    ctx.fireChannelRead(msg);
					                                    if (last != null) {
					                                        if (last.content().refCnt() == expectedRefCount) {
					                                            bufferReleased.tryEmitEmpty();
					                                        }
					                                        else {
					                                            bufferReleased.tryEmitError(new RuntimeException("The buffer is not released!"));
					                                        }
					                                    }
					                                }
					                            }))
					                        .get()
					                        .uri("/")
					                        .responseContent()
					                        .retain()
					                        .doOnError(bufferReleasedError::set)
					                        .flatMap(b -> serverFn.apply(b, out))
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
			                  .response((res, bytes) -> Mono.just(res.status().code())))
			            .expectNext(500)
			            .expectComplete()
			            .verify(Duration.ofSeconds(30));

			bufferReleased.asMono()
			              .as(StepVerifier::create)
			              .expectComplete()
			              .verify(Duration.ofSeconds(5));

			assertThat(error.get()).isNotNull()
			                       .isInstanceOf(RuntimeException.class);
			assertThat(bufferReleasedError.get()).isNull();
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

	@ParameterizedCompressionTest
	void serverCompressionEnabledResponseCompressionDisabled(HttpServer server, HttpClient client) {
		disposableServer =
				server.compress(1)
				      .route(r -> r.get("/1", (in, out) -> out.compression(false).sendString(Mono.just("reply")))
				                   .get("/2", (in, out) -> out.compression(false).sendString(Flux.just("re", "ply"))))
				      .bindNow(Duration.ofSeconds(10));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		//edit the header manually to attempt to trigger compression on server side
		Flux.range(1, 2)
		    .flatMap(i ->
		            client.port(disposableServer.port())
		                  .headers(h -> h.add("Accept-Encoding", "gzip"))
		                  .get()
		                  .uri("/" + i)
		                  .responseSingle((res, byteBufFlux) -> byteBufFlux.asString()
		                                                                   .zipWith(Mono.just(res.responseHeaders()))))
		    .collectList()
		    .as(StepVerifier::create)
		    .assertNext(list -> assertThat(list).allMatch(t ->
		            //check the server didn't send the gzip header, only 'content-length'
		            t.getT2().get("conTENT-encoding") == null &&
		                    //check the server sent plain text
		                    "reply".equals(t.getT1())))
		    .expectComplete()
		    .verify(Duration.ofSeconds(10));
	}
}
