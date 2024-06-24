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
package reactor.netty.examples.documentation.http.server.http3;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http3SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

import java.io.File;
import java.time.Duration;

public class Application {

	public static void main(String[] args) throws Exception {
		File certChainFile = new File("certificate chain file");
		File keyFile = new File("private key file");

		Http3SslContextSpec serverCtx = Http3SslContextSpec.forServer(keyFile, null, certChainFile);

		DisposableServer server =
				HttpServer.create()
				          .port(8080)
				          .protocol(HttpProtocol.HTTP3)                 //<1>
				          .secure(spec -> spec.sslContext(serverCtx))   //<2>
				          .http3Settings(spec -> spec.idleTimeout(Duration.ofSeconds(5)) //<3>
				                                     .maxData(10000000)
				                                     .maxStreamDataBidirectionalLocal(1000000)
				                                     .maxStreamDataBidirectionalRemote(1000000)
				                                     .maxStreamsBidirectional(100))
				          .handle((request, response) -> response.header("server", "reactor-netty")
				                                                 .sendString(Mono.just("hello")))
				          .bindNow();

		server.onDispose()
		      .block();
	}
}
