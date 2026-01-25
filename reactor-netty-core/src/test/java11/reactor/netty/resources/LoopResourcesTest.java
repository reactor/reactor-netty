/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringDatagramChannel;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.channel.uring.IoUringSocketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class LoopResourcesTest {

	@Test
	@EnabledOnOs(OS.LINUX)
	void testIoUringIsAvailable() {
		assumeThat(System.getProperty("forceTransport")).isEqualTo("io_uring");
		assertThat(IoUring.isAvailable()).isTrue();
	}

	@Test
	@EnabledOnOs(OS.LINUX)
	void testOnChannelWithIoUringEventLoopGroup() throws Exception {
		assumeThat(System.getProperty("forceTransport")).isEqualTo("io_uring");
		assumeThat(IoUring.isAvailable()).isTrue();

		EventLoopGroup ioUringGroup = new MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory());
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelIoUring");
			try {
				// Verify onChannel returns correct io_uring channel instances (Java 11+)
				SocketChannel socketChannel = loopResources.onChannel(SocketChannel.class, ioUringGroup);
				assertThat(socketChannel).isInstanceOf(IoUringSocketChannel.class);

				ServerSocketChannel serverSocketChannel = loopResources.onChannel(ServerSocketChannel.class, ioUringGroup);
				assertThat(serverSocketChannel).isInstanceOf(IoUringServerSocketChannel.class);

				DatagramChannel datagramChannel = loopResources.onChannel(DatagramChannel.class, ioUringGroup);
				assertThat(datagramChannel).isInstanceOf(IoUringDatagramChannel.class);
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
	void testOnChannelClassWithIoUringEventLoopGroup() throws Exception {
		assumeThat(System.getProperty("forceTransport")).isEqualTo("io_uring");
		assumeThat(IoUring.isAvailable()).isTrue();

		EventLoopGroup ioUringGroup = new MultiThreadIoEventLoopGroup(1, IoUringIoHandler.newFactory());
		try {
			LoopResources loopResources = LoopResources.create("testOnChannelClassIoUring");
			try {
				// Verify onChannelClass returns correct io_uring channel classes (Java 11+)
				Class<? extends SocketChannel> socketChannelClass = loopResources.onChannelClass(SocketChannel.class, ioUringGroup);
				assertThat(socketChannelClass).isEqualTo(IoUringSocketChannel.class);

				Class<? extends ServerSocketChannel> serverSocketChannelClass = loopResources.onChannelClass(ServerSocketChannel.class, ioUringGroup);
				assertThat(serverSocketChannelClass).isEqualTo(IoUringServerSocketChannel.class);

				Class<? extends DatagramChannel> datagramChannelClass = loopResources.onChannelClass(DatagramChannel.class, ioUringGroup);
				assertThat(datagramChannelClass).isEqualTo(IoUringDatagramChannel.class);
			}
			finally {
				loopResources.disposeLater().block(Duration.ofSeconds(5));
			}
		}
		finally {
			ioUringGroup.shutdownGracefully().get(5, TimeUnit.SECONDS);
		}
	}
}
