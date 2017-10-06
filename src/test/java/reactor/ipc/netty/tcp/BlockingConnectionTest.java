/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.tcp;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyPipeline;

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
		public Mono<Void> onClose() {
			return Mono.never();
		}
	};

	static final Connection IMMEDIATE_STOP_CONTEXT = new Connection() {
		@Override
		public Channel channel() {
			return new EmbeddedChannel();
		}

		@Override
		public InetSocketAddress address() {
			return InetSocketAddress.createUnresolved("localhost", 4321);
		}

		@Override
		public Mono<Void> onClose() {
			return Mono.empty();
		}
	};

	@Test
	public void simpleServerFromAsyncServer() throws InterruptedException {
		BlockingNettyContext simpleServer =
				TcpServer.create()
				         .start((in, out) -> out
						         .options(NettyPipeline.SendOptions::flushOnEach)
						         .sendString(
								         in.receive()
								           .asString()
								           .takeUntil(s -> s.endsWith("CONTROL"))
								           .map(s -> "ECHO: " + s.replaceAll("CONTROL", ""))
								           .concatWith(Mono.just("DONE"))
						         )
						         .neverComplete()
				         );

		System.out.println(simpleServer.getHost());
		System.out.println(simpleServer.getPort());

		AtomicReference<List<String>> data1 = new AtomicReference<>();
		AtomicReference<List<String>> data2 = new AtomicReference<>();

		BlockingNettyContext simpleClient1 =
				TcpClient.create(simpleServer.getPort())
				         .start((in, out) -> out.options(NettyPipeline.SendOptions::flushOnEach)
				                                .sendString(Flux.just("Hello", "World", "CONTROL"))
				                                .then(in.receive()
				                                        .asString()
				                                        .takeUntil(s -> s.endsWith("DONE"))
				                                        .map(s -> s.replaceAll("DONE", ""))
				                                        .filter(s -> !s.isEmpty())
				                                        .collectList()
				                                        .doOnNext(data1::set)
				                                        .doOnNext(System.err::println)
				                                        .then()));

		BlockingNettyContext simpleClient2 =
				TcpClient.create(simpleServer.getPort())
				         .start((in, out) -> out.options(NettyPipeline.SendOptions::flushOnEach)
				                                .sendString(Flux.just("How", "Are", "You?", "CONTROL"))
				                                .then(in.receive()
				                                        .asString()
				                                        .takeUntil(s -> s.endsWith("DONE"))
				                                        .map(s -> s.replaceAll("DONE", ""))
				                                        .filter(s -> !s.isEmpty())
				                                        .collectList()
				                                        .doOnNext(data2::set)
				                                        .doOnNext(System.err::println)
				                                        .then()));

		Thread.sleep(100);
		System.err.println("STOPPING 1");
		simpleClient1.shutdown();

		System.err.println("STOPPING 2");
		simpleClient2.shutdown();

		System.err.println("STOPPING SERVER");
		simpleServer.shutdown();

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
		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(() -> new BlockingNettyContext(Mono.never(), "TEST NEVER START", Duration.ofMillis(100)))
				.withCauseExactlyInstanceOf(TimeoutException.class)
				.withMessage("java.util.concurrent.TimeoutException: TEST NEVER START couldn't be started within 100ms");
	}

	@Test
	public void testTimeoutOnStop() {
		final BlockingNettyContext neverStop =
				new BlockingNettyContext(Mono.just(NEVER_STOP_CONTEXT), "TEST NEVER STOP", Duration.ofMillis(100));

		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(neverStop::shutdown)
				.withCauseExactlyInstanceOf(TimeoutException.class)
				.withMessage("java.util.concurrent.TimeoutException: TEST NEVER STOP couldn't be stopped within 100ms");
	}

	@Test
	public void testTimeoutOnStopChangedTimeout() {
		final BlockingNettyContext neverStop =
				new BlockingNettyContext(Mono.just(NEVER_STOP_CONTEXT), "TEST NEVER STOP", Duration.ofMillis(500));

		neverStop.setLifecycleTimeout(Duration.ofMillis(100));

		assertThatExceptionOfType(RuntimeException.class)
				.isThrownBy(neverStop::shutdown)
				.withCauseExactlyInstanceOf(TimeoutException.class)
				.withMessage("java.util.concurrent.TimeoutException: TEST NEVER STOP couldn't be stopped within 100ms");
	}

	@Test
	public void getContextAddressAndHost() {
		BlockingNettyContext
				facade = new BlockingNettyContext(Mono.just(NEVER_STOP_CONTEXT), "foo");

		assertThat(facade.getContext()).isSameAs(NEVER_STOP_CONTEXT);
		assertThat(facade.getPort()).isEqualTo(NEVER_STOP_CONTEXT.address().getPort());
		assertThat(facade.getHost()).isEqualTo(NEVER_STOP_CONTEXT.address().getHostString());
	}

	@Test
	public void shutdownHookDeregisteredOnShutdown() {
		BlockingNettyContext facade =
				new BlockingNettyContext(Mono.just(IMMEDIATE_STOP_CONTEXT), "test");

		facade.installShutdownHook();
		Thread hook = facade.getShutdownHook();

		assertThat(hook).isNotNull();

		facade.shutdown();

		assertThat(Runtime.getRuntime().removeShutdownHook(hook))
				.withFailMessage("hook wasn't deregistered by shutdown")
				.isFalse();
		assertThat(facade.getShutdownHook())
				.withFailMessage("hook reference wasn't nulled by shutdown")
				.isNull();
	}

	@Test
	public void installShutdownHookTwice() {
		BlockingNettyContext facade =
				new BlockingNettyContext(Mono.just(IMMEDIATE_STOP_CONTEXT), "test");

		facade.installShutdownHook();
		Thread hook1 = facade.getShutdownHook();

		facade.installShutdownHook();
		Thread hook2 = facade.getShutdownHook();

		assertThat(hook1).isSameAs(hook2);

		facade.shutdown();

		assertThat(facade.getShutdownHook())
				.withFailMessage("hook1 wasn't nulled out by shutdown")
				.isNull();
		assertThat(Runtime.getRuntime().removeShutdownHook(hook1))
				.withFailMessage("hook1 wasn't deregistered by shutdown")
				.isFalse();
	}

	@Test
	public void smokeTestShutdownHook() {
		BlockingNettyContext simpleServer =
				TcpServer.create()
				         .start((in, out) -> out
						         .options(NettyPipeline.SendOptions::flushOnEach)
						         .sendString(
								         in.receive()
								           .asString()
								           .takeUntil(s -> s.endsWith("CONTROL"))
								           .map(s -> "ECHO: " + s.replaceAll("CONTROL", ""))
								           .concatWith(Mono.just("DONE"))
						         )
						         .neverComplete()
				         );

		simpleServer.installShutdownHook();
		simpleServer.getShutdownHook().setName("BlockingConnectionTest.smokeTestShutdownHook");
		//this test doesn't assert anything, but look out for JVM shutdown hook messages
	}
}