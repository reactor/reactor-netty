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
package reactor.netty.examples.documentation.http.server.liveness;

import reactor.netty.DisposableServer;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

import java.io.File;
import java.time.Duration;

public class Application {

	public static void main(String[] args) {
		File cert = new File("certificate.crt");
		File key = new File("private.key");

		Http2SslContextSpec http2SslContextSpec = Http2SslContextSpec.forServer(cert, key);

		DisposableServer server =
				HttpServer.create()
				          .port(8080)
				          .protocol(HttpProtocol.H2)
				          .secure(spec -> spec.sslContext(http2SslContextSpec))
				          .idleTimeout(Duration.ofSeconds(1))                                      //<1>
				          .http2Settings(builder -> builder.pingAckTimeout(Duration.ofMillis(600)) //<2>
				                                           .pingAckDropThreshold(2))               //<3>
				          .bindNow();

		server.onDispose()
		      .block();
	}
}
