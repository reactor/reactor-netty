/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.core.publisher.Operators;
import reactor.netty.ReactorNetty;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

final class MonoSendMany<I, O> extends MonoSend<I, O> implements Scannable {

	static MonoSendMany<ByteBuf, ByteBuf> byteBufSource(Publisher<? extends ByteBuf> source,
			Channel channel,
			Predicate<ByteBuf> predicate) {
		return new MonoSendMany<>(source, channel, predicate, TRANSFORMATION_FUNCTION_BB, CONSUMER_BB_NOCHECK_CLEANUP, SIZE_OF_BB);
	}

	static MonoSendMany<?, ?> objectSource(Publisher<?> source, Channel channel, Predicate<Object> predicate) {
		return new MonoSendMany<>(source, channel, predicate, TRANSFORMATION_FUNCTION, CONSUMER_NOCHECK_CLEANUP, SIZE_OF);
	}

	final Publisher<? extends I> source;
	final Predicate<I> predicate;

	MonoSendMany(Publisher<? extends I> source,
			Channel channel,
			Predicate<I> predicate,
			Function<? super I, ? extends O> transformer,
			Consumer<? super I> sourceCleanup,
			ToIntFunction<O> sizeOf) {
		super(channel, transformer, sourceCleanup, sizeOf);
		this.source = Objects.requireNonNull(source, "source publisher cannot be null");
		this.predicate = Objects.requireNonNull(predicate, "predicate cannot be null");
	}

	@Override
	public void subscribe(CoreSubscriber<? super Void> destination) {
		source.subscribe(new SendManyInner<>(this, destination));
	}

	@Override
	@Nullable
	public Object scanUnsafe(Attr key) {
		if (key == Attr.PREFETCH) return MAX_SIZE;
		if (key == Attr.PARENT) return source;
		return null;
	}

	static final class SendManyInner<I, O> implements CoreSubscriber<I>, Subscription, Fuseable,
	                                                  ChannelFutureListener, Runnable, Scannable, ChannelPromise {

		final ChannelHandlerContext        ctx;
		final EventLoop                    eventLoop;
		final MonoSendMany<I, O>           parent;
		final CoreSubscriber<? super Void> actual;
		final Runnable                     asyncFlush;


		@SuppressWarnings("unused")
		volatile Subscription s;

		@SuppressWarnings("unused")
		volatile int          wip;

		Queue<I> queue;
		boolean  done;
		int      pending;
		int      requested;
		int      sourceMode;
		boolean  needFlush;

		int nextRequest;

		SendManyInner(MonoSendMany<I, O> parent, CoreSubscriber<? super Void> actual) {
			this.parent = parent;
			this.actual = actual;
			this.requested = MAX_SIZE;
			this.ctx = parent.ctx;
			this.eventLoop = ctx.channel().eventLoop();

			this.asyncFlush = new AsyncFlush();

			//TODO should also cleanup on complete operation (ChannelOperation.OnTerminate) ?
			ctx.channel()
			   .closeFuture()
			   .addListener(this);
		}



		@Override
		public Context currentContext() {
			return actual.currentContext();
		}

		@Override
		public void cancel() {
			if (Operators.terminate(SUBSCRIPTION, this)) {
				return;
			}
			if (WIP.getAndIncrement(this) == 0) {
				cleanup();
			}
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			ctx.channel()
			   .closeFuture()
			   .removeListener(this);
			trySchedule(null);
		}

		@Override
		public void onError(Throwable t) {

			if (SUBSCRIPTION.getAndSet(this, Operators.cancelledSubscription()) == Operators.cancelledSubscription()) {
				Operators.onErrorDropped(t, actual.currentContext());
				return;
			}

			//FIXME serialize om drain loop
			if (WIP.getAndIncrement(this) == 0) {
				cleanup();
			}

			//Avoid singleton
			if (t instanceof ClosedChannelException) {
				t = ReactorNetty.wrapException(t);
			}

			actual.onError(t);
		}

		@Override
		public void onNext(I t) {
			if (sourceMode == ASYNC) {
				trySchedule(null);
				return;
			}

			if (done) {
				parent.sourceCleanup.accept(t);
				Operators.onDiscard(t, actual.currentContext());
				return;
			}

//			ReferenceCountUtil.touch(t);
			//FIXME check cancel race
			if (!queue.offer(t)) {
				onError(Operators.onOperatorError(s,
						Exceptions.failWithOverflow(Exceptions.BACKPRESSURE_ERROR_QUEUE_FULL),
						t,
						actual.currentContext()));
				return;
			}
			trySchedule(t);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(SUBSCRIPTION, this, s)) {
				if (s instanceof QueueSubscription) {
					@SuppressWarnings("unchecked") QueueSubscription<I> f =
							(QueueSubscription<I>) s;

					int m = f.requestFusion(Fuseable.ANY/* | Fuseable.THREAD_BARRIER*/);

					if (m == Fuseable.SYNC) {
						sourceMode = Fuseable.SYNC;
						queue = f;
						done = true;
						actual.onSubscribe(this);
						trySchedule(null);
						return;
					}
					if (m == Fuseable.ASYNC) {
						sourceMode = Fuseable.ASYNC;
						queue = f;
						actual.onSubscribe(this);
						s.request(MAX_SIZE);
						return;
					}
				}

				queue = Queues.<I>get(MAX_SIZE).get();
				actual.onSubscribe(this);
				s.request(MAX_SIZE);
			}
			else {
				queue = Queues.<I>empty().get();
			}
		}

