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
package reactor.netty;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.netty.http.Http3SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

public class Application {

	public static void main(String[] args) throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();

		Http3SslContextSpec serverCtx = Http3SslContextSpec.forServer(cert.key(), null, cert.cert());

		DisposableServer server =
				HttpServer.create()
				          .port(8080)
				          .wiretap(true)
				          .protocol(HttpProtocol.H3)
				          .secure(spec -> spec.sslContext(serverCtx))
				          .idleTimeout(Duration.ofSeconds(5))
				          .http3Settings(spec -> spec.maxData(10000000)
				                                     .maxStreamDataBidirectionalLocal(1000000)
				                                     .maxStreamDataBidirectionalRemote(1000000)
				                                     .maxStreamsBidirectional(100))
				          .handle((request, response) -> response.header("server", "reactor-netty")
				                                                 .send(request.receive().retain()))
				          .bindNow();

		server.onDispose()
		      .block();
	}
}
