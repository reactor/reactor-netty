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
package reactor.netty.examples.documentation.http.client.http3;

import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.util.function.Tuple2;

import java.net.InetSocketAddress;
import java.time.Duration;

public class Application {

	public static void main(String[] args) throws Exception {
		HttpClient client =
				HttpClient.create()
				          .remoteAddress(() -> new InetSocketAddress("www.google.com", 443))
				          .protocol(HttpProtocol.HTTP3)                                  //<1>
				          .secure()                                                      //<2>
				          .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5)) //<3>
				                                     .maxData(10000000)
				                                     .maxStreamDataBidirectionalLocal(1000000));

		Tuple2<String, HttpHeaders> response =
				client.get()
				      .uri("/")
				      .responseSingle((res, bytes) -> bytes.asString()
				                                           .zipWith(Mono.just(res.responseHeaders())))
				      .block();

		System.out.println("Used stream ID: " + response.getT2().get("x-http3-stream-id"));
		System.out.println("Response: " + response.getT1());
	}
}
