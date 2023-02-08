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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLSession;

import io.netty.channel.ChannelId;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpServer;
import reactor.netty.tcp.TcpSslContextSpec;

public class SecureChatServer {

	private static final int PORT = Integer.parseInt(System.getProperty("port", "8992"));
	private static final boolean WIRETAP = System.getProperty("wiretap") != null;

	public static void main(String[] args) throws UnknownHostException, CertificateException {
		ConcurrentHashMap<ChannelId, Connection> conns = new ConcurrentHashMap<>();
		String hostname = InetAddress.getLocalHost().getHostName();

		SelfSignedCertificate ssc = new SelfSignedCertificate();
		TcpServer.create()
		         .port(PORT)
		         .wiretap(WIRETAP)
		         .doOnConnection(connection -> {
		             // Cache the new connection. It'll be needed later
		             // when the server broadcasts messages from other clients.
		             ChannelId id = connection.channel().id();
		             conns.put(id, connection);
		             connection.addHandlerLast(new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
		             connection.onDispose(() -> conns.remove(id));
		         })
		         .handle((in, out) -> {
		             List<String> welcomeTexts = new ArrayList<>();
		             welcomeTexts.add("Welcome to " + hostname + " secure chat service!\n");
		             in.withConnection(connection -> {
		                 SslHandler handler = connection.channel().pipeline().get(SslHandler.class);
		                 SSLSession session = handler.engine().getSession();
		                 String cipherSuite = session.getCipherSuite();
		                 String msg = "Your session is protected by " + cipherSuite + " cipher suite.\n";
		                 welcomeTexts.add(msg);
		             });

		             Flux<String> welcomeFlux = Flux.fromIterable(welcomeTexts);

		             Flux<String> flux =
		                     in.receive()
		                       .asString()
		                       .takeUntil("bye"::equalsIgnoreCase)
		                       .handle((text, sink) ->
		                           in.withConnection(current -> {
		                               for (Connection conn : conns.values()) {
		                                   if (conn == current) {
		                                       sink.next(text);
		                                   }
		                                   else {
		                                       String msg = "[" + conn.channel().remoteAddress() + "] " + text + '\n';

		                                       conn.outbound().sendString(Mono.just(msg)).then().subscribe();
		                                   }
		                               }
		                           }))
		                       .map(msg -> "[you] " + msg + '\n');

		             return out.sendString(Flux.concat(welcomeFlux, flux));
		         })
		         .secure(spec -> spec.sslContext(TcpSslContextSpec.forServer(ssc.certificate(), ssc.privateKey())))
		         .bindNow()
		         .onDispose()
		         .block();
	}
}
