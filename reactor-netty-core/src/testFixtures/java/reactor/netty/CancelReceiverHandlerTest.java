/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.EmptyByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper class used to cancel a receiver when the first message is received.
 * The class is also used to check that the messages received after the cancel are all well released.
 * The handler must be placed before the reactive bridge, it will trigger a cancel action when
 * the first content body of the incoming messages is received.
 *
 * @author Pierre De Rop
 * @since 1.0.27
 */
public final class CancelReceiverHandlerTest extends ChannelInboundHandlerAdapter {
	/**
	 * Our Logger.
	 */
	static final Logger log = Loggers.getLogger(CancelReceiverHandlerTest.class);

	/**
	 * Cancel action to execute when the first message buffer is received.
	 */
	final Runnable cancelAction;

	/**
	 * Flag set to true when the cancel action has already been invoked.
	 */
	private final AtomicBoolean cancelled = new AtomicBoolean();

	/**
	 * Latch initialized with the number of incoming message body parts which are exected to be all released.
	 */
	private final CountDownLatch expectedReleaseCount;

	/**
	 * Creates a new CancelReceiverHandler instance. 1 message buffer is expected to be released.
	 *
	 * @param cancelAction The task to execute when the first content of a message is received
	 */
	public CancelReceiverHandlerTest(Runnable cancelAction) {
		this(cancelAction, 1);
	}

	/**
	 * Creates a new CancelReceiverHandler instance.
	 *
	 * @param cancelAction The task to execute when the first buffer of a message is received
	 * @param expectedReleaseCount The number of incoming message buffers that are expected to be released.
	 *                             The {@link #awaitAllReleased(long)} method will return once all incoming
	 *                             message buffers are all released.
	 */
	public CancelReceiverHandlerTest(Runnable cancelAction, int expectedReleaseCount) {
		this.cancelAction = cancelAction;
		this.expectedReleaseCount = new CountDownLatch(expectedReleaseCount);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		// If the incoming message is a kind of a buffer, calls the cancel action.
		ByteBuf buf = (msg instanceof ByteBufHolder) ? ((ByteBufHolder) msg).content() :
				((msg instanceof ByteBuf) ? (ByteBuf) msg : null);
		if (buf != null && cancelled.compareAndSet(false, true)) {
			log.debug("Executing cancel action");
			cancelAction.run();
		}

		try {
			ctx.fireChannelRead(msg);
		}
		finally {
			if (buf != null && !(buf instanceof EmptyByteBuf) && buf.refCnt() == 0) {
				expectedReleaseCount.countDown();
				if (expectedReleaseCount.getCount() == 0) {
					log.debug("All received messages have been released.");
				}
			}
		}
	}

	/**
	 * Make sure all received messages have been properly released.
	 *
	 * @param timeoutSec max time in seconds to wait
	 * @return true on success, false on timeout
	 * @throws InterruptedException in case the internal latch times out.
	 */
	public boolean awaitAllReleased(long timeoutSec) throws InterruptedException {
		return expectedReleaseCount.await(timeoutSec, TimeUnit.SECONDS);
	}
}
