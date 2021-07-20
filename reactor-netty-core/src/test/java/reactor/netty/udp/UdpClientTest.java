/*
 * Copyright (c) 2017-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.udp;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.unix.DomainDatagramPacket;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.resources.LoopResources;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assumptions.assumeThat;

class UdpClientTest {

	@Test
	void smokeTest() throws Exception {
		LoopResources resources = LoopResources.create("test");
		CountDownLatch latch = new CountDownLatch(4);
		Connection server =
				UdpServer.create()
				         .port(0)
				         .runOn(resources)
				         .handle((in, out) -> in.receiveObject()
				                                    .map(o -> {
				                                            if (o instanceof DatagramPacket) {
				                                                DatagramPacket received = (DatagramPacket) o;
				                                                ByteBuf buffer = received.content();
				                                                System.out.println("Server received " + buffer.readCharSequence(buffer.readableBytes(), CharsetUtil.UTF_8));
				                                                ByteBuf buf1 = Unpooled.copiedBuffer("echo ", CharsetUtil.UTF_8);
				                                                ByteBuf buf2 = Unpooled.copiedBuffer(buf1, buffer);
				                                                buf1.release();
				                                                return new DatagramPacket(buf2, received.sender());
				                                            }
				                                            else {
				                                                return Mono.error(new Exception());
				                                            }
				                                    })
				                                    .flatMap(out::sendObject))
				         .wiretap(true)
				         .bind()
				         .block(Duration.ofSeconds(30));
		assertThat(server).isNotNull();

		InetSocketAddress address = (InetSocketAddress) server.address();
		Connection client1 =
				UdpClient.create()
				         .port(address.getPort())
				         .runOn(resources)
				         .handle((in, out) -> {
				                                  in.receive()
				                                    .subscribe(b -> latch.countDown());
				                                  return out.sendString(Mono.just("ping1"))
				                                            .then(out.sendString(Mono.just("ping2")))
				                                            .neverComplete();
				         })
				         .wiretap(true)
				         .connect()
				         .block(Duration.ofSeconds(30));
		assertThat(client1).isNotNull();

		Connection client2 =
				UdpClient.create()
				         .port(address.getPort())
				         .runOn(resources)
				         .handle((in, out) -> {
				                                  in.receive()
				                                    .subscribe(b -> latch.countDown());
				                                  return out.sendString(Mono.just("ping3"))
				                                            .then(out.sendString(Mono.just("ping4")))
				                                            .neverComplete();
				         })
				         .wiretap(true)
				         .connect()
				         .block(Duration.ofSeconds(30));
		assertThat(client2).isNotNull();

		assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		server.disposeNow();
		client1.disposeNow();
		client2.disposeNow();
	}

	@Test
	void testIssue192() throws Exception {
		LoopResources resources = LoopResources.create("testIssue192");
		NioEventLoopGroup loop = new NioEventLoopGroup(1);
		UdpServer server = UdpServer.create()
		                            .runOn(resources);
		UdpClient client = UdpClient.create()
		                            .resolver(spec -> spec.runOn(loop))
		                            .runOn(resources);
		assertThat(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().startsWith("testIssue192"))).isTrue();

		Connection conn1 = null;
		Connection conn2 = null;
		try {
			conn1 = server.bindNow();
			conn2 = client.connectNow();

			assertThat(conn1).isNotNull();
			assertThat(conn2).isNotNull();
			assertThat(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().startsWith("testIssue192"))).isTrue();
		}
		finally {
			if (conn1 != null) {
				conn1.disposeNow();
			}
			if (conn2 != null) {
				conn2.disposeNow();
			}
			resources.disposeLater()
			         .block(Duration.ofSeconds(5));
			loop.shutdownGracefully()
			    .get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void testUdpClientWithDomainSocketsWithHost() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> UdpClient.create()
		                                   .remoteAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
		                                   .host("localhost")
		                                   .connectNow());
	}

	@Test
	void testUdpClientWithDomainSocketsWithPort() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> UdpClient.create()
		                                   .remoteAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
		                                   .port(1234)
		                                   .connectNow());
	}

	@Test
	void testUdpClientWithDomainSocketsNIOTransport() {
		LoopResources loop = LoopResources.create("testUdpClientWithDomainSocketsNIOTransport");
		try {
			UdpClient.create()
			         .runOn(loop, false)
			         .remoteAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
			         .connect()
			         .as(StepVerifier::create)
			         .expectError(IllegalArgumentException.class)
			         .verify(Duration.ofSeconds(5));
		}
		finally {
			loop.disposeLater()
			    .block(Duration.ofSeconds(30));
		}
	}

	@Test
	void testUdpClientWithDomainSocketsConnectionRefused() {
		assumeThat(LoopResources.hasNativeSupport()).isTrue();
		UdpClient.create()
		         .remoteAddress(() -> new DomainSocketAddress("/tmp/test.sock"))
		         .connect()
		         .as(StepVerifier::create)
		         .expectError(FileNotFoundException.class)
		         .verify(Duration.ofSeconds(5));
	}

	@Test
	void domainSocketsSmokeTest() throws Exception {
		assumeThat(LoopResources.hasNativeSupport()).isTrue();
		LoopResources resources = LoopResources.create("domainSocketsSmokeTest");
		CountDownLatch latch = new CountDownLatch(4);
		Connection server = null;
		Connection client1 = null;
		Connection client2 = null;
		try {
			server =
					UdpServer.create()
					         .bindAddress(UdpClientTest::newDomainSocketAddress)
					         .runOn(resources)
					         .handle((in, out) -> in.receiveObject()
					                                .map(o -> {
					                                    if (o instanceof DomainDatagramPacket) {
					                                        DomainDatagramPacket received = (DomainDatagramPacket) o;
					                                        ByteBuf buffer = received.content();
					                                        System.out.println("Server received " +
					                                                buffer.readCharSequence(buffer.readableBytes(), CharsetUtil.UTF_8));
					                                        ByteBuf buf1 = Unpooled.copiedBuffer("echo ", CharsetUtil.UTF_8);
					                                        ByteBuf buf2 = Unpooled.copiedBuffer(buf1, buffer);
					                                        buf1.release();
					                                        return new DomainDatagramPacket(buf2, received.sender());
					                                    }
					                                    else {
					                                        return Mono.error(new Exception());
					                                    }
					                                })
					                                .flatMap(out::sendObject))
					         .wiretap(true)
					         .bind()
					         .block(Duration.ofSeconds(30));
			assertThat(server).isNotNull();

			DomainSocketAddress address = (DomainSocketAddress) server.address();
			client1 =
					UdpClient.create()
					         .bindAddress(UdpClientTest::newDomainSocketAddress)
					         .remoteAddress(() -> address)
					         .runOn(resources)
					         .handle((in, out) -> {
					             in.receive()
					               .subscribe(b -> latch.countDown());
					             return out.sendString(Mono.just("ping1"))
					                       .then(out.sendString(Mono.just("ping2")))
					                       .neverComplete();
					         })
					         .wiretap(true)
					         .connect()
					         .block(Duration.ofSeconds(30));
			assertThat(client1).isNotNull();

			client2 =
					UdpClient.create()
					         .bindAddress(UdpClientTest::newDomainSocketAddress)
					         .remoteAddress(() -> address)
					         .runOn(resources)
					         .handle((in, out) -> {
					             in.receive()
					               .subscribe(b -> latch.countDown());
					             return out.sendString(Mono.just("ping3"))
					                       .then(out.sendString(Mono.just("ping4")))
					                       .neverComplete();
					         })
					         .wiretap(true)
					         .connect()
					         .block(Duration.ofSeconds(30));
			assertThat(client2).isNotNull();

			assertThat(latch.await(30, TimeUnit.SECONDS)).as("latch await").isTrue();
		}
		finally {
			if (server != null) {
				server.disposeNow();
			}
			if (client1 != null) {
				client1.disposeNow();
			}
			if (client2 != null) {
				client2.disposeNow();
			}
		}
	}

	private static DomainSocketAddress newDomainSocketAddress() {
		try {
			File tempFile = Files.createTempFile("UdpClientTest", "UDS").toFile();
			assertThat(tempFile.delete()).isTrue();
			tempFile.deleteOnExit();
			return new DomainSocketAddress(tempFile);
		}
		catch (Exception e) {
			throw new RuntimeException("Error creating a  temporary file", e);
		}
	}
}
