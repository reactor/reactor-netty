/*
 * Copyright (c) 2017-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.incubator.channel.uring.IOUring;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.incubator.channel.uring.IOUringDatagramChannel;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringServerSocketChannel;
import io.netty.incubator.channel.uring.IOUringSocketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpResources;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class DefaultLoopResourcesTest {

	@Test
	void disposeLaterDefers() {
		DefaultLoopResources loopResources = new DefaultLoopResources(
				"test", 0, false);

		Mono<Void> disposer = loopResources.disposeLater();
		assertThat(loopResources.isDisposed()).isFalse();

		disposer.subscribe();
		assertThat(loopResources.isDisposed()).isTrue();
	}

	@Test
	void disposeLaterSubsequentIsQuick() {
		DefaultLoopResources loopResources = new DefaultLoopResources(
				"test", 0, false);
		loopResources.onServer(true);

		assertThat(loopResources.isDisposed()).isFalse();

		Duration firstInvocation = StepVerifier.create(loopResources.disposeLater())
		                                       .verifyComplete();
		assertThat(loopResources.isDisposed()).isTrue();
		if (!LoopResources.hasNativeSupport()) {
			assertThat(loopResources.serverLoops.get().isTerminated()).isTrue();
		}
		else {
			assertThat(loopResources.cacheNativeServerLoops.get().isTerminated()).isTrue();
		}

		Duration secondInvocation = StepVerifier.create(loopResources.disposeLater())
		                                        .verifyComplete();

		assertThat(secondInvocation).isLessThan(firstInvocation);
	}

	@Test
	void testIssue416() {
		TestResources resources = TestResources.get();

		TestResources.set(ConnectionProvider.create("testIssue416"));
		assertThat(resources.provider.isDisposed()).isTrue();
		assertThat(resources.loops.isDisposed()).isFalse();

		TestResources.set(LoopResources.create("test"));
		assertThat(resources.loops.isDisposed()).isTrue();

		assertThat(resources.isDisposed()).isTrue();
	}

	static final class TestResources extends TcpResources {
		final LoopResources loops;
		final ConnectionProvider provider;

		TestResources(LoopResources defaultLoops, ConnectionProvider defaultProvider) {
			super(defaultLoops, defaultProvider);
			this.loops = defaultLoops;
			this.provider = defaultProvider;
		}

		public static TestResources get() {
			return getOrCreate(testResources, null, null, TestResources::new,  "test");
		}
		public static TestResources set(LoopResources loops) {
			return getOrCreate(testResources, loops, null, TestResources::new, "test");
		}
		public static TestResources set(ConnectionProvider pools) {
			return getOrCreate(testResources, null, pools, TestResources::new, "test");
		}

		static final AtomicReference<TestResources> testResources = new AtomicReference<>();
	}

	@Test
	void testClientTransportWarmupNative() throws Exception {
		testClientTransportWarmup(true);
	}

	@Test
	void testClientTransportWarmupNio() throws Exception {
		testClientTransportWarmup(false);
	}

	private static void testClientTransportWarmup(boolean preferNative) throws Exception {
		final DefaultLoopResources loop1 =
				(DefaultLoopResources) LoopResources.create("testClientTransportWarmup", 1, true);
		final EventLoopGroup loop2 = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
		try {
			TcpClient tcpClient = TcpClient.create()
			                               .resolver(spec -> spec.runOn(loop2))
			                               .runOn(loop1, preferNative);

			Mono<Void> warmupMono = tcpClient.warmup();

			assertThat(loop1.cacheNativeClientLoops.get()).isNull();
			assertThat(loop1.clientLoops.get()).isNull();

			warmupMono.block(Duration.ofSeconds(5));

			if (preferNative && LoopResources.hasNativeSupport()) {
				assertThat(loop1.cacheNativeClientLoops.get()).isNotNull();
				assertThat(loop1.clientLoops.get()).isNull();
			}
			else {
				assertThat(loop1.cacheNativeClientLoops.get()).isNull();
				assertThat(loop1.clientLoops.get()).isNotNull();
			}
		}
		finally {
			loop1.disposeLater()
			     .block(Duration.ofSeconds(5));
			loop2.shutdownGracefully()
			     .get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void testServerTransportWarmupNative() {
		testServerTransportWarmup(true);
	}

	@Test
	void testServerTransportWarmupNio() {
		testServerTransportWarmup(false);
	}

	private static void testServerTransportWarmup(boolean preferNative) {
		final DefaultLoopResources loop =
				(DefaultLoopResources) LoopResources.create("testServerTransportWarmup", 1, true);
		try {
			TcpServer tcpServer = TcpServer.create()
			                               .runOn(loop, preferNative);

			Mono<Void> warmupMono = tcpServer.warmup();

			assertThat(loop.cacheNativeServerLoops.get()).isNull();
			assertThat(loop.cacheNativeSelectLoops.get()).isNull();
			assertThat(loop.serverLoops.get()).isNull();
			assertThat(loop.serverSelectLoops.get()).isNull();

			warmupMono.block(Duration.ofSeconds(5));

			if (preferNative && LoopResources.hasNativeSupport()) {
				assertThat(loop.cacheNativeServerLoops.get()).isNotNull();
				assertThat(loop.cacheNativeSelectLoops.get()).isNotNull();
				assertThat(loop.serverLoops.get()).isNull();
				assertThat(loop.serverSelectLoops.get()).isNull();
			}
			else {
				assertThat(loop.cacheNativeServerLoops.get()).isNull();
				assertThat(loop.cacheNativeSelectLoops.get()).isNull();
				assertThat(loop.serverLoops.get()).isNotNull();
				assertThat(loop.serverSelectLoops.get()).isNotNull();
			}
		}
		finally {
			loop.disposeLater()
			    .block(Duration.ofSeconds(5));
		}
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	@EnabledIf("isNativeTransport")
	void testEpollIsAvailable() {
		assertThat(Epoll.isAvailable()).isTrue();
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	@EnabledOnJre(JRE.JAVA_8)
	@EnabledIf("isIoUringTransport")
	void testIoUringIncubatorIsAvailableOnJava8() {
		assertThat(IOUring.isAvailable()).isTrue();
	}

	@Test
	@EnabledOnOs(OS.MAC)
	@EnabledIf("isNativeTransport")
	void testKQueueIsAvailable() {
		assertThat(KQueue.isAvailable()).isTrue();
	}

	// ============== Epoll Transport Tests (Linux) ==============

	@ParameterizedTest
	@MethodSource("epollEventLoopGroups")
	@EnabledOnOs(OS.LINUX)
	@EnabledIf("isEpollAvailable")
	void testOnChannelWithEpollEventLoopGroup(EventLoopGroup epollGroup) throws Exception {
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelEpoll");
			try {
				// Verify onChannel returns correct Epoll channel instances
				SocketChannel socketChannel = loopResources.onChannel(SocketChannel.class, epollGroup);
				assertThat(socketChannel).isInstanceOf(EpollSocketChannel.class);

				ServerSocketChannel serverSocketChannel = loopResources.onChannel(ServerSocketChannel.class, epollGroup);
				assertThat(serverSocketChannel).isInstanceOf(EpollServerSocketChannel.class);

				DatagramChannel datagramChannel = loopResources.onChannel(DatagramChannel.class, epollGroup);
				assertThat(datagramChannel).isInstanceOf(EpollDatagramChannel.class);
			}
			finally {
				loopResources.disposeLater().block(Duration.ofSeconds(5));
			}
		}
		finally {
			epollGroup.shutdownGracefully().get(5, TimeUnit.SECONDS);
		}
	}

	@ParameterizedTest
	@MethodSource("epollEventLoopGroups")
	@EnabledOnOs(OS.LINUX)
	@EnabledIf("isEpollAvailable")
	void testOnChannelClassWithEpollEventLoopGroup(EventLoopGroup epollGroup) throws Exception {
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelClassEpoll");
			try {
				// Verify onChannelClass returns correct Epoll channel classes
				Class<? extends SocketChannel> socketChannelClass = loopResources.onChannelClass(SocketChannel.class, epollGroup);
				assertThat(socketChannelClass).isEqualTo(EpollSocketChannel.class);

				Class<? extends ServerSocketChannel> serverSocketChannelClass = loopResources.onChannelClass(ServerSocketChannel.class, epollGroup);
				assertThat(serverSocketChannelClass).isEqualTo(EpollServerSocketChannel.class);

				Class<? extends DatagramChannel> datagramChannelClass = loopResources.onChannelClass(DatagramChannel.class, epollGroup);
				assertThat(datagramChannelClass).isEqualTo(EpollDatagramChannel.class);
			}
			finally {
				loopResources.disposeLater().block(Duration.ofSeconds(5));
			}
		}
		finally {
			epollGroup.shutdownGracefully().get(5, TimeUnit.SECONDS);
		}
	}

	// ============== KQueue Transport Tests (macOS) ==============

	@ParameterizedTest
	@MethodSource("kqueueEventLoopGroups")
	@EnabledOnOs(OS.MAC)
	@EnabledIf("isKQueueAvailable")
	void testOnChannelWithKQueueEventLoopGroup(EventLoopGroup kqueueGroup) throws Exception {
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelKQueue");
			try {
				// Verify onChannel returns correct KQueue channel instances
				SocketChannel socketChannel = loopResources.onChannel(SocketChannel.class, kqueueGroup);
				assertThat(socketChannel).isInstanceOf(KQueueSocketChannel.class);

				ServerSocketChannel serverSocketChannel = loopResources.onChannel(ServerSocketChannel.class, kqueueGroup);
				assertThat(serverSocketChannel).isInstanceOf(KQueueServerSocketChannel.class);

				DatagramChannel datagramChannel = loopResources.onChannel(DatagramChannel.class, kqueueGroup);
				assertThat(datagramChannel).isInstanceOf(KQueueDatagramChannel.class);
			}
			finally {
				loopResources.disposeLater().block(Duration.ofSeconds(5));
			}
		}
		finally {
			kqueueGroup.shutdownGracefully().get(5, TimeUnit.SECONDS);
		}
	}

	@ParameterizedTest
	@MethodSource("kqueueEventLoopGroups")
	@EnabledOnOs(OS.MAC)
	@EnabledIf("isKQueueAvailable")
	void testOnChannelClassWithKQueueEventLoopGroup(EventLoopGroup kqueueGroup) throws Exception {
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelClassKQueue");
			try {
				// Verify onChannelClass returns correct KQueue channel classes
				Class<? extends SocketChannel> socketChannelClass = loopResources.onChannelClass(SocketChannel.class, kqueueGroup);
				assertThat(socketChannelClass).isEqualTo(KQueueSocketChannel.class);

				Class<? extends ServerSocketChannel> serverSocketChannelClass = loopResources.onChannelClass(ServerSocketChannel.class, kqueueGroup);
				assertThat(serverSocketChannelClass).isEqualTo(KQueueServerSocketChannel.class);

				Class<? extends DatagramChannel> datagramChannelClass = loopResources.onChannelClass(DatagramChannel.class, kqueueGroup);
				assertThat(datagramChannelClass).isEqualTo(KQueueDatagramChannel.class);
			}
			finally {
				loopResources.disposeLater().block(Duration.ofSeconds(5));
			}
		}
		finally {
			kqueueGroup.shutdownGracefully().get(5, TimeUnit.SECONDS);
		}
	}

	// ============== io_uring Transport Tests (Linux, Java 8 incubator) ==============

	@Test
	@EnabledOnOs(OS.LINUX)
	@EnabledOnJre(JRE.JAVA_8)
	@EnabledIf("isIoUringAvailable")
	void testOnChannelWithIoUringIncubatorEventLoopGroup() throws Exception {
		EventLoopGroup ioUringGroup = new IOUringEventLoopGroup(1);
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelIoUringIncubator");
			try {
				// Verify onChannel returns correct io_uring incubator channel instances (Java 8)
				SocketChannel socketChannel = loopResources.onChannel(SocketChannel.class, ioUringGroup);
				assertThat(socketChannel).isInstanceOf(IOUringSocketChannel.class);

				ServerSocketChannel serverSocketChannel = loopResources.onChannel(ServerSocketChannel.class, ioUringGroup);
				assertThat(serverSocketChannel).isInstanceOf(IOUringServerSocketChannel.class);

				DatagramChannel datagramChannel = loopResources.onChannel(DatagramChannel.class, ioUringGroup);
				assertThat(datagramChannel).isInstanceOf(IOUringDatagramChannel.class);
			}
			finally {
				loopResources.disposeLater().block(Duration.ofSeconds(5));
			}
		}
		finally {
			ioUringGroup.shutdownGracefully().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	@EnabledOnJre(JRE.JAVA_8)
	@EnabledIf("isIoUringAvailable")
	void testOnChannelClassWithIoUringIncubatorEventLoopGroup() throws Exception {
		EventLoopGroup ioUringGroup = new IOUringEventLoopGroup(1);
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelClassIoUringIncubator");
			try {
				// Verify onChannelClass returns correct io_uring incubator channel classes (Java 8)
				Class<? extends SocketChannel> socketChannelClass = loopResources.onChannelClass(SocketChannel.class, ioUringGroup);
				assertThat(socketChannelClass).isEqualTo(IOUringSocketChannel.class);

				Class<? extends ServerSocketChannel> serverSocketChannelClass = loopResources.onChannelClass(ServerSocketChannel.class, ioUringGroup);
				assertThat(serverSocketChannelClass).isEqualTo(IOUringServerSocketChannel.class);

				Class<? extends DatagramChannel> datagramChannelClass = loopResources.onChannelClass(DatagramChannel.class, ioUringGroup);
				assertThat(datagramChannelClass).isEqualTo(IOUringDatagramChannel.class);
			}
			finally {
				loopResources.disposeLater().block(Duration.ofSeconds(5));
			}
		}
		finally {
			ioUringGroup.shutdownGracefully().get(5, TimeUnit.SECONDS);
		}
	}

	// ============== NIO Transport Tests (All Platforms) ==============

	@ParameterizedTest
	@MethodSource("nioEventLoopGroups")
	void testOnChannelWithNioEventLoopGroup(EventLoopGroup nioGroup) throws Exception {
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelNio");
			try {
				// Verify onChannel returns correct NIO channel instances
				SocketChannel socketChannel = loopResources.onChannel(SocketChannel.class, nioGroup);
				assertThat(socketChannel).isInstanceOf(NioSocketChannel.class);

				ServerSocketChannel serverSocketChannel = loopResources.onChannel(ServerSocketChannel.class, nioGroup);
				assertThat(serverSocketChannel).isInstanceOf(NioServerSocketChannel.class);

				DatagramChannel datagramChannel = loopResources.onChannel(DatagramChannel.class, nioGroup);
				assertThat(datagramChannel).isInstanceOf(NioDatagramChannel.class);
			}
			finally {
				loopResources.disposeLater().block(Duration.ofSeconds(5));
			}
		}
		finally {
			nioGroup.shutdownGracefully().get(5, TimeUnit.SECONDS);
		}
	}

	@ParameterizedTest
	@MethodSource("nioEventLoopGroups")
	void testOnChannelClassWithNioEventLoopGroup(EventLoopGroup nioGroup) throws Exception {
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelClassNio");
			try {
				// Verify onChannelClass returns correct NIO channel classes
				Class<? extends SocketChannel> socketChannelClass = loopResources.onChannelClass(SocketChannel.class, nioGroup);
				assertThat(socketChannelClass).isEqualTo(NioSocketChannel.class);

				Class<? extends ServerSocketChannel> serverSocketChannelClass = loopResources.onChannelClass(ServerSocketChannel.class, nioGroup);
				assertThat(serverSocketChannelClass).isEqualTo(NioServerSocketChannel.class);

				Class<? extends DatagramChannel> datagramChannelClass = loopResources.onChannelClass(DatagramChannel.class, nioGroup);
				assertThat(datagramChannelClass).isEqualTo(NioDatagramChannel.class);
			}
			finally {
				loopResources.disposeLater().block(Duration.ofSeconds(5));
			}
		}
		finally {
			nioGroup.shutdownGracefully().get(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void testForGroupWithUnsupportedEventLoopGroup() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> DefaultLoopNativeDetector.forGroup(mock(EventLoopGroup.class)))
				.withMessageStartingWith("Unsupported event loop group");
	}

	static boolean isNativeTransport() {
		return "native".equals(System.getProperty("forceTransport"));
	}

	static boolean isEpollAvailable() {
		return isNativeTransport() && Epoll.isAvailable();
	}

	@SuppressWarnings("deprecation")
	static Stream<Arguments> epollEventLoopGroups() {
		return Stream.of(
				arguments(new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory())),
				arguments(new EpollEventLoopGroup(1))
		);
	}

	static boolean isKQueueAvailable() {
		return isNativeTransport() && KQueue.isAvailable();
	}

	@SuppressWarnings("deprecation")
	static Stream<Arguments> kqueueEventLoopGroups() {
		return Stream.of(
				arguments(new MultiThreadIoEventLoopGroup(1, KQueueIoHandler.newFactory())),
				arguments(new KQueueEventLoopGroup(1))
		);
	}

	static boolean isIoUringTransport() {
		return "io_uring".equals(System.getProperty("forceTransport"));
	}

	static boolean isIoUringAvailable() {
		return isIoUringTransport() && IOUring.isAvailable();
	}

	@SuppressWarnings("deprecation")
	static Stream<Arguments> nioEventLoopGroups() {
		return Stream.of(
				arguments(new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())),
				arguments(new NioEventLoopGroup(1))
		);
	}
}
