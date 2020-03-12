/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.tcp;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class BlockingConnectionTest {

	static final Connection NEVER_STOP_CONTEXT = new Connection() {
		@Override
		public Channel channel() {
			return new EmbeddedChannel();
		}

		@Override
		public InetSocketAddress address() {
			return InetSocketAddress.createUnresolved("localhost", 4321);
		}

		@Override
		public Mono<Void> onDispose() {
			return Mono.never();
		}
	};

	static final DisposableServer NEVER_STOP_SERVER = new DisposableServer() {
		@Override
		public Channel channel() {
			return new EmbeddedChannel();
		}

		@Override
		public InetSocketAddress address() {
			return InetSocketAddress.createUnresolved("localhost", 4321);
		}

		@Override
		public Mono<Void> onDispose() {
			return Mono.never();
		}
	};

	@Test
	public void simpleServerFromAsyncServer() throws InterruptedException {
		DisposableServer simpleServer =
				TcpServer.create()
				         .handle((in, out) -> out
						         .sendString(
								         in.receive()
								           .asString()
								           .takeUntil(s -> s.endsWith("CONTROL"))
								           .map(s -> "ECHO: " + s.replaceAll("CONTROL", ""))
								           .concatWith(Mono.just("DONE"))
						         )
						         .neverComplete()
				         )
				         .wiretap(true)
				         .bindNow();

		System.out.println(simpleServer.address().getHostString());
		System.out.println(simpleServer.address().getPort());

		AtomicReference<List<String>> data1 = new AtomicReference<>();
		AtomicReference<List<String>> data2 = new AtomicReference<>();

		Connection simpleClient1 =
				TcpClient.create().port(simpleServer.address().getPort())
				         .handle((in, out) -> out.sendString(Flux.just("Hello", "World", "CONTROL"))
				                                 .then(in.receive()
				                                        .asString()
				                                        .takeUntil(s -> s.endsWith("DONE"))
				                                        .map(s -> s.replaceAll("DONE", ""))
				                                        .filter(s -> !s.isEmpty())
				                                        .collectList()
				                                        .doOnNext(data1::set)
				                                        .doOnNext(System.err::println)
				                                        .then()))
				         .wiretap(true)
				         .connectNow();

		Connection simpleClient2 =
				TcpClient.create()
				         .port(simpleServer.address().getPort())
				         .handle((in, out) -> out.sendString(Flux.just("How", "Are", "You?", "CONTROL"))
				                                 .then(in.receive()
				                                        .asString()
				                                        .takeUntil(s -> s.endsWith("DONE"))
				                                        .map(s -> s.replaceAll("DONE", ""))
				                                        .filter(s -> !s.isEmpty())
				                                        .collectList()
				                                        .doOnNext(data2::set)
				                                        .doOnNext(System.err::println)
				                                        .then()))
				         .wiretap(true)
				         .connectNow();

		Thread.sleep(100);
		System.err.println("STOPPING 1");
		simpleClient1.disposeNow();

		System.err.println("STOPPING 2");
		simpleClient2.disposeNow();

		System.err.println("STOPPING SERVER");
		simpleServer.disposeNow();

		assertThat(data1.get())
				.allSatisfy(s -> assertThat(s).startsWith("ECHO: "));
		assertThat(data2.get())
				.allSatisfy(s -> assertThat(s).startsWith("ECHO: "));

		assertThat(data1.get()
		                .toString()
		                .replaceAll("ECHO: ", "")
		                .replaceAll(", ", ""))
				.isEqualTo("[HelloWorld]");
		assertThat(data2.get()
		                .toString()
		                .replaceAll("ECHO: ", "")
		                .replaceAll(", ", ""))
		.isEqualTo("[HowAreYou?]");
	}

	@Test
	public void testTimeoutOnStart() {
		TcpClient neverStart = new TcpClient(){
			@Override
			public Mono<? extends Connection> connect(Bootstrap b) {
				return Mono.never();
			}
		};

		assertThatExceptionOfType(IllegalStateException.class)
				.isThrownBy(() -> neverStart.connectNow(Duration.ofMillis(100)))
				.withMessage("TcpClient couldn't be started within 100ms");
	}

	@Test
	public void testTimeoutOnStop() {
		Connection c = new TcpClient(){
			@Override
			public Mono<? extends Connection> connect(Bootstrap b) {
				return Mono.just(NEVER_STOP_CONTEXT);
			}
		}.connectNow();

		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(() -> c.disposeNow(Duration.ofMillis(100)))
				.withMessage("Socket couldn't be stopped within 100ms");
	}

	@Test
	public void getContextAddressAndHost() {
		DisposableServer c = new TcpServer(){
			@Override
			public Mono<? extends DisposableServer> bind(ServerBootstrap b) {
				return Mono.just(NEVER_STOP_SERVER);
			}

			@Override
			public ServerBootstrap configure() {
				return TcpServerBind.INSTANCE.createServerBootstrap();
			}
		}.bindNow();

		assertThat(c).isSameAs(NEVER_STOP_SERVER);
		assertThat(c.port()).isEqualTo(NEVER_STOP_CONTEXT.address().getPort());
		assertThat(c.host()).isEqualTo(NEVER_STOP_CONTEXT.address().getHostString());
	}
}