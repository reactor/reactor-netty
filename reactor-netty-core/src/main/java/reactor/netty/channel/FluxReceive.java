/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.IntConsumer;

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
 * An inbound-traffic API delegating to an underlying {@link Channel}.
 *
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

	volatile IntConsumer receiverCancel;

	boolean subscribedOnce;

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
		CANCEL.lazySet(this, (state) -> {
			if (eventLoop.inEventLoop()) {
				if (state == 1) {
					disposeAndUnsubscribeReceiver();
				}
				else {
					unsubscribeReceiver();
				}
			}
			else {
				if (state == 1) {
					eventLoop.execute(this::disposeAndUnsubscribeReceiver);
				}
				else {
					eventLoop.execute(this::unsubscribeReceiver);
				}
			}
		});
	}

	@Override
	public void cancel() {
		doCancel(0);
	}

	final long getPending() {
		return receiverQueue != null ? receiverQueue.size() : 0;
	}

	final boolean isCancelled() {
		return receiverCancel == CANCELLED;
	}

	@Override
	public void dispose() {
		doCancel(1);
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
		if (!subscribedOnce) {
			subscribedOnce = true;
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "{}: subscribing inbound receiver"), this);
			}
			if ((inboundDone && getPending() == 0) || isCancelled()) {
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
				if (log.isDebugEnabled()) {
					log.debug(format(channel, "{}: Rejecting additional inbound receiver."), this);
				}

				String msg = "Rejecting additional inbound receiver. State=" + toString(false);
				IllegalStateException ex = inboundError == null ? new IllegalStateException(msg) :
						new IllegalStateException(msg, inboundError);
				Operators.error(s, ex);
			}
		}
	}

	final void cancelReceiver(int cancelCode) {
		IntConsumer c = receiverCancel;
		if (c != CANCELLED) {
			c = CANCEL.getAndSet(this, CANCELLED);
			if (c != CANCELLED) {
				c.accept(cancelCode);
			}
		}
	}

	final void doCancel(int cancelCode) {
		cancelReceiver(cancelCode);
		if (eventLoop.inEventLoop()) {
			drainReceiver();
		}
		else {
			eventLoop.execute(this::drainReceiver);
		}
	}

	final void cleanQueue(@Nullable Queue<Object> q) {
		if (q != null) {
			Object o;
			while ((o = q.poll()) != null) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel, "{}: dropping frame {}"), this, parent.asDebugLogMessage(o));
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
					if (logLeakDetection.isDebugEnabled()) {
						if (v instanceof ByteBuf) {
							((ByteBuf) v).touch(format(channel, "Receiver " + a.getClass().getName() +
									" will handle the message from this point"));
						}
						else if (v instanceof ByteBufHolder) {
							((ByteBufHolder) v).touch(format(channel, "Receiver " + a.getClass().getName() +
									" will handle the message from this point"));
						}
					}
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
				log.debug(format(channel, "{}: dropping frame {}"), this, parent.asDebugLogMessage(msg));
			}
			ReferenceCountUtil.release(msg);
			return;
		}

		if (receiverFastpath && receiver != null) {
			try {
				if (logLeakDetection.isDebugEnabled()) {
					if (msg instanceof ByteBuf) {
						((ByteBuf) msg).touch(format(channel, "Receiver " + receiver.getClass().getName() +
								" will handle the message from this point"));
					}
					else if (msg instanceof ByteBufHolder) {
						((ByteBufHolder) msg).touch(format(channel, "Receiver " + receiver.getClass().getName() +
								" will handle the message from this point"));
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
			if (logLeakDetection.isDebugEnabled()) {
				if (msg instanceof ByteBuf) {
					((ByteBuf) msg).touch(format(channel, "Buffered ByteBuf in the inbound buffer queue"));
				}
				else if (msg instanceof ByteBufHolder) {
					((ByteBufHolder) msg).touch(format(channel, "Buffered ByteBufHolder in the inbound buffer queue"));
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

	final void disposeAndUnsubscribeReceiver() {
		final CoreSubscriber<? super Object> a = receiver;
		receiverDemand = 0L;
		receiver = null;
		if (isCancelled()) {
			parent.onInboundCancel();
		}

		if (a != null) {
			Throwable ex = inboundError;
			if (ex != null) {
				a.onError(ex);
			}
			else {
				a.onComplete();
			}
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
		return toString(true);
	}

	final String toString(boolean logErrorMessage) {
		return '[' +
				"terminated=" + inboundDone +
				", cancelled=" + isCancelled() +
				", pending=" + getPending() +
				", error=" + (logErrorMessage ? inboundError : (inboundError != null)) +
				']';
	}

	static final AtomicReferenceFieldUpdater<FluxReceive, IntConsumer> CANCEL =
			AtomicReferenceFieldUpdater.newUpdater(FluxReceive.class,
					IntConsumer.class,
					"receiverCancel");

	static final IntConsumer CANCELLED = (__) -> {
	};

	static final Logger log = Loggers.getLogger(FluxReceive.class);

	static final Logger logLeakDetection = Loggers.getLogger("_reactor.netty.channel.LeakDetection");
}
