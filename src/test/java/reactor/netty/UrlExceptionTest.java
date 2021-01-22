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

package reactor.netty;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

public class UrlExceptionTest {

	@Test
	public void urlExceptionTest() {
		try {
			HttpClient.create()
					.doOnError((httpClientRequest, throwable) -> {

					}, (httpClientResponse, throwable) -> {

					})
					.request(HttpMethod.GET)
					.uri(Mono.error(new IllegalArgumentException("URL cannot be resolved")))
					.responseSingle((httpClientResponse, byteBufMono) -> {
						HttpResponseStatus status = httpClientResponse.status();
						if (status.code() != 200) {
							return Mono.error(new IllegalStateException(status.toString()));
						}
						return byteBufMono.asString();
					})
					.block();

		}
		catch (IllegalArgumentException e){
			assertThat(e.getMessage()).isEqualTo("URL cannot be resolved");
		}
	}
}
