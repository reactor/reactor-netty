/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.websocket.echo;

import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.server.HttpServer;

/**
 * A WebSocket server example that echoes incoming messages back to the client.
 *
 * @author Vineeth Yelagandula
 */
public final class WebsocketServer {
	
	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8443" : "8080"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;
	
	public static void main(String[] args) throws Exception {
		HttpServer server = 
			HttpServer.create()
		          .port(PORT)
		          .wiretap(WIRETAP)
		          .route(r -> r.ws("/ws",
		                  (inbound, outbound) -> outbound.send(inbound.receive().retain())));

			if (SECURE){ 
			X509Bundle ssc = new CertificateBuilder()
					.subject("CN=localhost")
					.setIsCertificateAuthority(true)
					.buildSelfSigned();
			Http11SslContextSpec http11SslContextSpec =
					Http11SslContextSpec.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem());
			server = server.secure(spec -> spec.sslContext(http11SslContextSpec));
		}
		          server.bindNow()
		          		.onDispose()
		          		.block();
	}
}
