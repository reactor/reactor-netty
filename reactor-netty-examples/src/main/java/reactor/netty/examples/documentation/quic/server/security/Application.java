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
package reactor.netty.examples.documentation.quic.server.security;

import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import reactor.netty.Connection;
import reactor.netty.quic.QuicServer;

import java.io.File;

public class Application {

	public static void main(String[] args) {
		File cert = new File("an X.509 certificate chain file in PEM format");
		File key = new File("a PKCS#8 private key file in PEM format");

		QuicSslContext serverCtx =
				QuicSslContextBuilder.forServer(key, null, cert)
				                     .applicationProtocols("http/1.1")
				                     .build();

		Connection server =
				QuicServer.create()
				          .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
				          .secure(serverCtx) //<1>
				          .bindNow();

		server.onDispose()
		      .block();
	}
}