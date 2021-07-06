/*
 * Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.tcp.echo;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.netty.tcp.TcpServer;
import reactor.netty.tcp.TcpSslContextSpec;

/**
 * A TCP server that sends back the received content.
 *
 * @author Violeta Georgieva
 */
public final class EchoServer {

	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8443" : "8080"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;

	public static void main(String[] args) throws Exception {
		TcpServer server =
				TcpServer.create()
				         .port(PORT)
				         .wiretap(WIRETAP)
				         .handle((in, out) -> out.send(in.receive().retain()));

		if (SECURE) {
			SelfSignedCertificate ssc = new SelfSignedCertificate();
			server = server.secure(
					spec -> spec.sslContext(TcpSslContextSpec.forServer(ssc.certificate(), ssc.privateKey())));
		}

		server.bindNow()
		      .onDispose()
		      .block();
	}
}
