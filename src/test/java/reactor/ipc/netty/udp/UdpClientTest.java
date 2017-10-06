/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.CharsetUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.resources.LoopResources;

public class UdpClientTest {

	@Test
	public void smokeTest() throws Exception {
		LoopResources resources = LoopResources.create("test");
		CountDownLatch latch = new CountDownLatch(4);
		Connection server =
				UdpServer.create(ops -> ops.port(0).loopResources(resources))
				         .newHandler((in, out) -> in.receiveObject()
				                                    .map(o -> {
				                                            if (o instanceof DatagramPacket) {
				                                                DatagramPacket received = (DatagramPacket) o;
				                                                System.out.println("Server received " + received.content().toString(CharsetUtil.UTF_8));
				                                                ByteBuf buf1 = Unpooled.copiedBuffer("echo ", CharsetUtil.UTF_8);
				                                                ByteBuf buf2 = Unpooled.copiedBuffer(buf1, received.content().retain());
				                                                return new DatagramPacket(buf2, received.sender());
				                                            }
				                                            else {
				                                                return Mono.error(new Exception());
				                                            }
				                                    })
				                                    .flatMap(p -> out.sendObject(p)))
				         .block(Duration.ofSeconds(30));

		Connection client1 =
				UdpClient.create(ops -> ops.port(server.address().getPort()).loopResources(resources))
				         .newHandler((in, out) -> {
				                                  in.receive()
				                                    .subscribe(b -> {
				                                        System.out.println("Client1 received " + b.toString(CharsetUtil.UTF_8));
				                                        latch.countDown();
				                                    });
				                                  return out.sendString(Mono.just("ping1"))
				                                            .then(out.sendString(Mono.just("ping2")))
				                                            .neverComplete();
				         })
				         .block(Duration.ofSeconds(30));

		Connection client2 =
				UdpClient.create(ops -> ops.port(server.address().getPort()).loopResources(resources))
				         .newHandler((in, out) -> {
				                                  in.receive()
				                                    .subscribe(b -> {
				                                        System.out.println("Client2 received " + b.toString(CharsetUtil.UTF_8));
				                                        latch.countDown();
				                                    });
				                                  return out.sendString(Mono.just("ping3"))
				                                            .then(out.sendString(Mono.just("ping4")))
				                                            .neverComplete();
				         })
				         .block(Duration.ofSeconds(30));

		assertTrue(latch.await(30, TimeUnit.SECONDS));
		server.dispose();
		client1.dispose();
		client2.dispose();
	}

	@Test
	public void testIssue192() {
		UdpServer.Builder serverBuilder = UdpServer.builder();
		UdpClient.Builder clientBuilder = UdpClient.builder();
		assertThat(Thread.getAllStackTraces().keySet().stream().allMatch(t -> !t.getName().startsWith("udp"))).isTrue();
		serverBuilder.build();
		clientBuilder.build();
		assertThat(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().startsWith("udp"))).isTrue();
	}
}
