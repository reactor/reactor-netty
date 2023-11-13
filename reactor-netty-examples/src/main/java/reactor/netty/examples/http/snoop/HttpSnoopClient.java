/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.http.snoop;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;

/**
 * An HTTP client demo that sends two requests to the http snoop server and then waits for the completion of the two requests.
 *
 * @author Kun.Long
 * @see HttpSnoopServer
 **/
public class HttpSnoopClient {

	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8443" : "8080"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;
	static final boolean COMPRESS = System.getProperty("compress") != null;

	public static void main(String[] args) throws Exception {
		HttpClient client = HttpClient.create()
		                              .port(PORT)
		                              .wiretap(WIRETAP)
		                              .compress(COMPRESS);

		if (SECURE) {
			Http11SslContextSpec http11SslContextSpec =
					Http11SslContextSpec.forClient()
					                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
			client = client.secure(spec -> spec.sslContext(http11SslContextSpec));
		}

		// we fire two requests to the server asynchronously
		Mono<String> respOfGet =
				client.headers(hds -> hds.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN))
				      .get()
				      .uri("/hello?q1=a&q2=b")
				      .responseContent()
				      .aggregate()
				      .asString()
				      .doOnNext(resp -> System.out.println("resp of get(): \n" + resp));

		Mono<String> respOfPost =
				client.headers(hds -> hds.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON))
				      .post()
				      .uri("/hello2")
				      .send(ByteBufMono.fromString(Mono.just("{\"foo\": 1,\"bar\": 2}")))
				      .responseContent()
				      .aggregate()
				      .asString()
				      .doOnNext(resp -> System.out.println("resp of post(): \n" + resp));

		System.out.println("requests have fired");

		// then let the main thread wait for the completion of the two requests
		Mono.when(respOfGet, respOfPost)
		    .block();

		System.out.println("main thread exits");
	}
}
