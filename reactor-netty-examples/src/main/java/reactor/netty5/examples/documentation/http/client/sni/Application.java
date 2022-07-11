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
package reactor.netty5.examples.documentation.http.client.sni;

import io.netty5.handler.ssl.SslContext;
import io.netty5.handler.ssl.SslContextBuilder;
import reactor.netty5.http.client.HttpClient;

import javax.net.ssl.SNIHostName;

public class Application {

	public static void main(String[] args) throws Exception {
		SslContext sslContext = SslContextBuilder.forClient().build();

		HttpClient client =
				HttpClient.create()
				          .secure(spec -> spec.sslContext(sslContext)
				                              .serverNames(new SNIHostName("test.com")));

		client.get()
		      .uri("https://127.0.0.1:8080/")
		      .response()
		      .block();
	}
}