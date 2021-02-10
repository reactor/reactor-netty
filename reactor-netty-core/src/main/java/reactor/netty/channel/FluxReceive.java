/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 */
final class FluxReceive extends Flux<Object> implements Subscription, Disposable {

	static final int QUEUE_LOW_LIMIT = 32;

	final Channel           channel;
	final ChannelOperations<?, ?> parent;
	final EventLoop         eventLoop;

	CoreSubscriber<? super Object> receiver;
	boolean                        receiverFastpath;
	long                           receiverDemand;
	Queue<Object>                  receiverQueue;

	boolean needRead = true;

	volatile boolean   inboundDone;
	Throwable inboundError;

	volatile Disposable receiverCancel;

	volatile int once;
	static final AtomicIntegerFieldUpdater<FluxReceive> ONCE =
		AtomicIntegerFieldUpdater.newUpdater(FluxReceive.class, "once");

	// Please note, in this specific case WIP is non-volatile since all operation that
	// involves work-in-progress pattern is within Netty Event-Loops which guarantees
	// serial, thread-safe behaviour.
	// However, we need that flag in order to preserve work-in-progress guarding that
	// prevents stack overflow in case of onNext -> request -> onNext cycling on the
	// same stack
	int wip;


	FluxReceive(ChannelOperations<?, ?> parent) {

		//reset channel to manual read if re-used

		this.parent = parent;
		this.channel = parent.channel();
		this.eventLoop = channel.eventLoop();
		channel.config()
		       .setAutoRead(false);
		CANCEL.lazySet(this, () -> {
			if (eventLoop.inEventLoop()) {
				unsubscribeReceiver();
			}
			else {
				eventLoop.execute(this::unsubscribeReceiver);
			}
		});
	}

	@Override
	public void cancel() {
		cancelReceiver();
		if (eventLoop.inEventLoop()) {
			drainReceiver();
		}
		else {
			eventLoop.execute(this::drainReceiver);
		}
	}

	final long getPending() {
		return receiverQueue != null ? receiverQueue.size() : 0;
	}

	final boolean isCancelled() {
		return receiverCancel == CANCELLED;
	}

	@Override
	public void dispose() {
		cancel();
	}

	@Override
	public boolean isDisposed() {
		return inboundDone && (receiverQueue == null || receiverQueue.isEmpty());
	}

	@Override
	public void request(long n) {
		if (Operators.validate(n)) {
			if (eventLoop.inEventLoop()) {
				this.receiverDemand = Operators.addCap(receiverDemand, n);
				drainReceiver();
			}
			else {
				eventLoop.execute(() -> {
					this.receiverDemand = Operators.addCap(receiverDemand, n);
					drainReceiver();
				});
			}
		}
	}

	@Override
	public void subscribe(CoreSubscriber<? super Object> s) {
		if (eventLoop.inEventLoop()) {
			startReceiver(s);
		}
		else {
			eventLoop.execute(() -> startReceiver(s));
		}
	}

