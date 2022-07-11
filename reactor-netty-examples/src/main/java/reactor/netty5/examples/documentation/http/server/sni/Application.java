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
package reactor.netty5.examples.documentation.http.server.sni;

import io.netty5.handler.ssl.SslContext;
import io.netty5.handler.ssl.SslContextBuilder;
import reactor.netty5.DisposableServer;
import reactor.netty5.http.server.HttpServer;

import java.io.File;

public class Application {

	public static void main(String[] args) throws Exception {
		File defaultCert = new File("default_certificate.crt");
		File defaultKey = new File("default_private.key");

		File testDomainCert = new File("default_certificate.crt");
		File testDomainKey = new File("default_private.key");

		SslContext defaultSslContext = SslContextBuilder.forServer(defaultCert, defaultKey).build();
		SslContext testDomainSslContext = SslContextBuilder.forServer(testDomainCert, testDomainKey).build();

		DisposableServer server =
				HttpServer.create()
				          .secure(spec -> spec.sslContext(defaultSslContext)
				                              .addSniMapping("*.test.com",
				                                      testDomainSpec -> testDomainSpec.sslContext(testDomainSslContext)))
				          .bindNow();

		server.onDispose()
		      .block();
	}
}