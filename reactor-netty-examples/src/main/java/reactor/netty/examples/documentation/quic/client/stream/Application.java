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
package reactor.netty.examples.documentation.quic.client.stream;

import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.quic.QuicClient;
import reactor.netty.quic.QuicConnection;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

public class Application {

	public static void main(String[] args) throws InterruptedException {
		QuicSslContext clientCtx =
				QuicSslContextBuilder.forClient()
				                     .applicationProtocols("http/1.1")
				                     .build();

		QuicConnection connection =
				QuicClient.create()
				          .bindAddress(() -> new InetSocketAddress(0))
				          .remoteAddress(() -> new InetSocketAddress("127.0.0.1", 8080))
				          .secure(clientCtx)
				          .idleTimeout(Duration.ofSeconds(5))
				          .initialSettings(spec ->
				              spec.maxData(10000000)
				                  .maxStreamDataBidirectionalLocal(1000000))
				          .connectNow();

		CountDownLatch latch = new CountDownLatch(1);
		connection.createStream((in, out) ->             //<1>
		              out.sendString(Mono.just("Hello")) //<2>
		                 .then(in.receive()              //<3>
		                         .asString()
		                         .doOnNext(s -> {
		                             System.out.println("CLIENT RECEIVED: " + s);
		                             latch.countDown();
		                         })
		                         .then()))
		          .subscribe();

		latch.await();

		connection.onDispose()
		          .block();
	}
}