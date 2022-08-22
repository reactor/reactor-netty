/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5;

import io.netty5.channel.AbstractChannel;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelOutboundBuffer;
import io.netty5.channel.ChannelShutdownDirection;
import io.netty5.channel.EventLoop;
import io.netty5.channel.IoHandle;
import io.netty5.channel.WriteHandleFactory;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import reactor.util.annotation.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Violeta Georgieva
 */
class ReactorNettyTest {

	/**
	 * Original channel string: [id: 0x1320c27a]
	 * Expected channel string: [1320c27a]
	 */
	@Test
	void testFormatChannel() {
		doTestFormatChannel(new TestChannel(new EmbeddedChannel()));
	}

	/**
	 * Original channel string: [id: 0x2f07a7a0, L:127.0.0.1:8080]
	 * Expected channel string: [2f07a7a0, L:127.0.0.1:8080]
	 */
	@Test
	void testFormatChannelWithLocalAddress() {
		doTestFormatChannel(new TestChannel(new EmbeddedChannel(),
				InetSocketAddress.createUnresolved("127.0.0.1", 8080)));
	}

	/**
	 * Original channel string: [id: 0x9ed4b854, L:127.0.0.1:8080 - R:127.0.0.1:9090]
	 * Expected channel string: [9ed4b854, L:127.0.0.1:8080 - R:127.0.0.1:9090]
	 */
	@Test
	void testFormatChannelWithLocalAndRemoteAddresses() {
		doTestFormatChannel(new TestChannel(new EmbeddedChannel(),
				InetSocketAddress.createUnresolved("127.0.0.1", 8080),
				InetSocketAddress.createUnresolved("127.0.0.1", 9090)));
	}

	/**
	 * Original channel id: ()[id: 0x1320c27a]
	 * Expected channel id: ()[1320c27a]
	 */
	@Test
	void testFormatChildChannel() {
		doTestFormatChannel(new ChildTestChannel(new TestChannel(new EmbeddedChannel())));
	}

	/**
	 * Original channel id: ()[id: 0x867c3169, L:127.0.0.1:8080]
	 * Expected channel id: ()[867c3169, L:127.0.0.1:8080]
	 */
	@Test
	void testFormatChildChannelWithLocalAddresses() {
		doTestFormatChannel(new ChildTestChannel(new TestChannel(new EmbeddedChannel()),
				InetSocketAddress.createUnresolved("127.0.0.1", 8080)));
	}

	/**
	 * Original channel id: ()[id: 0x43f4ed87, L:127.0.0.1:8080 - R:127.0.0.1:9090]
	 * Expected channel id: ()[43f4ed87, L:127.0.0.1:8080 - R:127.0.0.1:9090]
	 */
	@Test
	void testFormatChildChannelWithLocalAndRemoteAddresses() {
		doTestFormatChannel(new ChildTestChannel(new TestChannel(new EmbeddedChannel()),
				InetSocketAddress.createUnresolved("127.0.0.1", 8080),
				InetSocketAddress.createUnresolved("127.0.0.1", 9090)));
	}

	private void doTestFormatChannel(TestChannel channel) {
		channel.active = true;
		String channelStr = channel.toString();
		assertThat(ReactorNetty.format(channel, "testFormatWithChannel"))
				.isEqualTo(channelStr.replace(ReactorNetty.ORIGINAL_CHANNEL_ID_PREFIX, "[") + " testFormatWithChannel");
	}

	static class TestChannel extends AbstractChannel<Channel, SocketAddress, SocketAddress> {

		final SocketAddress localAddress;
		final SocketAddress remoteAddress;

		boolean active;

		TestChannel(Channel parent) {
			this(parent, null, null);
		}

		TestChannel(Channel parent, @Nullable SocketAddress localAddress) {
			this(parent, localAddress, null);
		}

		TestChannel(Channel parent, @Nullable SocketAddress localAddress, @Nullable SocketAddress remoteAddress) {
			super(parent, new TestEventLoop(), false);
			this.localAddress = localAddress;
			this.remoteAddress = remoteAddress;
		}

		@Override
		protected SocketAddress localAddress0() {
			return localAddress;
		}

		@Override
		protected SocketAddress remoteAddress0() {
			return remoteAddress;
		}

		@Override
		protected void doBind(SocketAddress localAddress) {
		}

		@Override
		protected void doDisconnect() {
		}

		@Override
		protected void doClose() {
		}

		@Override
		protected void doShutdown(ChannelShutdownDirection direction) {
		}

		@Override
		protected void doRead(boolean wasReadPendingAlready) {
		}

		@Override
		protected boolean doReadNow(AbstractChannel<Channel, SocketAddress, SocketAddress>.ReadSink readSink) {
			return false;
		}

		@Override
		protected void doWrite(ChannelOutboundBuffer channelOutboundBuffer, WriteHandleFactory.WriteHandle writeHandle) throws Exception {
		}

		@Override
		protected boolean doConnect(SocketAddress socketAddress, SocketAddress socketAddress1) {
			return false;
		}

		@Override
		protected boolean doFinishConnect(SocketAddress socketAddress) {
			return false;
		}

		@Override
		public boolean isOpen() {
			return false;
		}

		@Override
		public boolean isActive() {
			return active;
		}

		@Override
		public boolean isShutdown(ChannelShutdownDirection direction) {
			return false;
		}
	}

	static final class TestEventLoop implements EventLoop {

		@Override
		public Future<Void> registerForIo(IoHandle handle) {
			return null;
		}

		@Override
		public Future<Void> deregisterForIo(IoHandle handle) {
			return null;
		}

		@Override
		public boolean isCompatible(Class<? extends IoHandle> handleType) {
			return true;
		}

		@Override
		public boolean inEventLoop(Thread thread) {
			return false;
		}

		@Override
		public boolean isShuttingDown() {
			return false;
		}

		@Override
		public boolean isShutdown() {
			return false;
		}

		@Override
		public Future<Void> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
			return null;
		}

		@Override
		public Future<Void> terminationFuture() {
			return null;
		}

		@Override
		public Future<Void> submit(Runnable task) {
			return null;
		}

		@Override
		public <T> Future<T> submit(Runnable task, T result) {
			return null;
		}

		@Override
		public <T> Future<T> submit(Callable<T> task) {
			return null;
		}

		@Override
		public Future<Void> schedule(Runnable task, long delay, TimeUnit unit) {
			return null;
		}

		@Override
		public <V> Future<V> schedule(Callable<V> task, long delay, TimeUnit unit) {
			return null;
		}

		@Override
		public Future<Void> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
			return null;
		}

		@Override
		public Future<Void> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
			return null;
		}

		@Override
		public void execute(Runnable task) {

		}
	}

	static final class ChildTestChannel extends TestChannel {

		ChildTestChannel(Channel parent) {
			this(parent, null, null);
		}

		ChildTestChannel(Channel parent, @Nullable SocketAddress localAddress) {
			this(parent, localAddress, null);
		}

		ChildTestChannel(Channel parent, @Nullable SocketAddress localAddress, @Nullable SocketAddress remoteAddress) {
			super(parent, localAddress, remoteAddress);
		}

		@Override
		public String toString() {
			// quic channel string looks like (0d764edf1d7fbf6651fdedc08ed44c69efbaeee1)[id: 5dc8071b]
			// the id inside "()" is a trace id
			return "()" + super.toString();
		}
	}
}
