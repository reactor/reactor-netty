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
package reactor.netty;

import java.io.InputStream;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.netty.tcp.TcpServer;

public class TcpEmissionTest {

	@Test
	@Timeout(5)
	public void testBackpressure() {
		byte[] array = new byte[32];
		Random random = new Random();
		final int emissionCount = 130;

		DisposableServer server = TcpServer.create()
		                                   .handle((inbound, outbound) -> {
			                                   return outbound.send(Flux.fromStream(IntStream.range(0, emissionCount)
			                                                                                 .mapToObj(i -> {
				                                                                                 random.nextBytes(array);
				                                                                                 return Unpooled.copiedBuffer(
						                                                                                 array);
			                                                                                 })), b -> true);
		                                   })
		                                   .host("localhost")
		                                   .port(8080)
		                                   .bindNow();

		// a slow reader
		AtomicLong cnt = new AtomicLong();
		try (Socket socket = new Socket("localhost", 8080); InputStream is = socket.getInputStream()) {
			byte[] buffer = new byte[32];
			while (true) {
				System.out.println("slow reading... " + cnt);
				is.read(buffer, 0, 4);
				//Thread.sleep(100);
				if (cnt.incrementAndGet() >= emissionCount * 8) {
					break;
				}
			}
			System.out.println("cnt = " + cnt.get());
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		server.dispose();
		System.out.println("SERVER DISPOSE CALLED");
		server.onDispose()
		      .block();
	}

}
