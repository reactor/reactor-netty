/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
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

package reactor.netty.channel;

import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.netty.FutureMono;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

class MonoSend<I, O> extends Mono<Void> {

	final Publisher<? extends I> source;
	final Channel                channel;
	final Function<I, O>         transformer;
	final Consumer<O>            writeCleanup;
	final Consumer<I>            sourceCleanup;
	final ToIntFunction<O>       sizeOf;
	final FlushOptions           flushOption;
	@SuppressWarnings("unchecked")
	MonoSend(Publisher<? extends I> source,
			ChannelOperations<?, ?> ops,
			Function<I, O> transformer,
			Consumer<I> sourceCleanup,
			Consumer<O> writeCleanup,
			ToIntFunction<O> sizeOf) {
		this.source = source;
		this.channel = ops.channel();
		this.flushOption = ops.flushOption == null ? FlushOptions.FLUSH_ON_BOUNDARY :
				ops.flushOption;
		this.transformer = transformer;
		this.sizeOf = sizeOf;
		this.sourceCleanup = sourceCleanup;
		this.writeCleanup = writeCleanup;
	}

	@Override
	public void subscribe(CoreSubscriber<? super Void> destination) {
		source.subscribe(new SendInner<>(this, destination));
	}

//
	enum FlushOptions {FLUSH_ON_EACH, FLUSH_ON_BURST, FLUSH_ON_BOUNDARY}

	static final class SendInner<I, O> implements CoreSubscriber<I>, Subscription,
	                                              ChannelFutureListener {

		final ChannelHandlerContext        ctx;
		final EventLoop                    eventLoop;
		final MonoSend<I, O>               parent;
		final CoreSubscriber<? super Void> actual;
		final AtomicBoolean                pendingFlush;
		final AtomicBoolean                completed;
		final Queue<I>                     queue;

		volatile Subscription s;
		@SuppressWarnings("unused")
		volatile int          terminated;
		@SuppressWarnings("unused")
		volatile int          wip;

		int     pending;
		long    requestedUpstream;
		boolean fuse;

		SendInner(MonoSend<I, O> parent, CoreSubscriber<? super Void> actual) {
			this.parent = parent;
			this.actual = actual;
			this.pendingFlush = new AtomicBoolean();
			this.requestedUpstream = MAX_SIZE;
			this.completed = new AtomicBoolean();
			this.ctx = Objects.requireNonNull(parent.channel.pipeline().context(ChannelOperationsHandler.class));
			this.eventLoop = parent.channel.eventLoop();
			this.queue = Queues.<I>get(MAX_SIZE).get();

//			this.fuse = queue instanceof Fuseable.QueueSubscription;
			this.fuse = false;

			//TODO cleanup on complete
			FutureMono.from(ctx.channel().closeFuture())
			          .doOnTerminate(() -> {
				          if (completed.get() && TERMINATED.get(this) == 0) {
					          onError(new ClosedChannelException());
				          }
			          })
			          .subscribe();
		}

		@Override
		public Context currentContext() {
			return actual.currentContext();
		}

		@Override
		public void cancel() {
			TERMINATED.set(this, 1);
			while (!queue.isEmpty()) {
				I sourceMessage = queue.poll();
				if (sourceMessage != null) {
					parent.sourceCleanup.accept(sourceMessage);
				}
			}
		}

		@Override
		public void onComplete() {
			if (completed.compareAndSet(false, true)) {
				tryDrain();
			}
		}

		@Override
		public void onError(Throwable t) {
			if (TERMINATED.compareAndSet(this, 0, 1)) {
				try {
					s.cancel();
					actual.onError(t);
				}
				finally {
					I sourceMessage = queue.poll();
					while (sourceMessage != null) {
						parent.sourceCleanup.accept(sourceMessage);
						sourceMessage = queue.poll();
					}
				}
			}
		}

		@Override
		public void onNext(I t) {
			if (terminated == 0) {
				if (!fuse && !queue.offer(t)) {
					throw new IllegalStateException("missing back pressure");
				}
				tryDrain();
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				actual.onSubscribe(this);
				s.request(MAX_SIZE);
				tryDrain();
			}
		}

		@Override
		public void request(long n) {
			tryDrain();
		}

		@Override
		public void operationComplete(ChannelFuture future) {
			if (future.cause() != null) {
				onError(future.cause());
			}
			else {
				requestedUpstream--;
				pending--;

				tryRequestMoreUpstream();
				tryComplete();
			}
		}

		void drain() {
			try {
				boolean scheduleFlush;
				int missed = 1;
				for (; ; ) {
					scheduleFlush = false;

					long r = requestedUpstream;
					while (r-- > 0) {
						I sourceMessage = queue.poll();
						if (sourceMessage != null && terminated == 0) {
							O encodedMessage = parent.transformer.apply(sourceMessage);
							int readableBytes = parent.sizeOf.applyAsInt(encodedMessage);
							pending++;

							scheduleFlush =
									parent.flushOption != FlushOptions.FLUSH_ON_EACH && ctx.channel().isWritable() && readableBytes <= ctx.channel().bytesBeforeUnwritable();

							ctx.write(encodedMessage)
							       .addListener(this);

							if (!scheduleFlush) {
								ctx.flush();
							}

							tryRequestMoreUpstream();
						}
						else {
							break;
						}
					}

					if (parent.flushOption != FlushOptions.FLUSH_ON_BOUNDARY && scheduleFlush
							&& !pendingFlush.getAndSet(true)) {
						eventLoop.execute(this::flushOnBurst);
					}
					else if (parent.flushOption != FlushOptions.FLUSH_ON_EACH && completed.get()) {
						ctx.flush();
						tryComplete();
						return;
					}

					if (terminated == 1) {
						break;
					}

					missed = WIP.addAndGet(this, -missed);
					if (missed == 0) {
						break;
					}
				}
			}
			catch (Throwable t) {
				onError(t);
			}
		}

		void flushOnBurst() {
			try {
				ctx.flush();
				pendingFlush.set(false);
				tryComplete();
			}
			catch (Throwable t) {
				onError(t);
			}
		}

		void tryComplete() {
			if (pending == 0 && completed.get() && queue.isEmpty() && TERMINATED.compareAndSet(this, 0, 1)) {
				actual.onComplete();
			}
		}

		void tryDrain() {
			if (terminated == 0 && WIP.getAndIncrement(this) == 0) {
				try {
					if (eventLoop.inEventLoop()) {
						drain();
					}
					else {
						eventLoop.execute(this::drain);
					}
				}
				catch (Throwable t) {
					onError(t);
				}
			}
		}

		void tryRequestMoreUpstream() {
			if (requestedUpstream <= REFILL_SIZE && s != null) {
				long u = MAX_SIZE - requestedUpstream;
				requestedUpstream = Operators.addCap(requestedUpstream, u);
				s.request(u);
			}
		}

		static final int MAX_SIZE    = Queues.SMALL_BUFFER_SIZE;
		static final int REFILL_SIZE = MAX_SIZE / 2;

		static final AtomicIntegerFieldUpdater<SendInner> WIP        =
				AtomicIntegerFieldUpdater.newUpdater(SendInner.class, "wip");
		static final AtomicIntegerFieldUpdater<SendInner> TERMINATED =
				AtomicIntegerFieldUpdater.newUpdater(SendInner.class, "terminated");
	}
}
