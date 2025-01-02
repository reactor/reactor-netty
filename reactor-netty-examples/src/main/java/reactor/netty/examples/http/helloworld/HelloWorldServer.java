/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.http.helloworld;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.core.publisher.Mono;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.Http3SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.TEXT_PLAIN;

/**
 * An HTTP server that expects GET request and sends back "Hello World!".
 *
 * @author Violeta Georgieva
 */
public final class HelloWorldServer {

	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8443" : "8080"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;
	static final boolean COMPRESS = System.getProperty("compress") != null;
	static final boolean HTTP2 = System.getProperty("http2") != null;
	static final boolean HTTP3 = System.getProperty("http3") != null;

	public static void main(String[] args) throws Exception {
		HttpServer server =
				HttpServer.create()
				          .port(PORT)
				          .wiretap(WIRETAP)
				          .compress(COMPRESS)
				          .route(r -> r.get("/hello",
				                  (req, res) -> res.header(CONTENT_TYPE, TEXT_PLAIN)
				                                   .sendString(Mono.just("Hello World!"))));

		if (SECURE) {
			SelfSignedCertificate ssc = new SelfSignedCertificate();
			if (HTTP2) {
				server = server.secure(spec -> spec.sslContext(Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())));
			}
			else if (HTTP3) {
				server = server.secure(spec -> spec.sslContext(Http3SslContextSpec.forServer(ssc.key(), null, ssc.cert())));
			}
			else {
				server = server.secure(spec -> spec.sslContext(Http11SslContextSpec.forServer(ssc.certificate(), ssc.privateKey())));
			}
		}

		if (HTTP2) {
			server = server.protocol(HttpProtocol.H2);
		}

		if (HTTP3) {
			server =
					server.protocol(HttpProtocol.HTTP3)
					      .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5))
					                                 .maxData(10000000)
					                                 .maxStreamDataBidirectionalLocal(1000000)
					                                 .maxStreamDataBidirectionalRemote(1000000)
					                                 .maxStreamsBidirectional(100));
		}

		server.bindNow()
		      .onDispose()
		      .block();
	}
}
