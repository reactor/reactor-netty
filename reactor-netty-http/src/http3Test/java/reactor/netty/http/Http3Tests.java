/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.time.Duration;

/**
 * Holds HTTP/3 specific tests.
 *
 * @author Violeta Georgieva
 * @since 1.2.0
 */
class Http3Tests {
	static final String HTTP3_WITHOUT_TLS_SERVER = "Configured HTTP/3 protocol without TLS. " +
			"Configure TLS via HttpServer#secure";
	static final String HTTP3_WITHOUT_TLS_CLIENT = "Configured HTTP/3 protocol without TLS. Check URL scheme";

	static SelfSignedCertificate ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new SelfSignedCertificate();
	}

	@Test
	void testHttpClientNoSecurityHttp3Fails() {
		HttpClient.create()
		          .protocol(HttpProtocol.HTTP3)
		          .wiretap(true)
		          .get()
		          .uri("http://example.com")
		          .responseContent()
		          .aggregate()
		          .asString()
		          .as(StepVerifier::create)
		          .verifyErrorMessage(HTTP3_WITHOUT_TLS_CLIENT);
	}

	@Test
	void testHttpServerNoSecurityHttp3Fails() {
		createServer()
		        .noSSL()
		        .handle((req, res) -> res.sendString(Mono.just("Hello")))
		        .bind()
		        .as(StepVerifier::create)
		        .verifyErrorMessage(HTTP3_WITHOUT_TLS_SERVER);
	}

	static HttpServer createServer() {
		Http3SslContextSpec serverCtx = Http3SslContextSpec.forServer(ssc.key(), null, ssc.cert());
		return HttpServer.create()
		                 .port(0)
		                 .wiretap(true)
		                 .protocol(HttpProtocol.HTTP3)
		                 .secure(spec -> spec.sslContext(serverCtx))
		                 .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5))
		                                            .maxData(10000000)
		                                            .maxStreamDataBidirectionalLocal(1000000)
		                                            .maxStreamDataBidirectionalRemote(1000000)
		                                            .maxStreamsBidirectional(100)
		                                            .tokenHandler(InsecureQuicTokenHandler.INSTANCE));
	}
}
