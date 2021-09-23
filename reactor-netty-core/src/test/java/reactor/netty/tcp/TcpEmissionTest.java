/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.tcp;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;

import java.io.InputStream;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TcpEmissionTest {

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void testBackpressure(boolean flushOnEach) throws Exception {
		byte[] array = new byte[32];
		Random random = new Random();
		final int emissionCount = 130;

		DisposableServer server =
				TcpServer.create()
				         .handle((inbound, outbound) ->
				                 outbound.send(
				                         Flux.fromStream(IntStream.range(0, emissionCount)
				                                                  .mapToObj(i -> {
				                                                      random.nextBytes(array);
				                                                      return Unpooled.copiedBuffer(array);
				                                                  })),
				                         b -> flushOnEach))
				         .host("localhost")
				         .port(0)
				         .bindNow();

		CountDownLatch latch = new CountDownLatch(1);
		AtomicLong cnt = new AtomicLong();
		try (Socket socket = new Socket("localhost", server.port());
		     InputStream is = socket.getInputStream()) {
			byte[] buffer = new byte[32];
			while (true) {
				is.read(buffer, 0, 4);
				if (cnt.incrementAndGet() >= emissionCount * 8) {
					latch.countDown();
					break;
				}
			}
		}
		finally {
			server.disposeNow();
		}

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
	}
}
