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
import io.netty.util.IllegalReferenceCountException;
import org.junit.jupiter.api.Test;
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
	void cleanQueueReleasesEveryBufferWhenOneWasAlreadyReleased() {
		EmbeddedChannel channel = new EmbeddedChannel();
		ChannelOperations<NettyInbound, NettyOutbound> operations =
				new ChannelOperations<>(() -> channel, (connection, newState) -> {
				});
		FluxReceive receive = new FluxReceive(operations);

		receive.subscribe(TestSubscriber.builder().initialRequest(0).build());

		ByteBuf releasedElsewhere = Unpooled.buffer().writeByte(1);
		ByteBuf queuedBehindIt = Unpooled.buffer().writeByte(2);
		receive.onInboundNext(releasedElsewhere);
		receive.onInboundNext(queuedBehindIt);
		assertThat(receive.getPending()).isEqualTo(2);

		releasedElsewhere.release();

		assertThatCode(receive::cancel).doesNotThrowAnyException();

		assertThat(queuedBehindIt.refCnt()).as("buffer queued behind an already released one").isZero();
	}

	@Test
	void cleanQueueStillTerminatesTheReceiverWhenABufferWasAlreadyReleased() {
		EmbeddedChannel channel = new EmbeddedChannel();
		ChannelOperations<NettyInbound, NettyOutbound> operations =
				new ChannelOperations<>(() -> channel, (connection, newState) -> {
				});
		FluxReceive receive = new FluxReceive(operations);

		TestSubscriber<Object> subscriber = TestSubscriber.builder().initialRequest(0).build();
		receive.subscribe(subscriber);

		ByteBuf delivered = Unpooled.buffer().writeByte(1);
		ByteBuf queuedBehindIt = Unpooled.buffer().writeByte(2);
		receive.onInboundNext(delivered);
		receive.onInboundNext(queuedBehindIt);

		delivered.release();
		queuedBehindIt.release();

		assertThatCode(() -> receive.request(1)).doesNotThrowAnyException();

		assertThat(subscriber.isTerminated()).as("receiver terminated").isTrue();
		assertThat(subscriber.expectTerminalError()).isInstanceOf(IllegalReferenceCountException.class);
	}
}
