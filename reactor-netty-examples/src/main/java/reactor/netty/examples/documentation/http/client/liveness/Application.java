/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.documentation.http.client.liveness;

import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.function.Tuple2;

import java.time.Duration;

public class Application {

	public static void main(String[] args) {
		ConnectionProvider provider =
				ConnectionProvider.builder("liveness")
				                  .maxIdleTime(Duration.ofSeconds(2))                              //<1>
				                  .build();

		HttpClient client =
				HttpClient.create(provider)
				          .protocol(HttpProtocol.H2)
				          .secure()
				          .http2Settings(builder -> builder.pingAckTimeout(Duration.ofMillis(600)) //<2>
				                                           .pingAckDropThreshold(2));              //<3>

		Tuple2<String, HttpHeaders> response =
				client.get()
				      .uri("https://example.com/")
				      .responseSingle((res, bytes) -> bytes.asString()
				                                           .zipWith(Mono.just(res.responseHeaders())))
				      .block();

		System.out.println("Used stream ID: " + response.getT2().get("x-http2-stream-id"));
		System.out.println("Response: " + response.getT1());
	}
}