		@Override
		public void request(long n) {
			//ignore since downstream has no demand
		}

		@Override
		public void operationComplete(ChannelFuture future) {
			if (Operators.terminate(SUBSCRIPTION, this)) {
				if (WIP.getAndIncrement(this) == 0) {
					cleanup();
				}
				//actual.onError(new AbortedException("Closed channel ["+ctx.channel().id().asShortText()+"] while sending operation active"));
				actual.onComplete();
			}
		}

		@Override
		public void run() {
			Queue<I> queue = this.queue;
			try {
				int missed = 1;
				for (; ; ) {
					int r = requested;

					while (Integer.MAX_VALUE != r && r-- > 0) {
						I sourceMessage = queue.poll();

						if (sourceMessage == null) {
							break;
						}

						if (s == Operators.cancelledSubscription()) {
							parent.sourceCleanup.accept(sourceMessage);
							Operators.onDiscard(sourceMessage, actual.currentContext());
							cleanup();
							return;
						}

						O encodedMessage = parent.transformer.apply(sourceMessage);
						if (encodedMessage == null) {
							if (parent.predicate.test(sourceMessage)) {
								nextRequest++;
								needFlush = false;
								ctx.flush();
							}
							continue;
						}

						int readableBytes = parent.sizeOf.applyAsInt(encodedMessage);


						if (readableBytes == 0 && !(encodedMessage instanceof ByteBufHolder)) {
							nextRequest++;
							continue;
						}
						pending++;
						ctx.write(encodedMessage, this);

						if (parent.predicate.test(sourceMessage) || !ctx.channel().isWritable() || readableBytes > ctx.channel().bytesBeforeUnwritable()) {
							needFlush = false;
							ctx.flush();
						}
						else {
							needFlush = true;
						}
					}

					if (needFlush && pending != 0) {
						needFlush = false;
						eventLoop.execute(asyncFlush);
					}

					if (Operators.cancelledSubscription() == s) {
						cleanup();
						return;
					}

					if (tryComplete()) {
						return;
					}

					int nextRequest = this.nextRequest;
					if (!done && nextRequest != 0) {
						this.nextRequest = 0;
						s.request(nextRequest);
					}

					missed = WIP.addAndGet(this, -missed);
					if (missed == 0) {
						break;
					}
				}
			}
			catch (Throwable t) {
				cleanup();
				if (Operators.terminate(SUBSCRIPTION, this) ) {
					actual.onError(t);
				}
				else {
					Operators.onErrorDropped(t, actual.currentContext());
				}
			}
		}

		void cleanup() {
			ctx.channel()
			   .closeFuture()
			   .removeListener(this);

			Queue<I> queue = this.queue;
			if (queue == null) {
				return;
			}
			Context context = null;
			while (!queue.isEmpty()) {
				I sourceMessage = queue.poll();
				if (sourceMessage != null) {
					parent.sourceCleanup.accept(sourceMessage);
					if (context == null) {
						context = actual.currentContext();
					}
					Operators.onDiscard(sourceMessage, context);
				}
			}
		}

