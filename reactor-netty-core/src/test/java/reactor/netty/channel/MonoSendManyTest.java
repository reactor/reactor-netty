/*
 * Copyright (c) 2019-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import reactor.test.util.RaceTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test class verifies {@link MonoSendMany}.
 *
 * @author Stephane Maldini
 */
class MonoSendManyTest {

	@ParameterizedTest
	@EnumSource(SourceMode.class)
	void shouldBoundPendingWrites(SourceMode mode) {
		for (boolean flushOnEach : new boolean[]{true, false}) {
			Queue<ByteBuf> messages = new ArrayDeque<>();
			Queue<ChannelPromise> promises = new ArrayDeque<>();
			List<Integer> written = new ArrayList<>();
			EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
				@Override
				public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
					ByteBuf buffer = (ByteBuf) msg;
					written.add(buffer.getInt(buffer.readerIndex()));
					messages.add(buffer);
					promises.add(promise);
					assertThat(promises).hasSizeLessThanOrEqualTo(MonoSend.MAX_SIZE);
				}
			}, new ChannelHandlerAdapter() {});
			ByteBuf[] buffers = new ByteBuf[MonoSend.MAX_SIZE * 4];
			for (int i = 0; i < buffers.length; i++) {
				buffers[i] = ByteBufAllocator.DEFAULT.buffer(4).writeInt(i);
			}
			List<Long> requests = new ArrayList<>();
			Flux<ByteBuf> source = mode.source.apply(buffers);
			if (mode == SourceMode.NONE) {
				source = source.doOnRequest(requests::add);
			}
			Mono<Void> send = MonoSendMany.byteBufSource(source,
					channel, b -> flushOnEach);
			try {
				StepVerifier.create(send)
				            .consumeSubscriptionWith(s -> assertThat(((MonoSendMany.SendManyInner<?, ?>) s).sourceMode)
							            .isEqualTo(mode.fusionMode))
				            .then(() -> {
					            channel.runPendingTasks();
					            assertThat(promises).hasSize(MonoSend.MAX_SIZE);
					            int initialRequests = requests.size();
					            for (int i = 0; i < 3; i++) {
						            channel.runPendingTasks();
					            }
					            assertThat(promises).hasSize(MonoSend.MAX_SIZE);
					            assertThat(requests).hasSize(initialRequests);
					            if (mode == SourceMode.NONE) {
						            assertThat(requests).containsExactly((long) MonoSend.MAX_SIZE);
					            }
					            for (int i = 0; i < 3; i++) {
						            for (int j = 0; j < MonoSend.MAX_SIZE - MonoSend.REFILL_SIZE; j++) {
							            messages.remove().release();
							            promises.remove().setSuccess();
						            }
						            channel.runPendingTasks();
						            assertThat(promises).hasSize(MonoSend.MAX_SIZE);
					            }
					            while (written.size() < buffers.length || promises.size() > 1) {
						            messages.remove().release();
						            promises.remove().setSuccess();
						            channel.runPendingTasks();
					            }
				            })
				            .expectNoEvent(Duration.ofMillis(10))
				            .then(() -> {
					            messages.remove().release();
					            promises.remove().setSuccess();
					            channel.runPendingTasks();
				            })
				            .verifyComplete();
				assertThat(written).hasSize(buffers.length);
				for (int i = 0; i < buffers.length; i++) {
					assertThat(written.get(i)).isEqualTo(i);
				}
				assertThat(buffers).allSatisfy(b -> assertThat(b.refCnt()).isZero());
			}
			finally {
				messages.forEach(ReferenceCountUtil::release);
				channel.finishAndReleaseAll();
			}
		}
	}

	@ParameterizedTest
	@EnumSource(SourceMode.class)
	void shouldReleaseQueuedBuffersWhenPendingWritesInterrupted(SourceMode mode) {
		for (boolean flushOnEach : new boolean[]{true, false}) {
			for (int termination = 0; termination < 3; termination++) {
				List<ByteBuf> messages = new ArrayList<>();
				List<ChannelPromise> promises = new ArrayList<>();
				EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
					@Override
					public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
						messages.add((ByteBuf) msg);
						promises.add(promise);
					}
				}, new ChannelHandlerAdapter() {});
				ByteBuf[] buffers = new ByteBuf[MonoSend.MAX_SIZE * 2];
				for (int i = 0; i < buffers.length; i++) {
					buffers[i] = ByteBufAllocator.DEFAULT.buffer(4).writeInt(i);
				}
				AtomicBoolean completed = new AtomicBoolean();
				AtomicReference<Throwable> error = new AtomicReference<>();
				BaseSubscriber<Void> subscriber = MonoSendMany.byteBufSource(mode.source.apply(buffers),
						channel, b -> flushOnEach).subscribeWith(new BaseSubscriber<Void>() {
					@Override
					protected void hookOnComplete() {
						completed.set(true);
					}

					@Override
					protected void hookOnError(Throwable t) {
						error.set(t);
					}
				});
				try {
					channel.runPendingTasks();
					assertThat(promises).hasSize(MonoSend.MAX_SIZE);
					IllegalStateException failure = new IllegalStateException("write failed");
					if (termination == 0) {
						subscriber.cancel();
					}
					else if (termination == 1) {
						channel.close();
					}
					else {
						promises.get(0).setFailure(failure);
					}
					channel.runPendingTasks();
					assertThat(completed.get()).isEqualTo(termination == 1);
					assertThat(error.get()).isSameAs(termination == 2 ? failure : null);
					assertThat(promises).hasSize(MonoSend.MAX_SIZE);
					for (int i = MonoSend.MAX_SIZE; i < buffers.length; i++) {
						assertThat(buffers[i].refCnt()).isZero();
					}
				}
				finally {
					subscriber.cancel();
					messages.forEach(ReferenceCountUtil::release);
					channel.finishAndReleaseAll();
				}
				assertThat(buffers).allSatisfy(b -> assertThat(b.refCnt()).isZero());
			}
		}
	}

	@ParameterizedTest
	@EnumSource(SourceMode.class)
	void shouldDrainWithImmediateSuccessAndFlushMarkers(SourceMode mode) {
		for (boolean flushOnEach : new boolean[]{true, false}) {
			List<Integer> written = new ArrayList<>();
			EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
				@Override
				public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
					ByteBuf buffer = (ByteBuf) msg;
					written.add(buffer.readInt());
					buffer.release();
					promise.setSuccess();
				}
			}, new ChannelHandlerAdapter() {});
			ByteBuf[] buffers = new ByteBuf[MonoSend.MAX_SIZE * 3];
			List<Integer> expected = new ArrayList<>();
			for (int i = 0; i < buffers.length; i++) {
				buffers[i] = ByteBufAllocator.DEFAULT.buffer(4);
				if (i % 3 != 1) {
					buffers[i].writeInt(i);
				}
				if (i % 3 == 2) {
					expected.add(i);
				}
			}
			AtomicBoolean flushMarker = new AtomicBoolean();
			Mono<Void> send = new MonoSendMany<>(mode.source.apply(buffers), channel,
					b -> flushOnEach || flushMarker.get(),
					b -> {
						flushMarker.set(false);
						if (b.isReadable() && b.getInt(b.readerIndex()) % 3 == 0) {
							flushMarker.set(true);
							b.release();
							return null;
						}
						return b;
					}, ReferenceCountUtil::release, ByteBuf::readableBytes);
			try {
				StepVerifier.create(send)
				            .then(channel::runPendingTasks)
				            .expectComplete()
				            .verify(Duration.ofSeconds(5));
				assertThat(written).containsExactlyElementsOf(expected);
				assertThat(buffers).allSatisfy(b -> assertThat(b.refCnt()).isZero());
			}
			finally {
				channel.finishAndReleaseAll();
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void testPromiseSendTimeout(boolean flushOnEach) {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new WriteTimeoutHandler(1), new ChannelHandlerAdapter() {});

		Flux<String> flux = Flux.range(0, 257).map(count -> count + "");
		Mono<Void> m = MonoSendMany.objectSource(flux, channel, b -> flushOnEach);

		StepVerifier.create(m)
		            .then(() -> {
		                channel.runPendingTasks(); //run flush
		                for (int i = 0; i < 257; i++) {
		                    assertThat(channel.<String>readOutbound()).isEqualTo(i + "");
		                }
		            })
		            .verifyComplete();
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void cleanupFuseableSyncCloseFuture(boolean flushOnEach) {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.fromArray(new String[]{"test", "test2"}), channel, b -> flushOnEach);

		List<WeakReference<Subscription>> _w = new ArrayList<>(1);
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> _w.add(new WeakReference<>(s)))
		            .then(() -> {
			            channel.runPendingTasks();
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test");
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test2");
		            })
		            .verifyComplete();

		System.gc();
		wait(_w.get(0));
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void cleanupFuseableAsyncCloseFuture(boolean flushOnEach) {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.fromArray(new String[]{"test", "test2"}).limitRate(10), channel, b -> flushOnEach);

		List<WeakReference<Subscription>> _w = new ArrayList<>(1);
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> _w.add(new WeakReference<>(s)))
		            .then(() -> {
			            channel.runPendingTasks();
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test");
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test2");
		            })
		            .verifyComplete();

		System.gc();
		wait(_w.get(0));
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void cleanupFuseableErrorCloseFuture(boolean flushOnEach) {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.fromArray(new String[]{"test", "test2"}).concatWith(Mono.error(new Exception("boo"))).limitRate(10), channel, b -> flushOnEach);

		List<WeakReference<Subscription>> _w = new ArrayList<>(1);
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> _w.add(new WeakReference<>(s)))
		            .then(() -> {
			            channel.runPendingTasks();
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test");
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test2");
		            })
		            .verifyErrorMessage("boo");

		System.gc();
		wait(_w.get(0));
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void cleanupCancelCloseFuture(boolean flushOnEach) {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.fromArray(new String[]{"test", "test2"}).concatWith(Mono.never()), channel, b -> flushOnEach);

		List<WeakReference<Subscription>> _w = new ArrayList<>(1);
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> _w.add(new WeakReference<>(s)))
		            .then(channel::runPendingTasks)
		            .thenCancel()
		            .verify(Duration.ofSeconds(5));

		System.gc();
		wait(_w.get(0));
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void cleanupErrorCloseFuture(boolean flushOnEach) {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Mono.error(new Exception("boo")), channel, b -> flushOnEach);

		List<WeakReference<Subscription>> _w = new ArrayList<>(1);
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> _w.add(new WeakReference<>(s)))
		            .then(channel::runPendingTasks)
		            .verifyErrorMessage("boo");

		System.gc();
		wait(_w.get(0));
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void shouldNotLeakOnRacingCancelAndOnNext(boolean flushOnEach) {
		int messagesToSend = 128;

		for (int i = 0; i < 10000; i++) {
			//use an extra handler
			EmbeddedChannel channel = new EmbeddedChannel(true, true, new ChannelHandlerAdapter() {});

			TestPublisher<ByteBuf> source = TestPublisher.createNoncompliant(TestPublisher.Violation.DEFER_CANCELLATION);

			IdentityHashMap<ReferenceCounted, Object> discarded = new IdentityHashMap<>();
			MonoSendMany<ByteBuf, ByteBuf> m = MonoSendMany.byteBufSource(source, channel, b -> flushOnEach);
			BaseSubscriber<Void> testSubscriber = m
				.doOnDiscard(ReferenceCounted.class, v -> discarded.put(v, null))
				.subscribeWith(new BaseSubscriber<Void>() {});
			Queue<Object> messages = channel.outboundMessages();
			Queue<ByteBuf> buffersToSend = new ArrayDeque<>(messagesToSend);
			for (int j = 0; j < messagesToSend; j++) {
				buffersToSend.offer(ByteBufAllocator.DEFAULT.buffer().writeInt(j));
			}

			RaceTestUtils.race(testSubscriber::cancel, () -> {
				for (ByteBuf buf : buffersToSend) {
					source.next(buf);
				}
			});

			channel.flush();

			messages.forEach(ReferenceCountUtil::safeRelease);

			assertThat(discarded.size() + messages.size())
					.as("Expect all element are flushed or discarded but was discarded " +
							": [" + discarded.size() + "], flushed : [" + messages.size() + "]")
					.isEqualTo(messagesToSend);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void shouldNotLeakIfFusedOnRacingCancelAndOnNext(boolean flushOnEach) {
		int messagesToSend = 128;

		ArrayBlockingQueue<ReferenceCounted> discarded = new ArrayBlockingQueue<>(messagesToSend * 2);
		Hooks.onNextDropped(v -> {
			ReferenceCountUtil.safeRelease(v);
			discarded.add((ReferenceCounted) v);
		});
		for (int i = 0; i < 10000; i++) {
			//use an extra handler
			EmbeddedChannel channel = new EmbeddedChannel(true, true, new ChannelHandlerAdapter() {});

			Sinks.Many<ByteBuf> source = Sinks.many().unicast().onBackpressureBuffer();
			MonoSendMany<ByteBuf, ByteBuf> m = MonoSendMany.byteBufSource(source.asFlux(), channel, b -> flushOnEach);
			BaseSubscriber<Void> testSubscriber = m
					.doOnDiscard(ReferenceCounted.class, discarded::add)
					.subscribeWith(new BaseSubscriber<Void>() {});
			Queue<Object> messages = channel.outboundMessages();
			Queue<ByteBuf> buffersToSend = new ArrayDeque<>(messagesToSend);
			for (int j = 0; j < messagesToSend; j++) {
				buffersToSend.offer(ByteBufAllocator.DEFAULT.buffer().writeInt(j));
			}

			RaceTestUtils.race(testSubscriber::cancel, () -> {
				for (ByteBuf buf : buffersToSend) {
					source.emitNext(buf, Sinks.EmitFailureHandler.FAIL_FAST);
				}
			});

			IdentityHashMap<ReferenceCounted, ?> distinctDiscarded =
					discarded.stream().collect(Collectors.toMap(Function.identity(),
							Function.identity(), (r1, r2) -> r1, IdentityHashMap::new));

			channel.flush();
			messages.forEach(ReferenceCountUtil::release);

			assertThat(distinctDiscarded.size() + messages.size())
					.as("Expect all element are flushed or discarded but was discarded " +
							": [" + distinctDiscarded.size() + "], flushed : [" + messages.size() + "]")
					.isEqualTo(messagesToSend);
			discarded.clear();
		}
	}


	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@SuppressWarnings("unchecked")
	void shouldCallQueueClearToNotifyTermination(boolean flushOnEach) {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(true, true, new ChannelHandlerAdapter() {});
		AtomicBoolean cleared = new AtomicBoolean();

		Sinks.Many<ByteBuf> source = Sinks.many().unicast().onBackpressureBuffer();
		MonoSendMany<ByteBuf, ByteBuf> m =
				MonoSendMany.byteBufSource(source.asFlux().transform(Operators.<ByteBuf, ByteBuf>lift((__,
						downstream) -> new CoreSubscriber<ByteBuf>() {
					@Override
					public void onSubscribe(Subscription s) {
						downstream.onSubscribe(new Fuseable.QueueSubscription<ByteBuf>() {
							@Override
							public void request(long n) {
								s.request(n);
							}

							@Override
							public void cancel() {
								s.cancel();
							}

							@Override
							public int size() {
								return ((Fuseable.QueueSubscription<ByteBuf>) s).size();
							}

							@Override
							public boolean isEmpty() {
								return ((Fuseable.QueueSubscription<ByteBuf>) s).isEmpty();
							}

							@Override
							public void clear() {
								cleared.set(true);
								((Fuseable.QueueSubscription<ByteBuf>) s).clear();
							}

							@Override
							public ByteBuf poll() {
								return ((Fuseable.QueueSubscription<ByteBuf>) s).poll();
							}

							@Override
							public int requestFusion(int requestedMode) {
								return ((Fuseable.QueueSubscription<ByteBuf>) s).requestFusion(requestedMode);
							}
						});
					}

					@Override
					public void onNext(ByteBuf buf) {
						downstream.onNext(buf);
					}

					@Override
					public void onError(Throwable t) {
						downstream.onError(t);
					}

					@Override
					public void onComplete() {
						downstream.onComplete();
					}
				})), channel, b -> flushOnEach);
		m.subscribe();
		Queue<Object> messages = channel.outboundMessages();

		source.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);

		channel.flush();
		messages.forEach(ReferenceCountUtil::release);
		assertThat(cleared).isTrue();
	}

	@ParameterizedTest
	@EnumSource(SourceMode.class)
	void shouldCompleteAndReleaseWhenChannelAlreadyClosed(SourceMode mode) {
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});
		ByteBuf[] buffers = buffers();

		Mono<Void> m = MonoSendMany.byteBufSource(mode.source.apply(buffers), channel, b -> false);
		assertThat(channel.close().isSuccess()).isTrue();

		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> assertThat(((MonoSendMany.SendManyInner<?, ?>) s).sourceMode).isEqualTo(mode.fusionMode))
		            .then(channel::runPendingTasks)
		            .expectComplete()
		            .verify(Duration.ofSeconds(5));

		assertThat(buffers).allSatisfy(b -> assertThat(b.refCnt()).isZero());
	}

	@ParameterizedTest
	@EnumSource(SourceMode.class)
	void shouldNotRetainCloseListenerWhenCancelledOnSubscribe(SourceMode mode) {
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});
		ByteBuf[] buffers = buffers();

		Mono<Void> m = MonoSendMany.byteBufSource(mode.source.apply(buffers), channel, b -> false);

		List<WeakReference<Subscription>> _w = new ArrayList<>(1);
		m.subscribe(new BaseSubscriber<Void>() {
			@Override
			protected void hookOnSubscribe(Subscription s) {
				_w.add(new WeakReference<>(s));
				s.cancel();
			}
		});
		channel.runPendingTasks();

		assertThat(channel.isOpen()).isTrue();
		assertThat(channel.<ByteBuf>readOutbound()).isNull();
		assertThat(buffers).allSatisfy(b -> assertThat(b.refCnt()).isZero());

		System.gc();
		wait(_w.get(0));
	}

	static ByteBuf[] buffers() {
		return new ByteBuf[]{
				ByteBufAllocator.DEFAULT.buffer().writeInt(1),
				ByteBufAllocator.DEFAULT.buffer().writeInt(2)};
	}

	enum SourceMode {
		SYNC(Fuseable.SYNC, Flux::fromArray),
		ASYNC(Fuseable.ASYNC, buffers -> Flux.fromArray(buffers).publishOn(Schedulers.immediate())),
		NONE(Fuseable.NONE, buffers -> Flux.fromIterable(Arrays.asList(buffers)).hide());

		final int fusionMode;
		final Function<ByteBuf[], Flux<ByteBuf>> source;

		SourceMode(int fusionMode, Function<ByteBuf[], Flux<ByteBuf>> source) {
			this.fusionMode = fusionMode;
			this.source = source;
		}
	}

	static void wait(WeakReference<Subscription> ref) {
		int duration = 5_000;
		int spins = duration / 100;
		int i = 0;
		while (ref.get() != null && i < spins) {
			try {
				Thread.sleep(100);
				i++;
			}
			catch (Throwable e) {
				throw Exceptions.propagate(e);
			}
		}
		if (ref.get() != null) {
			throw new IllegalStateException("Has not cleaned");
		}
	}
}
