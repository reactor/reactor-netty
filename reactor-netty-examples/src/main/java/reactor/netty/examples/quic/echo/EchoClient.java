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

import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.quic.QuicClient;
import reactor.netty.quic.QuicConnection;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * A QUIC client that sends a package to the QUIC server and
 * receives the same content that was sent.
 *
 * @author Violeta Georgieva
 */
public class EchoClient {
	static final int PORT = Integer.parseInt(System.getProperty("port", "8443"));
	static final boolean WIRETAP = System.getProperty("wiretap") != null;

	public static void main(String[] args) throws Exception {
		QuicSslContext clientCtx =
				QuicSslContextBuilder.forClient()
				                     .trustManager(InsecureTrustManagerFactory.INSTANCE)
				                     .applicationProtocols("http/1.1")
				                     .build();

		CountDownLatch latch = new CountDownLatch(1);
		QuicConnection connection =
				QuicClient.create()
				          .bindAddress(() -> new InetSocketAddress(0))
				          .remoteAddress(() -> new InetSocketAddress("127.0.0.1", PORT))
				          .secure(clientCtx)
				          .wiretap(WIRETAP)
				          .idleTimeout(Duration.ofSeconds(5))
				          .initialSettings(spec ->
				              spec.maxData(10000000)
				                  .maxStreamDataBidirectionalLocal(1000000))
				          .connectNow();

		connection.createStream((in, out) -> out.sendString(Mono.just("echo"))
		                                        .then(in.receive()
		                                                .asString()
		                                                .doOnNext(s -> {
		                                                    System.out.println("Received " + s);
		                                                    latch.countDown();
		                                                })
		                                                .then()))
		          .subscribe();

		latch.await();
	}
}