		boolean tryComplete() {
			if (pending == 0
					&& done
					&& queue.isEmpty()
					&& SUBSCRIPTION.getAndSet(this, Operators.cancelledSubscription()) != Operators.cancelledSubscription()) {
				actual.onComplete();
				return true;
			}
			return false;
		}

		void trySchedule(@Nullable Object data) {
			if (WIP.getAndIncrement(this) == 0) {
				try {
					if (eventLoop.inEventLoop()) {
						run();
						return;
					}
					eventLoop.execute(this);
				}
				catch (Throwable t) {
					if(Operators.terminate(SUBSCRIPTION, this)) {
						cleanup();
						actual.onError(Operators.onRejectedExecution(t, null, null, data, actual.currentContext()));
					}
				}
			}
		}

		@Override
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return s;
			if (key == Attr.ACTUAL) return actual;
			if (key == Attr.REQUESTED_FROM_DOWNSTREAM) return requested;
			if (key == Attr.CANCELLED) return Operators.cancelledSubscription() == s;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.BUFFERED) return queue != null ? queue.size() : 0;
//			if (key == Attr.ERROR) return error;
			if (key == Attr.PREFETCH) return MAX_SIZE;
			return null;
		}

		@Override
		public Channel channel() {
			return ctx.channel();
		}

		@Override
		public ChannelPromise setSuccess(Void result) {
			trySuccess(null);
			return this;
		}

		@Override
		public ChannelPromise setSuccess() {
			trySuccess(null);
			return this;
		}

		@Override
		public boolean trySuccess() {
			trySuccess(null);
			return true;
		}

		@Override
		public ChannelPromise setFailure(Throwable cause) {
			if (tryFailure(cause)) {
				return this;
			}
			Operators.onErrorDropped(cause, actual.currentContext());
			return this;
		}

		@Override
		public ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
			return this;
		}

		@Override
		public ChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
			return this;
		}

		@Override
		public ChannelPromise sync() {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChannelPromise syncUninterruptibly() {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChannelPromise await()  {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChannelPromise awaitUninterruptibly() {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChannelPromise unvoid() {
			return new DefaultChannelPromise(ctx.channel()).addListener(this);
		}

		@Override
		public boolean isVoid() {
			return false;
		}

		@Override
		public boolean trySuccess(Void result) {
			requested--;
			pending--;

			if (tryComplete()) {
				return true;
			}

			if (requested <= REFILL_SIZE) {
				int u = MAX_SIZE - requested;
				requested += u;
				nextRequest += u;
				trySchedule(null);
			}
			return true;
		}

		@Override
		public boolean tryFailure(Throwable cause) {
			if (Operators.terminate(SUBSCRIPTION, this)) {
				if (WIP.getAndIncrement(this) == 0) {
					cleanup();
				}
				actual.onError(cause);
			}
			return true;
		}

		@Override
		public boolean setUncancellable() {
			return true;
		}

		@Override
		public boolean isSuccess() {
			return done && queue.isEmpty();
		}

		@Override
		public boolean isCancellable() {
			return false;
		}

		@Override
		@Nullable
		public Throwable cause() {
			return null;
		}

		@Override
		public boolean await(long timeout, TimeUnit unit) {
			return false;
		}

		@Override
		public boolean await(long timeoutMillis) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean awaitUninterruptibly(long timeoutMillis) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Void getNow() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			return false;
		}

		@Override
		public Void get() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Void get(long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		static final AtomicIntegerFieldUpdater<SendManyInner>                 WIP          =
				AtomicIntegerFieldUpdater.newUpdater(SendManyInner.class, "wip");
		static final AtomicReferenceFieldUpdater<SendManyInner, Subscription> SUBSCRIPTION =
				AtomicReferenceFieldUpdater.newUpdater(SendManyInner.class, Subscription.class, "s");

		final class AsyncFlush implements Runnable {
			@Override
			public void run() {
				if (pending != 0) {
					ctx.flush();
				}
			}
		}
	}

	static final Logger log = Loggers.getLogger(MonoSendMany.class);
}
