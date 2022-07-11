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
package reactor.netty5.examples.tcp.echo;

import io.netty5.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty5.BufferFlux;
import reactor.netty5.Connection;
import reactor.netty5.tcp.TcpClient;
import reactor.netty5.tcp.TcpSslContextSpec;

/**
 * A TCP client that sends a package to the TCP server and
 * later echoes the received content thus initiating ping/pong.
 *
 * @author Violeta Georgieva
 */
public final class EchoClient {

	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8443" : "8080"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;

	public static void main(String[] args) {
		TcpClient client =
				TcpClient.create()
				         .port(PORT)
				         .wiretap(WIRETAP);

		if (SECURE) {
			TcpSslContextSpec tcpSslContextSpec =
					TcpSslContextSpec.forClient()
					                 .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
			client = client.secure(spec -> spec.sslContext(tcpSslContextSpec));
		}

		Connection connection =
				client.handle((in, out) -> out.send(Flux.concat(BufferFlux.fromString(Mono.just("echo")),
				                                                in.receive().transferOwnership())))
				      .connectNow();

		connection.onDispose()
		          .block();
	}
}
