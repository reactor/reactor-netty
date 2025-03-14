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

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.Http3SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * An HTTP client that sends GET request to the HTTP server and receives as a response - "Hello World!".
 *
 * @author Violeta Georgieva
 */
public final class HelloWorldClient {

	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8443" : "8080"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;
	static final boolean COMPRESS = System.getProperty("compress") != null;
	static final boolean HTTP2 = System.getProperty("http2") != null;
	static final boolean HTTP3 = System.getProperty("http3") != null;

	public static void main(String[] args) {
		HttpClient client =
				HttpClient.create()
				          .port(PORT)
				          .wiretap(WIRETAP)
				          .compress(COMPRESS);

		if (SECURE) {
			if (HTTP2) {
				Http2SslContextSpec http2SslContextSpec =
						Http2SslContextSpec.forClient()
						                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
				client = client.secure(spec -> spec.sslContext(http2SslContextSpec));
			}
			else if (HTTP3) {
				Http3SslContextSpec http3SslContextSpec =
						Http3SslContextSpec.forClient()
						                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
				client = client.secure(spec -> spec.sslContext(http3SslContextSpec));
			}
			else {
				Http11SslContextSpec http11SslContextSpec =
						Http11SslContextSpec.forClient()
						                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
				client = client.secure(spec -> spec.sslContext(http11SslContextSpec));
			}
		}

		if (HTTP2) {
			client = client.protocol(HttpProtocol.H2);
		}

		if (HTTP3) {
			client =
					client.protocol(HttpProtocol.HTTP3)
					      .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5))
					                                 .maxData(10000000)
					                                 .maxStreamDataBidirectionalLocal(1000000));
		}

		String response =
				client.get()
				      .uri("/hello")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block();

		System.out.println("Response: " + response);
	}
}
