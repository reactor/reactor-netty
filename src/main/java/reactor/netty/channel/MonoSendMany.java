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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.netty.FutureMono;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

class MonoSendMany<I, O> extends Mono<Void> {

	static MonoSendMany<ByteBuf, ByteBuf> byteBufSource(Publisher<? extends ByteBuf> source, Channel channel, @Nullable FlushOptions flushOption) {
		return new MonoSendMany<>(source, channel, flushOption, FUNCTION_BB_IDENTITY, CONSUMER_BB_NOCHECK_CLEANUP, CONSUMER_BB_CLEANUP, SIZE_OF_BB);
	}

	static MonoSendMany<?, ?> objectSource(Publisher<?> source, Channel channel, @Nullable FlushOptions flushOption) {
		return new MonoSendMany<>(source, channel, flushOption, FUNCTION_IDENTITY, CONSUMER_NOCHECK_CLEANUP, CONSUMER_CLEANUP, SIZE_OF);
	}

	final Publisher<? extends I> source;
	final Channel                channel;
	final Function<I, O>         transformer;
	final Consumer<O>            writeCleanup;
	final Consumer<I>            sourceCleanup;
	final ToIntFunction<O>       sizeOf;
	final FlushOptions           flushOption;

	MonoSendMany(Publisher<? extends I> source,
			Channel channel,
			@Nullable FlushOptions flushOption,
			Function<I, O> transformer,
			Consumer<I> sourceCleanup,
			Consumer<O> writeCleanup,
			ToIntFunction<O> sizeOf) {
		this.source = source;
		this.channel = channel;
		this.flushOption = flushOption == null ? FlushOptions.FLUSH_ON_BOUNDARY : flushOption;
		this.transformer = transformer;
		this.sizeOf = sizeOf;
		this.sourceCleanup = sourceCleanup;
		this.writeCleanup = writeCleanup;
	}

	@Override
	public void subscribe(CoreSubscriber<? super Void> destination) {
		source.subscribe(new SendManyInner<>(this, destination));
	}

//
	enum FlushOptions {FLUSH_ON_EACH, FLUSH_ON_BURST, FLUSH_ON_BOUNDARY}

	static final class SendManyInner<I, O> implements CoreSubscriber<I>, Subscription,
	                                                  ChannelFutureListener {

		final ChannelHandlerContext        ctx;
		final EventLoop                    eventLoop;
		final MonoSendMany<I, O>           parent;
		final CoreSubscriber<? super Void> actual;
		final AtomicBoolean                completed;
		final Queue<I>                     queue;

		volatile Subscription s;
		@SuppressWarnings("unused")
		volatile int          terminated;
		@SuppressWarnings("unused")
		volatile int          wip;

		boolean pendingFlush;
		int     pending;
		long    requestedUpstream;
		boolean fuse;

		SendManyInner(MonoSendMany<I, O> parent, CoreSubscriber<? super Void> actual) {
			this.parent = parent;
			this.actual = actual;
			this.requestedUpstream = MAX_SIZE;
			this.completed = new AtomicBoolean();
			this.ctx = Objects.requireNonNull(parent.channel.pipeline().context(ChannelOperationsHandler.class));
			this.eventLoop = parent.channel.eventLoop();
			this.queue = Queues.<I>get(MAX_SIZE).get();

//			this.fuse = queue instanceof Fuseable.QueueSubscription;
			this.fuse = false;

			//TODO cleanup on complete
			Disposable listener =
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
				requestedUpstream -= pending;
				pending = 0;

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
					ChannelPromise lastWrite;

					while (r-- > 0) {
						I sourceMessage = queue.poll();
						if (sourceMessage != null && terminated == 0) {
							O encodedMessage = parent.transformer.apply(sourceMessage);
							int readableBytes = parent.sizeOf.applyAsInt(encodedMessage);
							pending++;

							scheduleFlush =
									parent.flushOption != FlushOptions.FLUSH_ON_EACH &&
											ctx.channel().isWritable() &&
											readableBytes <= ctx.channel().bytesBeforeUnwritable();

							if (r > 0 && !queue.isEmpty()) {
								lastWrite = ctx.voidPromise();
							}
							else {
								lastWrite = ctx.newPromise();
								lastWrite.addListener(this);
							}

							ctx.write(encodedMessage, lastWrite);

							if (!scheduleFlush) {
								ctx.flush();
							}

							tryRequestMoreUpstream();
						}
						else {
							break;
						}
					}

					if (parent.flushOption != FlushOptions.FLUSH_ON_BOUNDARY && scheduleFlush && !pendingFlush) {
						pendingFlush = true;
						eventLoop.execute(this::flushOnBurst);
					}
					else if (completed.get()){
						if (parent.flushOption != FlushOptions.FLUSH_ON_EACH ) {
							ctx.flush();
						}
						tryComplete();
					}

					if (terminated == 1) {
						return;
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
				pendingFlush = false;
				ctx.flush();
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

		static final AtomicIntegerFieldUpdater<SendManyInner> WIP        =
				AtomicIntegerFieldUpdater.newUpdater(SendManyInner.class, "wip");
		static final AtomicIntegerFieldUpdater<SendManyInner> TERMINATED =
				AtomicIntegerFieldUpdater.newUpdater(SendManyInner.class, "terminated");
	}

	static final int                    MAX_SIZE    = Queues.SMALL_BUFFER_SIZE;
	static final int                    REFILL_SIZE = MAX_SIZE / 2;
	static final ToIntFunction<ByteBuf> SIZE_OF_BB  = ByteBuf::readableBytes;
	static final ToIntFunction<Object>  SIZE_OF     = msg -> {
		if (msg instanceof ByteBufHolder) {
			return ((ByteBufHolder) msg).content()
			                            .readableBytes();
		}
		if (msg instanceof ByteBuf) {
			return ((ByteBuf) msg).readableBytes();
		}
		return 0;
	};

	static final Function<ByteBuf, ByteBuf> FUNCTION_BB_IDENTITY =
			Function.identity();
	static final Function<Object, Object>   FUNCTION_IDENTITY    =
			Function.identity();

	static final Consumer<ByteBuf> CONSUMER_BB_NOCHECK_CLEANUP = ByteBuf::release;
	static final Consumer<Object>  CONSUMER_NOCHECK_CLEANUP    =
			ReferenceCountUtil::release;

	static final Consumer<ByteBuf> CONSUMER_BB_CLEANUP = data -> {
		if (data.refCnt() > 0) {
			data.release();
		}
	};

	static final Consumer<Object> CONSUMER_CLEANUP = data -> {
		if (data instanceof ReferenceCounted) {
			ReferenceCounted counted = (ReferenceCounted) data;
			if (counted.refCnt() > 0) {
				counted.release();
			}
		}
	};
}
