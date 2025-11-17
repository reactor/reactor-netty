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
package reactor.netty.examples.documentation.http.client.authentication.token;

import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.SocketAddress;

public class Application {

	public static void main(String[] args) {
		HttpClient client =
				HttpClient.create()
				          .httpAuthentication(
				              (req, res) -> res.status().code() == 401, // <1>
				              (req, addr) -> { // <2>
				                  String token = generateAuthToken(addr);
				                  req.header(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
				                  return Mono.empty();
				              }
				          );

		client.get()
		      .uri("https://example.com/")
		      .response()
		      .block();
	}

	/**
	 * Generates an authentication token for the given remote address.
	 * In a real application, this would retrieve or generate a valid token.
	 */
	static String generateAuthToken(SocketAddress remoteAddress) {
		// In a real application, implement token generation/retrieval logic
		return "sample-token-123";
	}
}