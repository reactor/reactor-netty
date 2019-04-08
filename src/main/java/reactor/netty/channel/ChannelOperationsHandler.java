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

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel.Unsafe;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.DecoderResultProvider;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.publisher.Operators;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;

/**
 * Netty {@link io.netty.channel.ChannelDuplexHandler} implementation that bridge data
 * via an IPC {@link NettyOutbound}
 *
 * @author Stephane Maldini
 */
final class ChannelOperationsHandler extends ChannelDuplexHandler
		implements NettyPipeline.SendOptions, ChannelFutureListener {

	final PublisherSender           inner;
	final int                       prefetch;
	final ConnectionObserver        listener;
	final ChannelOperations.OnSetup opsFactory;

	/**
	 * Cast the supplied queue (SpscLinkedArrayQueue) to use its atomic dual-insert
	 * backed by {@link BiPredicate#test}
	 **/
	BiPredicate<ChannelFuture, Object>  pendingWriteOffer;
	Queue<?>                            pendingWrites;
	ChannelHandlerContext               ctx;
	boolean                             flushOnEach;
	boolean                             flushOnEachWithEventLoop;

	long                                pendingBytes;

	private Unsafe                      unsafe;

	volatile boolean innerActive;
	volatile boolean removed;
	volatile int     wip;
	volatile long    scheduledFlush;

	@SuppressWarnings("unchecked")
	ChannelOperationsHandler(ChannelOperations.OnSetup opsFactory, ConnectionObserver listener) {
		this.inner = new PublisherSender(this);
		this.prefetch = 32;
		this.listener = listener;
		this.opsFactory = opsFactory;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		Connection c = Connection.from(ctx.channel());
		listener.onStateChange(c, ConnectionObserver.State.CONNECTED);
		ChannelOperations<?, ?> ops = opsFactory.create(c, listener, null);
		if (ops != null) {
			ops.bind();
			listener.onStateChange(ops, ConnectionObserver.State.CONFIGURED);
		}
	}

	@Override
	final public void channelInactive(ChannelHandlerContext ctx) {
		try {
			Connection connection = Connection.from(ctx.channel());
			ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
			if (ops != null) {
				ops.onInboundClose();
			}
			else {
				listener.onStateChange(connection, ConnectionObserver.State.DISCONNECTING);
			}
		}
		catch (Throwable err) {
			Exceptions.throwIfJvmFatal(err);
			exceptionCaught(ctx, err);
		}
	}

	@Override
	final public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg == null || msg == Unpooled.EMPTY_BUFFER || msg instanceof EmptyByteBuf) {
			return;
		}
		try {
			ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
			if (ops != null) {
				ops.onInboundNext(ctx, msg);
			}
			else {
				if (log.isDebugEnabled()) {
					String loggingMsg = msg.toString();
					if (msg instanceof DecoderResultProvider) {
						DecoderResult decoderResult = ((DecoderResultProvider) msg).decoderResult();
						if (decoderResult.isFailure()) {
							log.debug(format(ctx.channel(), "Decoding failed: " + msg + " : "),
									decoderResult.cause());
						}
					}
					if (msg instanceof ByteBufHolder && ((ByteBufHolder)msg).content() != Unpooled.EMPTY_BUFFER) {
						ByteBuf buffer = ((ByteBufHolder) msg).content();
						loggingMsg = buffer.readCharSequence(buffer.readableBytes(), Charset.defaultCharset()).toString();
					}
					log.debug(format(ctx.channel(), "No ChannelOperation attached. Dropping: {}"),
							loggingMsg);
				}
				ReferenceCountUtil.release(msg);
			}
		}
		catch (Throwable err) {
			Exceptions.throwIfJvmFatal(err);
			exceptionCaught(ctx, err);
			ReferenceCountUtil.safeRelease(msg);
		}
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) {
		if (log.isDebugEnabled()) {
			log.debug(format(ctx.channel(), "Write state change {}"),
					ctx.channel()
					   .isWritable());
		}
		drain();
	}

	@Override
	final public void exceptionCaught(ChannelHandlerContext ctx, Throwable err) {
		Exceptions.throwIfJvmFatal(err);
		Connection connection = Connection.from(ctx.channel());
		ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
		if (ops != null) {
			ops.onInboundError(err);
		}
		else {
			listener.onUncaughtException(connection, err);
		}
	}

	@Override
	public void flush(ChannelHandlerContext ctx) {
		drain();
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		this.ctx = ctx;
		this.unsafe = ctx.channel().unsafe();
		inner.request(prefetch);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		if (!removed) {
			removed = true;

			inner.cancel();
			drain();
		}
	}

	@Override
	final public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
		if (log.isTraceEnabled()) {
			log.trace(format(ctx.channel(), "End of the pipeline, User event {}"), evt);
		}
		if (evt instanceof NettyPipeline.SendOptionsChangeEvent) {
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(), "New sending options"));
			}
			((NettyPipeline.SendOptionsChangeEvent) evt).configurator()
			                                            .accept(this);
			return;
		}

		ctx.fireUserEventTriggered(evt);
	}

	@Override
	@SuppressWarnings({"unchecked", "FutureReturnValueIgnored"})
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
		if (log.isDebugEnabled() && msg != ChannelOperations.TERMINATED_OPS) {
			log.debug(format(ctx.channel(), "Writing object {}"), msg);
		}

		if (pendingWrites == null) {
			this.pendingWrites = Queues.unbounded()
			                           .get();
			this.pendingWriteOffer = (BiPredicate<ChannelFuture, Object>) pendingWrites;
		}

		if (!pendingWriteOffer.test(promise, msg)) {
			// Returned value is deliberately ignored
			promise.setFailure(new IllegalStateException("Send Queue full?!"));
		}
	}

	@Override
	public NettyPipeline.SendOptions flushOnBoundary() {
		flushOnEach = false;
		return this;
	}

	@Override
	public NettyPipeline.SendOptions flushOnEach(boolean withEventLoop) {
		flushOnEach = true;
		flushOnEachWithEventLoop = withEventLoop;
		return this;
	}

	@Override
	public void operationComplete(ChannelFuture future) {
		if (future.isSuccess()) {
			inner.request(1L);
		}
	}

	ChannelFuture doWrite(Object msg, ChannelPromise promise, @Nullable PublisherSender inner) {
		if (flushOnEach || //fastpath
				(inner == null && pendingWrites.isEmpty()) || //last drained element
				!ctx.channel()
				    .isWritable() //force flush if write buffer full
				) {
			pendingBytes = 0L;

			ChannelFuture future = ctx.write(msg, promise);
			if (flushOnEachWithEventLoop && ctx.channel().isWritable()) {
				scheduleFlush();
			}
			else {
				ctx.flush();
			}
			return future;
		}
		else {
			if (msg instanceof ByteBuf) {
				pendingBytes =
						Operators.addCap(pendingBytes, ((ByteBuf) msg).readableBytes());
			}
			else if (msg instanceof ByteBufHolder) {
				pendingBytes = Operators.addCap(pendingBytes,
						((ByteBufHolder) msg).content()
						                     .readableBytes());
			}
			else if (msg instanceof FileRegion) {
				pendingBytes = Operators.addCap(pendingBytes, ((FileRegion) msg).count());
			}
			if (log.isTraceEnabled()) {
				log.trace(format(ctx.channel(), "Pending write size = {}"), pendingBytes);
			}
			ChannelFuture future = ctx.write(msg, promise);
			if (!ctx.channel().isWritable()) {
				pendingBytes = 0L;
				ctx.flush();
			}
			return future;
		}
	}

	void scheduleFlush() {
		if (SCHEDULED_FLUSH.getAndIncrement(this) == 0) {
			ctx.channel()
			   .eventLoop()
			   .execute(() -> {
			       long missed = scheduledFlush;
			       for(;;) {
			           if (hasPendingWriteBytes()) {
			               ctx.flush();
			           }
			           missed = SCHEDULED_FLUSH.addAndGet(this, -missed);
			           if (missed == 0) {
			               break;
			           }
			       }
			   });
		}
	}

	void discard() {
		for (; ; ) {
			if (pendingWrites == null || pendingWrites.isEmpty()) {
				return;
			}

			ChannelPromise promise;
			Object v = pendingWrites.poll();

			try {
				promise = (ChannelPromise) v;
			}
			catch (Throwable e) {
				ctx.fireExceptionCaught(e);
				return;
			}
			v = pendingWrites.poll();
			if (v == ChannelOperations.TERMINATED_OPS) {
				continue;
			}
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(), "Terminated ChannelOperation. Dropping Pending Write: {}"),
						v);
			}
			ReferenceCountUtil.release(v);
			promise.tryFailure(new AbortedException("Connection has been closed"));
		}
	}

	@SuppressWarnings({"unchecked", "FutureReturnValueIgnored"})
	void drain() {
		if (WIP.getAndIncrement(this) == 0) {

			for (; ; ) {
				if (removed) {
					discard();
					return;
				}

				if (pendingWrites == null || innerActive || !ctx.channel()
				                                                .isWritable()) {
					if (!ctx.channel().isWritable() && hasPendingWriteBytes()) {
						ctx.flush();
					}
					if (WIP.decrementAndGet(this) == 0) {
						break;
					}
					continue;
				}

				ChannelFuture future;
				Object v = pendingWrites.poll();

				try {
					future = (ChannelFuture) v;
				}
				catch (Throwable e) {
					ctx.fireExceptionCaught(e);
					return;
				}

				boolean empty = future == null;

				if (empty) {
					if (WIP.decrementAndGet(this) == 0) {
						break;
					}
					continue;
				}

				v = pendingWrites.poll();

				if (!innerActive && v == PublisherSender.PENDING_WRITES) {
					boolean last = pendingWrites.isEmpty();
					if (!future.isDone() && hasPendingWriteBytes()) {
						ctx.flush();
						if (!future.isDone() && hasPendingWriteBytes()) {
							if (!pendingWriteOffer.test(future, v) && future instanceof ChannelPromise) {
								// Returned value is deliberately ignored
								((ChannelPromise) future).setFailure(new IllegalStateException("Send Queue full?!"));
							}
						}
					}
					if (last && WIP.decrementAndGet(this) == 0) {
						break;
					}
				}
				else if (future instanceof ChannelPromise) {
					ChannelPromise promise = (ChannelPromise) future;
					if (v == ChannelOperations.TERMINATED_OPS) {
						promise.setSuccess(null);
					}
					else if (v instanceof Publisher) {
						Publisher<?> p = (Publisher<?>) v;
	
						if (p instanceof Callable) {
							@SuppressWarnings("unchecked") Callable<?> supplier =
									(Callable<?>) p;
	
							Object vr;
	
							try {
								vr = supplier.call();
							}
							catch (Throwable e) {
								// Returned value is deliberately ignored
								promise.setFailure(e);
								continue;
							}
	
							if (vr == null) {
								// Returned value is deliberately ignored
								promise.setSuccess();
								continue;
							}
	
							if (inner.unbounded) {
								// Returned value is deliberately ignored
								doWrite(vr, promise, null);
							}
							else {
								innerActive = true;
								inner.promise = promise;
								inner.onSubscribe(Operators.scalarSubscription(inner, vr));
							}
						}
						else {
							innerActive = true;
							inner.promise = promise;
							p.subscribe(inner);
						}
					}
					else {
						// Returned value is deliberately ignored
						doWrite(v, promise, null);
					}
				}
			}
		}
	}

	private boolean hasPendingWriteBytes() {
		// On close the outboundBuffer is made null. After that point
		// adding messages and flushes to outboundBuffer is not allowed.
		ChannelOutboundBuffer outBuffer = this.unsafe.outboundBuffer();
		return outBuffer != null && outBuffer.totalPendingWriteBytes() > 0;
	}

	static final class PublisherSender
			implements CoreSubscriber<Object>, Subscription, ChannelFutureListener,
			           Consumer<ChannelFuture> {

		final ChannelOperationsHandler parent;

		volatile Subscription missedSubscription;
		volatile long         missedRequested;
		volatile long         missedProduced;
		volatile int          wip;

		boolean        inactive;
		/**
		 * The current outstanding request amount.
		 */
		long           requested;
		boolean        unbounded;
		/**
		 * The current subscription which may null if no Subscriptions have been set.
		 */
		Subscription   actual;
		long           produced;
		ChannelPromise promise;
		ChannelFuture  lastWrite;
		boolean        lastThreadInEventLoop;

		PublisherSender(ChannelOperationsHandler parent) {
			this.parent = parent;
		}

		@Override
		public void accept(ChannelFuture cf) {
			//current subscription stop
			if (promise == cf &&
					MISSED_SUBSCRIPTION.compareAndSet(this, null, Operators.cancelledSubscription())) {
				drain();
			}
		}

		@Override
		@SuppressWarnings("unchecked")
		public Context currentContext() {
			ChannelPromise p = promise;
			if (p instanceof Function) {
				return ((Function<?, Context>)p).apply(null);
			}
			return Context.empty();
		}

		@Override
		public final void cancel() {
			//full stop
			if (!inactive) {
				inactive = true;

				drain();
			}
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void onComplete() {
			long p = produced;
			ChannelFuture f = lastWrite;
			parent.innerActive = false;

			if (p != 0L) {
				if (parent.pendingBytes > 0L || parent.hasPendingWriteBytes() || !lastThreadInEventLoop) {
					if (parent.ctx.channel()
					              .isActive()) {
						parent.pendingBytes = 0L;
						if (lastThreadInEventLoop) {
							parent.ctx.flush();
						}
						else {
							parent.ctx.channel()
							          .eventLoop()
							          .execute(() -> parent.ctx.flush());
						}
					}
					else {
						// Returned value is deliberately ignored
						promise.setFailure(new AbortedException("Connection has been closed"));
						return;
					}
				}
			}

			if (f != null) {
				if (!f.isDone() && (parent.hasPendingWriteBytes() || !lastThreadInEventLoop)) {
					EventLoop eventLoop = parent.ctx.channel().eventLoop();
					if (eventLoop.inEventLoop()) {
						if (!parent.pendingWriteOffer.test(f, PENDING_WRITES) && f instanceof ChannelPromise) {
							// Returned value is deliberately ignored
							((ChannelPromise) f).setFailure(new IllegalStateException("Send Queue full?!"));
						}
					}
					else {
						eventLoop.execute(() -> {
							if (!f.isDone() && parent.hasPendingWriteBytes()) {
								if (!parent.pendingWriteOffer.test(f, PENDING_WRITES) && f instanceof ChannelPromise) {
									// Returned value is deliberately ignored
									((ChannelPromise) f).setFailure(new IllegalStateException("Send Queue full?!"));
								}
							}
						});
					}
				}
				f.addListener(this);
			}
			else {
				produced = 0L;
				produced(p);
				// Returned value is deliberately ignored
				promise.setSuccess();
				parent.drain();
			}
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void onError(Throwable t) {
			long p = produced;
			ChannelFuture f = lastWrite;
			parent.innerActive = false;

			if (p != 0L) {
				if (parent.ctx.channel()
				              .isActive()) {
					if (lastThreadInEventLoop) {
						parent.ctx.flush();
					}
					else {
						parent.ctx.channel()
						          .eventLoop()
						          .execute(() -> parent.ctx.flush());
					}
				}
				else {
					// Returned value is deliberately ignored
					promise.setFailure(new AbortedException("Connection has been closed"));
					return;
				}
			}

			if (f != null) {
				if (!f.isDone() && (parent.hasPendingWriteBytes() || !lastThreadInEventLoop)) {
					EventLoop eventLoop = parent.ctx.channel().eventLoop();
					if (eventLoop.inEventLoop()) {
						if (!parent.pendingWriteOffer.test(f, PENDING_WRITES) && f instanceof ChannelPromise) {
							// Returned value is deliberately ignored
							((ChannelPromise) f).setFailure(new IllegalStateException("Send Queue full?!"));
						}
					}
					else {
						eventLoop.execute(() -> {
							if (!parent.pendingWriteOffer.test(f, PENDING_WRITES) && f instanceof ChannelPromise) {
								// Returned value is deliberately ignored
								((ChannelPromise) f).setFailure(new IllegalStateException("Send Queue full?!"));
							}
						});
					}
				}
				f.addListener(future -> {
					produced = 0L;
					produced(p);
					if (!future.isSuccess()) {
						// Returned value is deliberately ignored
						promise.setFailure(Exceptions.addSuppressed(future.cause(), t));
						return;
					}
					// Returned value is deliberately ignored
					promise.setFailure(t);
				});
			}
			else {
				produced = 0L;
				produced(p);
				// Returned value is deliberately ignored
				promise.setFailure(t);
				parent.drain();
			}
		}

		@Override
		public void onNext(Object t) {
			ChannelPromise newPromise = parent.ctx.newPromise();
			if (lastWrite == null || lastThreadInEventLoop || lastWrite.isDone()) {
				onNextInternal(t, newPromise);
				lastThreadInEventLoop = parent.ctx.channel().eventLoop().inEventLoop();
			}
			else {
				parent.ctx.channel()
				          .eventLoop()
				          .execute(() -> onNextInternal(t, newPromise));
				lastThreadInEventLoop = false;
			}

			lastWrite = newPromise;
		}

		@SuppressWarnings("FutureReturnValueIgnored")
		private void onNextInternal(Object t, ChannelPromise promise) {
			if (!parent.ctx.channel()
			               .isActive()) {
				cancel();
				if (log.isDebugEnabled()) {
					log.debug(format(parent.ctx.channel(), "Dropping pending write, " +
							"since connection has been closed: {}"), t);
				}
				ReferenceCountUtil.release(t);
				return;
			}

			produced++;

			// Returned value is deliberately ignored
			parent.doWrite(t, promise, this);

			if (parent.ctx.channel()
			              .isWritable()) {
				request(1L);
			}
			else {
				promise.addListener(parent);
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (inactive) {
				s.cancel();
				return;
			}

			Objects.requireNonNull(s);

			if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
				actual = s;

				long r = requested;

				if (WIP.decrementAndGet(this) != 0) {
					drainLoop();
				}

				if (r != 0L) {
					s.request(r);
				}

				return;
			}

			MISSED_SUBSCRIPTION.set(this, s);
			drain();
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void operationComplete(ChannelFuture future) {
			long p = produced;
			produced = 0L;
			produced(p);
			if (future.isSuccess()) {
				// Returned value is deliberately ignored
				promise.setSuccess();
			}
			else {
				// Returned value is deliberately ignored
				promise.setFailure(future.cause());
			}
		}

		@Override
		public final void request(long n) {
			if (Operators.validate(n)) {
				if (unbounded) {
					return;
				}
				if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
					long r = requested;

					if (r != Long.MAX_VALUE) {
						r = Operators.addCap(r, n);
						requested = r;
						if (r == Long.MAX_VALUE) {
							unbounded = true;
						}
					}
					Subscription a = actual;

					if (WIP.decrementAndGet(this) != 0) {
						drainLoop();
					}

					if (a != null) {
						a.request(n);
					}

					return;
				}

				Operators.addCap(MISSED_REQUESTED, this, n);

				drain();
			}
		}

		final void drain() {
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}
			drainLoop();
		}

		final void drainLoop() {
			int missed = 1;

			long requestAmount = 0L;
			Subscription requestTarget = null;

			for (; ; ) {

				Subscription ms = missedSubscription;

				if (ms != null) {
					ms = MISSED_SUBSCRIPTION.getAndSet(this, null);

					if (ms == Operators.cancelledSubscription()) {
						parent.innerActive = false;
						Subscription a = actual;
						if (a != null) {
							a.cancel();
							actual = null;
							promise = parent.ctx.voidPromise();
						}
					}
				}

				long mr = missedRequested;
				if (mr != 0L) {
					mr = MISSED_REQUESTED.getAndSet(this, 0L);
				}

				long mp = missedProduced;
				if (mp != 0L) {
					mp = MISSED_PRODUCED.getAndSet(this, 0L);
				}

				Subscription a = actual;

				if (inactive) {
					if (a != null) {
						a.cancel();
						actual = null;
						promise = parent.ctx.voidPromise();
					}
					if (ms != null) {
						ms.cancel();
					}
				}
				else {
					long r = requested;
					if (r != Long.MAX_VALUE) {
						long u = Operators.addCap(r, mr);

						if (u != Long.MAX_VALUE) {
							long v = u - mp;
							if (v < 0L) {
								Operators.reportMoreProduced();
								v = 0;
							}
							r = v;
						}
						else {
							r = u;
						}
						requested = r;
					}

					if (ms != null) {
						actual = ms;
						if (r != 0L) {
							requestAmount = Operators.addCap(requestAmount, r);
							requestTarget = ms;
						}
					}
					else if (mr != 0L && a != null) {
						requestAmount = Operators.addCap(requestAmount, mr);
						requestTarget = a;
					}
				}

				missed = WIP.addAndGet(this, -missed);
				if (missed == 0) {
					if (requestAmount != 0L) {
						requestTarget.request(requestAmount);
					}
					return;
				}
			}
		}

		final void produced(long n) {
			if (unbounded) {
				return;
			}
			if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
				long r = requested;

				if (r != Long.MAX_VALUE) {
					long u = r - n;
					if (u < 0L) {
						Operators.reportMoreProduced();
						u = 0;
					}
					requested = u;
				}
				else {
					unbounded = true;
				}

				if (WIP.decrementAndGet(this) == 0) {
					return;
				}

				drainLoop();

				return;
			}

			Operators.addCap(MISSED_PRODUCED, this, n);

			drain();
		}

		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<PublisherSender, Subscription>
				                                                MISSED_SUBSCRIPTION =
				AtomicReferenceFieldUpdater.newUpdater(PublisherSender.class,
						Subscription.class,
						"missedSubscription");
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<PublisherSender>    MISSED_REQUESTED    =
				AtomicLongFieldUpdater.newUpdater(PublisherSender.class,
						"missedRequested");
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<PublisherSender>    MISSED_PRODUCED     =
				AtomicLongFieldUpdater.newUpdater(PublisherSender.class,
						"missedProduced");
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<PublisherSender> WIP                 =
				AtomicIntegerFieldUpdater.newUpdater(PublisherSender.class, "wip");

		private static final PendingWritesOnCompletion PENDING_WRITES = new PendingWritesOnCompletion();
	}

	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<ChannelOperationsHandler> WIP =
			AtomicIntegerFieldUpdater.newUpdater(ChannelOperationsHandler.class, "wip");
	static final AtomicLongFieldUpdater<ChannelOperationsHandler> SCHEDULED_FLUSH =
			AtomicLongFieldUpdater.newUpdater(ChannelOperationsHandler.class, "scheduledFlush");
	static final Logger                                              log =
			Loggers.getLogger(ChannelOperationsHandler.class);

	static final BiConsumer<?, ? super ByteBuf> NOOP_ENCODER = (a, b) -> {
	};

	private static final class PendingWritesOnCompletion {
		@Override
		public String toString() {
			return "[Pending Writes on Completion]";
		}
	}
}
