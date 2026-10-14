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
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.IllegalReferenceCountException;
import org.junit.jupiter.api.Test;
import reactor.netty.Connection;
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

	/**
	 * Draining the inbound queue on cancellation must release every queued buffer, even when some of them have
	 * already been released elsewhere. {@code cleanQueue} used to call {@code ReferenceCountUtil.release}, so the
	 * first already-released buffer threw {@code IllegalReferenceCountException} out of the drain loop and the rest
	 * of the queue was abandoned. The failure must still reach the application: there is no subscriber after the
	 * cancellation, so it goes to the connection listener, the first failure carrying the next ones as suppressed.
	 */
	@Test
	void cleanQueueReleasesEveryBufferWhenOneWasAlreadyReleased() {
		EmbeddedChannel channel = new EmbeddedChannel();
		List<Throwable> uncaught = new ArrayList<>();
		ChannelOperations<NettyInbound, NettyOutbound> operations =
				new ChannelOperations<>(() -> channel, new ConnectionObserver() {
					@Override
					public void onUncaughtException(Connection connection, Throwable error) {
						uncaught.add(error);
					}

					@Override
					public void onStateChange(Connection connection, State newState) {
					}
				});
		FluxReceive receive = new FluxReceive(operations);

		// no initial request, so inbound buffers accumulate in the receiver queue instead of being delivered
		receive.subscribe(TestSubscriber.builder().initialRequest(0).build());

		ByteBuf releasedElsewhere1 = Unpooled.buffer().writeByte(1);
		ByteBuf releasedElsewhere2 = Unpooled.buffer().writeByte(2);
		ByteBuf queuedBehindThem = Unpooled.buffer().writeByte(3);
		receive.onInboundNext(releasedElsewhere1);
		receive.onInboundNext(releasedElsewhere2);
		receive.onInboundNext(queuedBehindThem);
		assertThat(receive.getPending()).isEqualTo(3);

		releasedElsewhere1.release();
		releasedElsewhere2.release();

		assertThatCode(receive::cancel).doesNotThrowAnyException();

		assertThat(queuedBehindThem.refCnt()).as("buffer queued behind already released ones").isZero();
		assertThat(uncaught).singleElement()
				.isInstanceOf(IllegalReferenceCountException.class)
				.satisfies(t -> assertThat(t.getSuppressed()).singleElement()
						.isInstanceOf(IllegalReferenceCountException.class));
	}

	/**
	 * The release in {@code drainReceiver} is guarded (#3406, PR #3448) but its {@code catch} block calls
	 * {@code cleanQueue}, which was not: a second already-released buffer threw out of that {@code catch}, so
	 * {@code terminateReceiver} never ran and the subscriber was left waiting for a terminal signal that never came.
	 */
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

		// both buffers reached refCnt 0 elsewhere, the way the slices of an over-released cumulation buffer do
		delivered.release();
		queuedBehindIt.release();

		// delivers the first buffer, then fails to release it, which is the guarded release that calls cleanQueue
		assertThatCode(() -> receive.request(1)).doesNotThrowAnyException();

		assertThat(subscriber.isTerminated()).as("receiver terminated").isTrue();
		// the failed release of the delivered buffer, carrying the failed release of the queued one as suppressed
		assertThat(subscriber.expectTerminalError())
				.isInstanceOf(IllegalReferenceCountException.class)
				.satisfies(t -> assertThat(t.getSuppressed()).singleElement()
						.isInstanceOf(IllegalReferenceCountException.class));
	}
}
