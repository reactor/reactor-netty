/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.tcp.securechat;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpSslContextSpec;

public class SecureChatClient {

	private static final String HOST = System.getProperty("host", "127.0.0.1");
	private static final int PORT = Integer.parseInt(System.getProperty("port", "8992"));
	private static final boolean WIRETAP = System.getProperty("wiretap") != null;

	public static void main(String[] args) {
		TcpSslContextSpec tcpSslContextSpec =
				TcpSslContextSpec.forClient()
				                 .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		TcpClient client =
				TcpClient.create()
				         .host(HOST)
				         .port(PORT)
				         .wiretap(WIRETAP)
				         .doOnConnected(connection ->
				             connection.addHandlerLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter())))
				         .secure(spec -> spec.sslContext(tcpSslContextSpec));

		Connection conn = client.connectNow();

		conn.inbound()
		    .receive()
		    .asString()
		    .subscribe(System.out::println);

		Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8.name());
		while (scanner.hasNext()) {
			String text = scanner.nextLine();
			conn.outbound()
			    .sendString(Mono.just(text + "\r\n"))
			    .then()
			    .subscribe();
			if ("bye".equalsIgnoreCase(text)) {
				break;
			}
		}

		conn.onDispose()
		    .block();
	}
}
