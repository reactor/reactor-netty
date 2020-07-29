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
package reactor.netty.examples.http.echo;

import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;

/**
 * An HTTP client that sends POST request to the HTTP server and
 * receives as a response the content that was sent as a request.
 *
 * @author Violeta Georgieva
 */
public final class EchoClient {

	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8443" : "8080"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;
	static final boolean COMPRESS = System.getProperty("compress") != null;

	public static void main(String[] args) {
		HttpClient client =
				HttpClient.create()
				          .port(PORT)
				          .wiretap(WIRETAP)
				          .compress(COMPRESS);

		if (SECURE) {
			client = client.secure(
					spec -> spec.sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)));
		}

		String response =
				client.post()
				      .uri("/echo")
				      .send(ByteBufFlux.fromString(Mono.just("echo")))
				      .responseContent()
				      .aggregate()
				      .asString()
				      .block();

		System.out.println("Response: " + response);
	}
}
