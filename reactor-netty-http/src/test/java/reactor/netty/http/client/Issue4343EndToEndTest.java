/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.util.IllegalReferenceCountException;
import io.netty.handler.codec.http.HttpContent;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end version of the {@code FluxReceiveTest} cases for #4343, over a real socket and the real HTTP
 * client pipeline. A consumer that over-releases the buffers it stored leaves already-released items in the
 * inbound queue; the drain that follows has to release the rest of the queue and still terminate the
 * consumer, rather than abort on the first of them.
 * <p>
 * How the queue comes to hold already-released buffers without the test reaching into the queue: the
 * response is written in a single flush and read with a small fixed receive buffer, so each socket read
 * decodes into many content slices of one cumulation buffer. Those slices share the cumulation's refCnt
 * ({@code UnpooledSlicedByteBuf}, an {@code AbstractDerivedByteBuf} - the same frame the reported
 * production stack trace shows), so a consumer that releases its stored set twice drives the shared parent
 * to zero while slices of it are still queued.
 * <p>
 * The over-release is the application's own fault, and it is meant to be: it is what a teardown race that
 * releases a buffer it has already handed on looks like from Reactor Netty's side. What the test asserts is
 * only what happens next.
 */
class Issue4343EndToEndTest extends BaseHttpTest {

	static final int CHUNKS = 600;

	/**
	 * How many buffers the consumer takes and holds before it over-releases them. It has to be more than
	 * what stays queued behind it, or the shared parent never reaches zero.
	 */
	static final int HELD = 295;

	static final int READ_SIZE = 2048;

	@Test
	void terminatesTheConsumerWhenAQueuedBufferWasAlreadyReleased() throws Exception {
		byte[] chunk = new byte[64];
		disposableServer =
				createServer()
				        .wiretap(false)
				        .handle((req, res) -> res.send(Flux.range(0, CHUNKS)
				                                           .map(i -> res.alloc().buffer().writeBytes(chunk)),
				                b -> false))
				        .bindNow();

		List<ByteBuf> inbound = new CopyOnWriteArrayList<>();
		List<ByteBuf> held = new CopyOnWriteArrayList<>();
		CountDownLatch enoughHeld = new CountDownLatch(1);
		CountDownLatch terminated = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		AtomicInteger delivered = new AtomicInteger();

		BaseSubscriber<ByteBuf> subscriber = new BaseSubscriber<ByteBuf>() {
			@Override
			protected void hookOnSubscribe(Subscription s) {
				// no initial demand, so the response piles up in the inbound queue
			}

			@Override
			protected void hookOnNext(ByteBuf value) {
				if (delivered.incrementAndGet() > HELD) {
					// the extra buffer asked for after the over-release: touch nothing, so the only
					// release that can fail on it is Reactor Netty's own
					return;
				}
				// retain and store, the way a consumer that delays processing must (ByteBufFlux#retain)
				held.add(value.retain());
				if (delivered.get() >= HELD) {
					enoughHeld.countDown();
				}
			}

			@Override
			protected void hookOnError(Throwable t) {
				error.set(t);
				terminated.countDown();
			}

			@Override
			protected void hookOnComplete() {
				terminated.countDown();
			}
		};

		Disposable exchange =
				createClient(disposableServer.port())
				        .wiretap(false)
				        .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(READ_SIZE))
				        // records every content the connection decoded, so the test can tell what is
				        // still sitting in the inbound queue: whatever the consumer never got
				        .doOnConnected(c -> c.addHandlerLast("probe", new ChannelInboundHandlerAdapter() {
				            @Override
				            public void channelRead(ChannelHandlerContext ctx, Object msg) {
				                if (msg instanceof HttpContent) {
				                    inbound.add(((HttpContent) msg).content());
				                }
				                ctx.fireChannelRead(msg);
				            }
				        }))
				        .get()
				        .uri("/")
				        .response((res, body) -> {
				            body.subscribe(subscriber);
				            return Mono.never();
				        })
				        .subscribe();

		try {
			// let the server write the whole response before any of it is asked for
			Thread.sleep(1000);

			subscriber.request(HELD);
			assertThat(enoughHeld.await(10, TimeUnit.SECONDS)).as("buffers held").isTrue();
			// let the rest of the read pile up in the inbound queue behind the demand that stopped
			Thread.sleep(500);

			// the teardown race: the consumer releases its whole stored set twice
			for (ByteBuf b : held) {
				for (int i = 0; i < 2; i++) {
					try {
						b.release();
					}
					catch (RuntimeException ignored) {
						// the shared parent has already reached zero
					}
				}
			}

			// the queue is what the consumer never got, and the over-release above left all of it
			// already released, because it is slices of the same cumulation buffer
			List<ByteBuf> queued = inbound.subList(held.size(), inbound.size());
			assertThat(queued).as("buffers left in the inbound queue").isNotEmpty();
			assertThat(queued).allSatisfy(b -> assertThat(b.refCnt())
					.as("buffer left in the inbound queue")
					.isZero());

			// ask for one more. Reactor Netty delivers a queued buffer, its own release of that buffer
			// fails because it has already been released, and the guarded release calls cleanQueue -
			// where the buffers queued behind it have already been released too.
			subscriber.request(1);

			assertThat(terminated.await(5, TimeUnit.SECONDS)).as("consumer terminated").isTrue();
			assertThat(error.get()).as("terminal signal")
			                       .isInstanceOf(IllegalReferenceCountException.class);
		}
		finally {
			exchange.dispose();
		}
	}

}
