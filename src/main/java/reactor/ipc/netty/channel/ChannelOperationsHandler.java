/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.channel;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;
import io.netty.handler.stream.ChunkedInput;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.QueueSupplier;

/**
 * Netty {@link io.netty.channel.ChannelDuplexHandler} implementation that bridge data
 * via an IPC {@link NettyOutbound}
 *
 * @author Stephane Maldini
 */
final class ChannelOperationsHandler extends ChannelDuplexHandler
		implements NettyPipeline.SendOptions {

	final PublisherSender                inner;
	final ContextHandler<?>              parentContext;
	final BiConsumer<?, ? super ByteBuf> encoder;
	final int                            prefetch;

	/**
	 * Cast the supplied queue (SpscLinkedArrayQueue) to use its atomic dual-insert
	 * backed by {@link BiPredicate#test)
	 **/
	BiPredicate<ChannelPromise, Object> pendingWriteOffer;
	Queue<?>                            pendingWrites;
	ChannelHandlerContext               ctx;
	boolean                             flushOnEach;
	long                                pendingBytes;

	volatile boolean innerActive;
	volatile boolean removed;
	volatile int     wip;

	@SuppressWarnings("unchecked")
	ChannelOperationsHandler(ContextHandler<?> contextHandler) {
		this.inner = new PublisherSender(this);
		this.prefetch = 32;
		this.encoder = NOOP_ENCODER;
		this.parentContext = contextHandler;
	}

	@Override
	final public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		try {
			ChannelOperations<?, ?> ops = inbound();
			if(ops != null){
				ops.onHandlerTerminate();
			}
			else {
				parentContext.terminateChannel(ctx.channel());
				parentContext.fireContextError(ContextHandler.ABORTED);
			}
		}
		catch (Throwable err) {
			Exceptions.throwIfFatal(err);
			exceptionCaught(ctx, err);
		}
	}

	@Override
	final public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg == null || msg == Unpooled.EMPTY_BUFFER || msg instanceof EmptyByteBuf) {
			return;
		}
		try {
			ChannelOperations<?, ?> ops = inbound();
			if (ops != null) {
				inbound().onInboundNext(ctx, msg);
			}
			else if (log.isDebugEnabled()) {
				log.debug("No ChannelOperation attached. Dropping: {}", msg);
			}
		}
		catch (Throwable err) {
			Exceptions.throwIfFatal(err);
			exceptionCaught(ctx, err);
		}
		finally {
			ReferenceCountUtil.release(msg);
		}
	}

	@Override
	public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
		if (log.isDebugEnabled()) {
			log.debug("Write state change {}",
					ctx.channel()
					   .isWritable());
		}
		if (ctx.channel()
		       .isWritable()) {
			inner.request(1L);
		}
		drain();
	}

	@Override
	final public void exceptionCaught(ChannelHandlerContext ctx, Throwable err)
			throws Exception {
		Exceptions.throwIfFatal(err);
		ChannelOperations<?, ?> ops = inbound();
		if(ops != null){
			ops.onInboundError(err);
		}
		else {
			parentContext.terminateChannel(ctx.channel());
			parentContext.fireContextError(err);
		}
		if(log.isDebugEnabled()){
			log.error("Handler failure while no operation was present", err);
		}
	}

	@Override
	public void flush(ChannelHandlerContext ctx) throws Exception {
		drain();
	}



	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		this.ctx = ctx;
		if (ctx.channel()
		       .isOpen()) {
			parentContext.createOperations(ctx.channel(), null);
			inner.request(prefetch);
		}
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		if (!removed) {
			removed = true;

			inner.cancel();
			drain();
		}
	}

	@Override
	final public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
			throws Exception {
		if(log.isTraceEnabled()){
			log.trace("User event {}", evt);
		}
		if (evt instanceof NettyPipeline.SendOptionsChangeEvent) {
			if (log.isDebugEnabled()) {
				log.debug("New sending options");
			}
			((NettyPipeline.SendOptionsChangeEvent) evt).configurator()
			                                            .accept(this);
		}
		else {
			ctx.fireUserEventTriggered(evt);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
			throws Exception {
		if (log.isDebugEnabled()) {
			log.debug("Writing object {}", msg);
		}

		if (pendingWrites == null) {
			this.pendingWrites = QueueSupplier.unbounded()
			                                  .get();
			this.pendingWriteOffer = (BiPredicate<ChannelPromise, Object>) pendingWrites;
		}

		if (!pendingWriteOffer.test(promise, msg)) {
			promise.setFailure(new IllegalStateException("Send Queue full?!"));
		}
	}

	@Override
	public NettyPipeline.SendOptions flushOnBoundary() {
		return this;
	}

	@Override
	public NettyPipeline.SendOptions flushOnEach() {
		flushOnEach = true;
		return this;
	}

	@Override
	public NettyPipeline.SendOptions flushOnMemoryUsed(long maxPendingBytes) {
		return this;
	}

	@Override
	public NettyPipeline.SendOptions prefetch(int prefetch) {
		return this;
	}

	@Override
	public NettyPipeline.SendOptions prefetchMemory(long prefetchBytes) {
		return this;
	}

	@Override
	public NettyPipeline.SendOptions prefetchMemory(int samplingPrefetch,
			long prefetchBytes) {
		return this;
	}

	@Override
	public NettyPipeline.SendOptions requestOnWriteAvailable() {
		return this;
	}

	@Override
	public NettyPipeline.SendOptions requestOnWriteConfirm() {
		return this;
	}

	@Override
	public NettyPipeline.SendOptions trackPendingBytes(boolean shouldCount) {
		return this;
	}

	ChannelFuture doWrite(Object msg, ChannelPromise promise, PublisherSender inner) {
		if (flushOnEach || //fastpath
				msg instanceof ChunkedInput || //let chunkedwriterhandler process
				inner == null && pendingWrites.isEmpty() || //last drained element
				!ctx.channel()
				    .isWritable() //force flush if write buffer full
				) {
			pendingBytes = 0L;
			if(inner != null){
				inner.justFlushed = true;
			}
			return ctx.writeAndFlush(msg, promise);
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
			if (log.isDebugEnabled()) {
				log.debug("Pending write size = {}", pendingBytes);
			}
			if(inner != null && inner.justFlushed){
				inner.justFlushed = false;
			}
			return ctx.write(msg, promise);
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
			if (log.isDebugEnabled()) {
				log.debug("Terminated ChannelOperation. Dropping: {}", v);
			}
			ReferenceCountUtil.release(v);
			promise.tryFailure(ContextHandler.ABORTED);
		}
	}

	@SuppressWarnings("unchecked")
	void drain() {
			if (WIP.getAndIncrement(this) == 0) {

				for (; ; ) {
					if (removed) {
						discard();
						return;
					}

					if (pendingWrites == null || innerActive || !ctx.channel()
					                                                .isWritable()) {
						if (WIP.decrementAndGet(this) == 0) {
							break;
						}
						continue;
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

					boolean empty = promise == null;

					if (empty) {
						if (WIP.decrementAndGet(this) == 0) {
							break;
						}
						continue;
					}

					v = pendingWrites.poll();

					if (v instanceof Publisher) {
						Publisher<?> p = (Publisher<?>) v;

						if (p instanceof Callable) {
							@SuppressWarnings("unchecked") Callable<?> supplier = (Callable<?>) p;

							Object vr;

							try {
								vr = supplier.call();
							}
							catch (Throwable e) {
								promise.setFailure(e);
								continue;
							}

							if (vr == null) {
								promise.setSuccess();
								continue;
							}

							if (inner.unbounded) {
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
						doWrite(v, promise, null);
					}
				}
			}
		}

	//
	final ChannelOperations<?, ?> inbound() {
		return ctx.channel()
		          .attr(ChannelOperations.OPERATIONS_KEY)
		          .get();
	}

	static final class PublisherSender
			implements Subscriber<Object>, Subscription, ChannelFutureListener {

		final ChannelOperationsHandler parent;

		volatile Subscription missedSubscription;
		volatile long         missedRequested;
		volatile long         missedProduced;
		volatile int          wip;

		boolean        inactive;
		boolean        justFlushed;
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

		PublisherSender(ChannelOperationsHandler parent) {
			this.parent = parent;
		}

		@Override
		public final void cancel() {
			if (!inactive) {
				inactive = true;

				drain();
			}
		}

		@Override
		public void onComplete() {
			long p = produced;
			ChannelFuture f = lastWrite;
			parent.innerActive = false;

			if (p != 0L) {
				produced = 0L;
				produced(p);
				if(!justFlushed) {
					parent.ctx.flush();
				}
			}

			if (f != null) {
				f.addListener(this);
			}
			else {
				promise.setSuccess();
				parent.drain();
			}
		}

		@Override
		public void onError(Throwable t) {
			long p = produced;
			ChannelFuture f = lastWrite;
			parent.innerActive = false;

			if (p != 0L) {
				produced = 0L;
				produced(p);
				if(!justFlushed) {
					parent.ctx.flush();
				}
			}

			if (f != null) {
				f.addListener(this);
			}
			else {
				promise.setFailure(t);
				parent.drain();
			}
		}

		@Override
		public void onNext(Object t) {
			produced++;

			lastWrite = parent.doWrite(t, parent.ctx.newPromise(), this);
			if (parent.ctx.channel()
			              .isWritable()) {
				request(1L);
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
		public void operationComplete(ChannelFuture future) throws Exception {
			if (future.isSuccess()) {
				promise.setSuccess();
			}
			else {
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

				Operators.getAndAddCap(MISSED_REQUESTED, this, n);

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

			Operators.getAndAddCap(MISSED_PRODUCED, this, n);

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
	}

	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<ChannelOperationsHandler> WIP =
			AtomicIntegerFieldUpdater.newUpdater(ChannelOperationsHandler.class, "wip");
	static final Logger                                              log =
			Loggers.getLogger(ChannelOperationsHandler.class);

	static final BiConsumer<?, ? super ByteBuf> NOOP_ENCODER = (a, b) -> {
	};
}