	final void startReceiver(CoreSubscriber<? super Object> s) {
		if (once == 0 && ONCE.compareAndSet(this, 0, 1)) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "{}: subscribing inbound receiver"), this);
			}
			if (inboundDone && getPending() == 0) {
				if (inboundError != null) {
					Operators.error(s, inboundError);
					return;
				}

				Operators.complete(s);
				return;
			}

			receiver = s;

			s.onSubscribe(this);
		}
		else {
			if (inboundDone && getPending() == 0) {
				if (inboundError != null) {
					Operators.error(s, inboundError);
					return;
				}

				Operators.complete(s);
			}
			else {
				Operators.error(s,
						new IllegalStateException(
								"Only one connection receive subscriber allowed."));
			}
		}
	}

	final boolean cancelReceiver() {
		Disposable c = receiverCancel;
		if (c != CANCELLED) {
			c = CANCEL.getAndSet(this, CANCELLED);
			if (c != CANCELLED) {
				c.dispose();
				return true;
			}
		}
		return false;
	}

	final void cleanQueue(@Nullable Queue<Object> q) {
		if (q != null) {
			Object o;
			while ((o = q.poll()) != null) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel, "{}: dropping frame {}"), this, o);
				}
				ReferenceCountUtil.release(o);
			}
		}
	}

	final void drainReceiver() {
		// general protect against stackoverflow onNext -> request -> onNext
		if (wip++ != 0) {
			return;
		}
		int missed = 1;
		for (;;) {
			final Queue<Object> q = receiverQueue;
			final CoreSubscriber<? super Object> a = receiver;
			boolean d = inboundDone;

			if (a == null) {
				if (isCancelled()) {
					cleanQueue(q);
					return;
				}
				if (d && getPending() == 0) {
					Throwable ex = inboundError;
					if (ex != null) {
						parent.listener.onUncaughtException(parent, ex);
					}
					return;
				}
				//CHECKSTYLE:OFF
				missed = (wip -= missed);
				//CHECKSTYLE:ON
				if (missed == 0) {
					break;
				}
				continue;
			}

			long r = receiverDemand;
			long e = 0L;

			while (e != r) {
				if (isCancelled()) {
					cleanQueue(q);
					terminateReceiver(q, a);
					return;
				}

				d = inboundDone;
				Object v = q != null ? q.poll() : null;
				boolean empty = v == null;

				if (d && empty) {
					terminateReceiver(q, a);
					return;
				}

				if (empty) {
					break;
				}

				try {
					a.onNext(v);
				}
				finally {
					try {
						ReferenceCountUtil.release(v);
					}
					catch (Throwable t) {
						inboundError = t;
						cleanQueue(q);
						terminateReceiver(q, a);
					}
				}

				e++;
			}

			if (isCancelled()) {
				cleanQueue(q);
				terminateReceiver(q, a);
				return;
			}

			if (inboundDone && (q == null || q.isEmpty())) {
				terminateReceiver(q, a);
				return;
			}

			if (r == Long.MAX_VALUE) {
				receiverFastpath = true;
				if (needRead) {
					needRead = false;
					channel.config()
					       .setAutoRead(true);
				}
				//CHECKSTYLE:OFF
				missed = (wip -= missed);
				//CHECKSTYLE:ON
				if (missed == 0) {
					break;
				}
			}

			if ((receiverDemand -= e) > 0L || (e > 0L && q.size() < QUEUE_LOW_LIMIT)) {
				if (needRead) {
					needRead = false;
					channel.config()
					       .setAutoRead(true);
				}
			}
			else if (!needRead) {
				needRead = true;
				channel.config()
				       .setAutoRead(false);
			}

			//CHECKSTYLE:OFF
			missed = (wip -= missed);
			//CHECKSTYLE:ON
			if (missed == 0) {
				break;
			}
		}
	}

	final void onInboundNext(Object msg) {
		if (inboundDone || isCancelled()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "{}: dropping frame {}"), this, msg);
			}
			ReferenceCountUtil.release(msg);
			return;
		}

		if (receiverFastpath && receiver != null) {
			try {
				if (log.isDebugEnabled()) {
					if (msg instanceof ByteBuf) {
						((ByteBuf) msg).touch(format(channel, "Unbounded receiver, bypass inbound " +
								"buffer queue"));
					}
					else if (msg instanceof ByteBufHolder) {
						((ByteBufHolder) msg).touch(format(channel, "Unbounded receiver, bypass inbound " +
								"buffer queue"));
					}
				}
				receiver.onNext(msg);
			}
			finally {
				ReferenceCountUtil.release(msg);
			}
		}
		else {
			Queue<Object> q = receiverQueue;
			if (q == null) {
				// please note, in that case we are using non-thread safe, simple
				// ArrayDeque since all modifications on this queue happens withing
				// Netty Event Loop
				q = new ArrayDeque<>();
				receiverQueue = q;
			}
			if (log.isDebugEnabled()) {
				if (msg instanceof ByteBuf) {
					((ByteBuf) msg).touch(format(channel, "Buffered ByteBuf in Inbound Flux Queue"));
				}
				else if (msg instanceof ByteBufHolder) {
					((ByteBufHolder) msg).touch(format(channel, "Buffered ByteBufHolder in Inbound Flux" +
							" Queue"));
				}
			}
			q.offer(msg);
			drainReceiver();
		}
	}

	final void onInboundComplete() {
		if (inboundDone) {
			return;
		}
		inboundDone = true;
		if (receiverFastpath) {
			CoreSubscriber<?> receiver = this.receiver;
			if (receiver != null) {
				receiver.onComplete();
			}
			return;
		}
		drainReceiver();
	}

	final void onInboundError(Throwable err) {
		if (isCancelled() || inboundDone) {
			if (log.isDebugEnabled()) {
				if (AbortedException.isConnectionReset(err)) {
					log.debug(format(channel, "Connection reset has been observed post termination"), err);
				}
				else {
					log.warn(format(channel, "An exception has been observed post termination"), err);
				}
			}
			else if (log.isWarnEnabled() && !AbortedException.isConnectionReset(err)) {
				log.warn(format(channel, "An exception has been observed post termination, use DEBUG level to see the full stack: {}"), err.toString());
			}
			return;
		}
		CoreSubscriber<?> receiver = this.receiver;
		this.inboundDone = true;
		if (channel.isActive()) {
			parent.markPersistent(false);
		}

		if (err instanceof OutOfMemoryError) {
			this.inboundError = parent.wrapInboundError(err);
			try {
				if (receiver != null) {
					// propagate java.lang.OutOfMemoryError: Direct buffer memory
					receiver.onError(inboundError);
				}
			}
			finally {
				// close the connection
				// release the buffers
				parent.terminate();
			}
		}
		else if (err instanceof ClosedChannelException) {
			this.inboundError = parent.wrapInboundError(err);
		}
		else {
			this.inboundError = err;
		}

		if (receiverFastpath && receiver != null) {
			receiver.onError(inboundError);
		}
		else {
			drainReceiver();
		}
	}

	final void terminateReceiver(@Nullable Queue<?> q, CoreSubscriber<?> a) {
		if (q != null) {
			q.clear();
		}
		Throwable ex = inboundError;
		receiver = null;
		if (ex != null) {
			//parent.listener.onReceiveError(channel, ex);
			a.onError(ex);
		}
		else {
			a.onComplete();
		}
	}

	final void unsubscribeReceiver() {
		receiverDemand = 0L;
		receiver = null;
		if (isCancelled()) {
			parent.onInboundCancel();
		}
	}

	@Override
	public String toString() {
		return "FluxReceive{" +
				"pending=" + getPending() +
				", cancelled=" + isCancelled() +
				", inboundDone=" + inboundDone +
				", inboundError=" + inboundError +
				'}';
	}

	static final AtomicReferenceFieldUpdater<FluxReceive, Disposable> CANCEL =
			AtomicReferenceFieldUpdater.newUpdater(FluxReceive.class,
					Disposable.class,
					"receiverCancel");

	static final Disposable CANCELLED = () -> {
	};

	static final Logger log = Loggers.getLogger(FluxReceive.class);
}
