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
package reactor.netty.examples.quic.echo;

import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import reactor.netty.quic.QuicServer;

import java.time.Duration;

/**
 * A QUIC server that sends back the received content.
 *
 * @author Violeta Georgieva
 */
public class EchoServer {
	static final int PORT = Integer.parseInt(System.getProperty("port", "8443"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;

	public static void main(String[] args) throws Exception {
		X509Bundle ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
		QuicSslContext serverCtx =
				QuicSslContextBuilder.forServer(ssc.toTempPrivateKeyPem(), null, ssc.toTempCertChainPem())
				                     .applicationProtocols("http/1.1")
				                     .build();

		QuicServer server =
				QuicServer.create()
				          .host("127.0.0.1")
				          .port(PORT)
				          .secure(serverCtx)
				          .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
				          .wiretap(WIRETAP)
				          .idleTimeout(Duration.ofSeconds(5))
				          .initialSettings(spec ->
				              spec.maxData(10000000)
				                  .maxStreamDataBidirectionalRemote(1000000)
				                  .maxStreamsBidirectional(100))
				          .handleStream((in, out) -> out.send(in.receive().retain()));

		server.bindNow()
		      .onDispose()
		      .block();
	}
}
