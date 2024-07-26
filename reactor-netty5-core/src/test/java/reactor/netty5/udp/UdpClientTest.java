/*
 * Copyright (c) 2017-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.udp;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.CompositeBuffer;
import io.netty5.buffer.DefaultBufferAllocators;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.nio.NioIoHandler;
import io.netty5.channel.socket.DatagramPacket;
import io.netty5.channel.socket.DomainSocketAddress;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty5.CancelReceiverHandlerTest;
import reactor.netty5.Connection;
import reactor.netty5.LogTracker;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.resources.LoopResources;
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
				                                            if (o instanceof DatagramPacket received) {
				                                                Buffer buffer = received.content();
				                                                System.out.println("Server received " + buffer.toString(StandardCharsets.UTF_8));
				                                                Buffer buf1 = out.alloc().copyOf("echo ".getBytes(StandardCharsets.UTF_8));
				                                                CompositeBuffer buf2 = out.alloc().compose(List.of(buf1.send(), buffer.send()));
				                                                return new DatagramPacket(buf2, received.sender());
				                                            }
				                                            else {
				                                                return Mono.error(new Exception());
				                                            }
				                                    })
				                                    .flatMap(o -> out.sendObject(o)))
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
		EventLoopGroup loop = new MultithreadEventLoopGroup(1, NioIoHandler.newFactory());
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
					.asStage().get(5, TimeUnit.SECONDS);
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
					                                    if (o instanceof DatagramPacket received) {
					                                        Buffer buffer = received.content();
					                                        System.out.println("Server received " + buffer.toString(StandardCharsets.UTF_8));
					                                        Buffer buf1 = out.alloc().copyOf("echo ".getBytes(StandardCharsets.UTF_8));
					                                        CompositeBuffer buf2 = out.alloc().compose(List.of(buf1.send(), buffer.send()));
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

	@Test
	void testUdpClientCancelled() throws InterruptedException {
		LoopResources resources = LoopResources.create("testUdpClientCancelled");
		Connection server = null;
		Connection client = null;

		try (LogTracker lt = new LogTracker(ChannelOperations.class,
				"Channel inbound receiver cancelled (operation cancelled).",
				"Channel inbound receiver cancelled (subscription disposed).")) {
			Sinks.Empty<Void> empty = Sinks.empty();
			CountDownLatch cancelled = new CountDownLatch(1);
			CancelReceiverHandlerTest cancelReceiver = new CancelReceiverHandlerTest(empty::tryEmitEmpty);

			server = UdpServer.create()
					.port(0)
					.wiretap(true)
					.runOn(resources)
					.handle((in, out) -> in.receiveObject()
							.log("server.receiveObject")
							.map(o -> {
								if (o instanceof DatagramPacket received) {
									Buffer pong = DefaultBufferAllocators.preferredAllocator().copyOf("pong", StandardCharsets.UTF_8);
									return new DatagramPacket(pong, received.sender());
								}
								else {
									return Mono.error(new Exception());
								}
							})
							.flatMap(out::sendObject))
					.bind()
					.block(Duration.ofSeconds(30));
			assertThat(server).isNotNull();

			InetSocketAddress address = (InetSocketAddress) server.address();
			client = UdpClient.create()
					.port(address.getPort())
					.wiretap(true)
					.runOn(resources)
					.doOnConnected(conn -> conn.addHandlerFirst(cancelReceiver))
					.handle((in, out) -> {
						Mono<Void> receive = in.receive()
								.asString()
								.log("client.receive")
								.doOnCancel(cancelled::countDown)
								.then();

						out.sendString(Mono.just("ping"))
								.then()
								.subscribe();

						return Flux.zip(receive, empty.asMono())
								.log("zip")
								.then(Mono.never());
					})
					.connect()
					.block(Duration.ofSeconds(30));

			assertThat(cancelled.await(30, TimeUnit.SECONDS)).as("cancelled await").isTrue();
			assertThat(cancelReceiver.awaitAllReleased(30)).as("cancelReceiver").isTrue();

			server.disposeNow();
			server = null;
			assertThat(lt.latch.await(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			if (server != null) {
				server.disposeNow();
			}
			if (client != null) {
				client.disposeNow();
			}
			resources.disposeLater()
					.block(Duration.ofSeconds(30));
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
