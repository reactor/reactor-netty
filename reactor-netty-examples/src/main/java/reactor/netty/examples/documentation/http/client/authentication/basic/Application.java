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
package reactor.netty.examples.documentation.http.client.authentication.basic;

import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class Application {

	public static void main(String[] args) {
		HttpClient client =
				HttpClient.create()
				          .httpAuthentication(
				              (req, res) -> res.status().code() == 401, // <1>
				              (req, addr) -> { // <2>
				                  // Never hardcode credentials in production code.
				                  // The one below is just an example.
				                  String credentials = "username:password";
				                  String encodedCredentials = Base64.getEncoder()
				                      .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
				                  req.header(HttpHeaderNames.AUTHORIZATION, "Basic " + encodedCredentials);
				              } // <3>
				          );

		client.get()
		      .uri("https://example.com/")
		      .response()
		      .block();
	}
}