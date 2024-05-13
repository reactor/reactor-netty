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

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * This test class verifies HTTP response status handling.
 *
 * @author Violeta Georgieva
 */
class HttpResponseStatusCodesHandlingTests extends BaseHttpTest {

	@Test
	void httpStatusCode404IsHandledByTheClient() {
		disposableServer =
				createServer()
				          .route(r -> r.post("/test", (req, res) -> res.send(req.receive()
				                                                                .log("server-received"))))
				          .bindNow();

		HttpClient client = createClient(disposableServer.port());

		Mono<Integer> content = client.headers(h -> h.add("Content-Type", "text/plain"))
				                      .request(HttpMethod.GET)
				                      .uri("/status/404")
				                      .send(ByteBufFlux.fromString(Flux.just("Hello")
				                                                       .log("client-send")))
				                      .responseSingle((res, buf) -> Mono.just(res.status().code()))
				                      .doOnError(t -> System.err.println("Failed requesting server: " + t.getMessage()));

		StepVerifier.create(content)
				    .expectNext(404)
				    .expectComplete()
				    .verify(Duration.ofSeconds(5));
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleCombinations")
	@SuppressWarnings("deprecation")
	void noContentStatusCodes(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols) throws Exception {
		SelfSignedCertificate ssc = new SelfSignedCertificate();

		HttpServer server = createServer().protocol(serverProtocols);
		if (Arrays.asList(serverProtocols).contains(HttpProtocol.H2)) {
			Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
			server = server.secure(spec -> spec.sslContext(serverCtx));
		}

		disposableServer =
				server.host("localhost")
				      .route(r -> r.get("/204-1", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                   .sendHeaders())
				                   .get("/204-2", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT))
				                   .get("/204-3", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                   .sendString(Mono.just("/204-3")))
				                   .get("/204-4", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                   .sendString(Flux.just("/", "204-4")))
				                   .get("/204-5", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                   .send())
				                   .get("/205-1", (req, res) -> res.status(HttpResponseStatus.RESET_CONTENT)
				                                                   .sendHeaders())
				                   .get("/205-2", (req, res) -> res.status(HttpResponseStatus.RESET_CONTENT))
				                   .get("/205-3", (req, res) -> res.status(HttpResponseStatus.RESET_CONTENT)
				                                                   .sendString(Mono.just("/205-3")))
				                   .get("/205-4", (req, res) -> res.status(HttpResponseStatus.RESET_CONTENT)
				                                                   .sendString(Flux.just("/", "205-4")))
				                   .get("/205-5", (req, res) -> res.status(HttpResponseStatus.RESET_CONTENT)
				                                                   .send())
				                   .get("/304-1", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)
				                                                   .sendHeaders())
				                   .get("/304-2", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED))
				                   .get("/304-3", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)
				                                                   .sendString(Mono.just("/304-3")))
				                   .get("/304-4", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)
				                                                   .header(HttpHeaderNames.CONTENT_LENGTH, "6")
				                                                   .sendString(Mono.just("/304-4")))
				                   .get("/304-5", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)
				                                                   .sendString(Flux.just("/", "304-5")))
				                   .get("/304-6", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)
				                                                   .send())
				                   .get("/304-7", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)
				                                                   .sendObject(Unpooled.wrappedBuffer("/304-7".getBytes(Charset.defaultCharset()))))
				                   .get("/304-8", (req, res) -> res.status(HttpResponseStatus.NOT_MODIFIED)
				                                                   .header(HttpHeaderNames.CONTENT_LENGTH, "6")
				                                                   .sendObject(Unpooled.wrappedBuffer("/304-8".getBytes(Charset.defaultCharset())))))
				      .bindNow();

		HttpClient client = createClient(disposableServer::address).protocol(clientProtocols);
		if (Arrays.asList(clientProtocols).contains(HttpProtocol.H2)) {
			Http2SslContextSpec clientCtx =
					Http2SslContextSpec.forClient()
					                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
			client = client.secure(spec -> spec.sslContext(clientCtx));
		}

		checkResponse("/204-1", client);
		checkResponse("/204-2", client);
		checkResponse("/204-3", client);
		checkResponse("/204-4", client);
		checkResponse("/204-5", client);
		checkResponse("/205-1", client);
		checkResponse("/205-2", client);
		checkResponse("/205-3", client);
		checkResponse("/205-4", client);
		checkResponse("/205-5", client);
		checkResponse("/304-1", client);
		checkResponse("/304-2", client);
		checkResponse("/304-3", client);
		checkResponse("/304-4", client);
		checkResponse("/304-5", client);
		checkResponse("/304-6", client);
		checkResponse("/304-7", client);
		checkResponse("/304-8", client);
	}

	static void checkResponse(String url, HttpClient client) {
		client.get()
		      .uri(url)
		      .responseSingle((r, buf) ->
		          Mono.zip(Mono.just(r.status().code()),
		                   Mono.just(r.responseHeaders()),
		                   buf.asString().defaultIfEmpty("NO BODY")))
		      .as(StepVerifier::create)
		      .expectNextMatches(t -> {
		          int code = t.getT1();
		          HttpHeaders h = t.getT2();
		          if (code == HttpResponseStatus.NO_CONTENT.code() ||
		                  code == HttpResponseStatus.NOT_MODIFIED.code()) {
		              return !h.contains(HttpHeaderNames.TRANSFER_ENCODING) &&
		                     !h.contains(HttpHeaderNames.CONTENT_LENGTH) &&
		                     "NO BODY".equals(t.getT3());
		          }
		          else if (code == HttpResponseStatus.RESET_CONTENT.code()) {
		              return !h.contains(HttpHeaderNames.TRANSFER_ENCODING) &&
		                     h.getInt(HttpHeaderNames.CONTENT_LENGTH).equals(0) &&
		                     "NO BODY".equals(t.getT3());
		          }
		          else {
		              return false;
		          }
		      })
		      .expectComplete()
		      .verify(Duration.ofSeconds(30));
	}

	static Stream<Arguments> httpCompatibleCombinations() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}),

				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}),

				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C}, new HttpProtocol[]{HttpProtocol.H2C}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C}),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11})
		);
	}
}