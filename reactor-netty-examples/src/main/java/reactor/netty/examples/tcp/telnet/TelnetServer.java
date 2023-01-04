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
package reactor.netty.examples.tcp.telnet;

import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.core.publisher.Flux;
import reactor.netty.tcp.TcpServer;
import reactor.netty.tcp.TcpSslContextSpec;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Date;

public class TelnetServer {

	static final boolean SECURE = System.getProperty("secure") != null;
	static final int PORT = Integer.parseInt(System.getProperty("port", SECURE ? "8992" : "8993"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;

	public static void main(String[] args) throws CertificateException, UnknownHostException {
		String hostName = InetAddress.getLocalHost().getHostName();

		TcpServer server =
				TcpServer.create()
				         .port(PORT)
				         .wiretap(WIRETAP)
				         .doOnConnection(connection ->
				             connection.addHandlerLast(new DelimiterBasedFrameDecoder(8092, Delimiters.lineDelimiter())))
				         .handle((in, out) -> {
				             Flux<String> welcomeFlux =
				                     Flux.just("Welcome to " + hostName + "!\r\n", "It is " + new Date() + " now.\r\n");

				             Flux<String> responses =
				                     in.receive()
				                       .asString()
				                       // Signals completion when 'bye' is encountered.
				                       // Reactor Netty will perform the necessary clean up, including
				                       // disposing the channel.
				                       .takeUntil("bye"::equalsIgnoreCase)
				                       .map(text -> {
				                           String response = "Did you say '" + text + "'?\r\n";
				                           if (text.isEmpty()) {
				                               response = "Please type something.\r\n";
				                           }
				                           else if ("bye".equalsIgnoreCase(text)) {
				                               response = "Have a good day!\r\n";
				                           }

				                           return response;
				                       });

				             return out.sendString(Flux.concat(welcomeFlux, responses));
				         });

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
