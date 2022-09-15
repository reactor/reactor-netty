/*
 * Copyright (c) 2020-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.examples.documentation.http.client.send.headers;

import io.netty5.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Mono;
import reactor.netty5.BufferFlux;
import reactor.netty5.http.client.HttpClient;

public class Application {

	public static void main(String[] args) {
		HttpClient client =
				HttpClient.create()
				          .headers(h -> h.set(HttpHeaderNames.CONTENT_LENGTH, "5")); //<1>

		client.post()
		      .uri("https://example.com/")
		      .send(BufferFlux.fromString(Mono.just("hello")))
		      .response()
		      .block();
	}
}
