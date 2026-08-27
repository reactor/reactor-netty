/*
 * Copyright (c) 2023-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.channel;

import java.time.Duration;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.test.subscriber.TestSubscriber;
import reactor.test.util.RaceTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class FluxReceiveTest {

	@Test
	void disposeAndSubscribeRaceTest() {
		for (int i = 0; i < 100; i++) {
			ChannelOperations<NettyInbound, NettyOutbound> operations =
					new ChannelOperations<>(EmbeddedChannel::new, (connection, newState) -> {
					});
			FluxReceive receive = new FluxReceive(operations);
			TestSubscriber<Object> subscriber = TestSubscriber.create();
			RaceTestUtils.race(receive::dispose, () -> receive.subscribe(subscriber));

			subscriber.block(Duration.ofSeconds(5));
		}
	}

	@Test
	void consumeImmediatelyReactorNettyReleases() {
		EmbeddedChannel channel = new EmbeddedChannel();
		try {
			ChannelOperations<?, ?> ops = bindReceiveOperations(channel);
			TestSubscriber<ByteBuf> subscriber = TestSubscriber.builder().initialRequest(0).build();
			ops.receive().subscribe(subscriber);

			ByteBuf first = Unpooled.buffer().writeByte(1);
			ByteBuf second = Unpooled.buffer().writeByte(2);
			channel.writeInbound(first, second);
			channel.runPendingTasks();

			assertThat(first.refCnt()).as("queued until demand, Reactor Netty owns").isOne();
			assertThat(second.refCnt()).isOne();

			subscriber.request(Long.MAX_VALUE);
			channel.runPendingTasks();

			assertThat(first.refCnt()).as("consume immediately: Reactor Netty released").isZero();
			assertThat(second.refCnt()).as("consume immediately: Reactor Netty released").isZero();
		}
		finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void retainThenApplicationReleases() {
		EmbeddedChannel channel = new EmbeddedChannel();
		try {
			ChannelOperations<?, ?> ops = bindReceiveOperations(channel);
			TestSubscriber<ByteBuf> subscriber = TestSubscriber.builder().initialRequest(0).build();
			ops.receive()
			   .retain()
			   .subscribe(subscriber);

			ByteBuf first = Unpooled.buffer().writeByte(1);
			ByteBuf second = Unpooled.buffer().writeByte(2);
			channel.writeInbound(first, second);
			channel.runPendingTasks();

			subscriber.request(1);
			channel.runPendingTasks();

			assertThat(first.refCnt()).as("retain: Reactor Netty released, application still holds").isOne();
			assertThat(second.refCnt()).as("not yet delivered").isOne();

			first.release();
			assertThat(first.refCnt()).as("application released").isZero();

			subscriber.request(1);
			channel.runPendingTasks();
			assertThat(second.refCnt()).isOne();
			second.release();
			assertThat(second.refCnt()).isZero();
		}
		finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void cancelAfterConsumeImmediatelyReleasesRemainingQueue() {
		EmbeddedChannel channel = new EmbeddedChannel();
		try {
			ChannelOperations<?, ?> ops = bindReceiveOperations(channel);
			TestSubscriber<ByteBuf> subscriber = TestSubscriber.builder().initialRequest(0).build();
			ops.receive().subscribe(subscriber);

			ByteBuf consumedImmediately = Unpooled.buffer().writeByte(1);
			ByteBuf queuedBehindIt = Unpooled.buffer().writeByte(2);
			ByteBuf alsoQueued = Unpooled.buffer().writeByte(3);
			channel.writeInbound(consumedImmediately, queuedBehindIt, alsoQueued);
			channel.runPendingTasks();

			subscriber.request(1);
			channel.runPendingTasks();

			assertThat(consumedImmediately.refCnt())
					.as("already released by Reactor Netty after consume immediately")
					.isZero();
			assertThat(queuedBehindIt.refCnt()).isOne();
			assertThat(alsoQueued.refCnt()).isOne();

			assertThatCode(subscriber::cancel).doesNotThrowAnyException();
			channel.runPendingTasks();

			assertThat(queuedBehindIt.refCnt()).as("cleanQueue released remaining").isZero();
			assertThat(alsoQueued.refCnt()).as("cleanQueue released remaining").isZero();
		}
		finally {
			channel.finishAndReleaseAll();
		}
	}

	static ChannelOperations<?, ?> bindReceiveOperations(EmbeddedChannel channel) {
		ChannelOperations.addReactiveBridge(channel,
				(conn, observer, msg) -> new ChannelOperations<>(conn, observer),
				ConnectionObserver.emptyListener());
		channel.pipeline().fireChannelActive();
		ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
		assertThat(ops).as("ChannelOperations bound after Netty channelActive").isNotNull();
		return ops;
	}
}
