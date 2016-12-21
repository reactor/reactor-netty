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

import java.util.Queue;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Cancellation;
import reactor.core.Exceptions;
import reactor.core.Producer;
import reactor.core.Trackable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.NettyContext;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.QueueSupplier;

/**
 * @author Stephane Maldini
 */
final class FluxReceive extends Flux<Object>
		implements Subscription, Trackable, Producer {

	final Channel           channel;
	final ChannelOperations<?, ?> parent;
	final EventLoop         eventLoop;

	Subscriber<? super Object> receiver;
	boolean                    receiverFastpath;
	long                       receiverDemand;
	Queue<Object>              receiverQueue;

	boolean   inboundDone;
	Throwable inboundError;

	volatile Cancellation receiverCancel;

	FluxReceive(ChannelOperations<?, ?> parent) {
		this.parent = parent;
		this.channel = parent.channel;
		this.eventLoop = channel.eventLoop();
	}

	@Override
	public void cancel() {
		if (cancelReceiver()) {
			Queue<Object> q = receiverQueue;
			if (q != null) {
				Object o;
				while ((o = q.poll()) != null) {
					ReferenceCountUtil.release(o);
				}
			}
		}
	}

	@Override
	final public Object downstream() {
		return receiver;
	}

	@Override
	final public Throwable getError() {
		return inboundError;
	}

	@Override
	public long getPending() {
		return receiverQueue != null ? receiverQueue.size() : 0;
	}

	@Override
	final public boolean isStarted() {
		return channel.isActive();
	}

	@Override
	final public boolean isTerminated() {
		return inboundDone;
	}

	@Override
	public boolean isCancelled() {
		return receiverCancel == CANCELLED;
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
	public long requestedFromDownstream() {
		return receiverDemand;
	}

	@Override
	public void subscribe(Subscriber<? super Object> s) {
		if (s == null) {
			throw Exceptions.argumentIsNullException();
		}
		if (eventLoop.inEventLoop()) {
			startReceiver(s);
		}
		else {
			eventLoop.execute(() -> startReceiver(s));
		}
	}

	final boolean cancelReceiver() {
		Cancellation c = receiverCancel;
		if (c != CANCELLED) {
			c = CANCEL.getAndSet(this, CANCELLED);
			if (c != CANCELLED) {
				if(parent.isOutboundDone()){
					parent.onHandlerTerminate();
				}
				parent.onInboundCancel();
				if (c != null) {
					c.dispose();
					return true;
				}
			}
		}
		return false;
	}

	final boolean drainReceiver() {

		final Queue<Object> q = receiverQueue;
		final Subscriber<? super Object> a = receiver;

		if (a == null) {
			if (inboundDone) {
				cancelReceiver();
			}
			return false;
		}

		long r = receiverDemand;
		long e = 0L;

		while (e != r) {
			if (isCancelled()) {
				return false;
			}

			boolean d = inboundDone;
			Object v = q != null ? q.poll() : null;
			boolean empty = v == null;

			if (d && empty) {
				terminateReceiver(q, a);
				return false;
			}

			if (empty) {
				break;
			}

			try {
				a.onNext(v);
			}
			finally {
				ReferenceCountUtil.release(v);
			}

			e++;
		}

		if (isCancelled()) {
			return false;
		}

		if (inboundDone && (q == null || q.isEmpty())) {
			terminateReceiver(q, a);
			return false;
		}

		if (r == Long.MAX_VALUE) {
			channel.config()
			       .setAutoRead(true);
			channel.read();
			return true;
		}

		if ((receiverDemand -= e) > 0L || e > 0L) {
			channel.read();
		}

		return false;
	}

	final void startReceiver(Subscriber<? super Object> s) {
		if (receiver == null) {
			if (log.isDebugEnabled()) {
				log.debug("Subscribing inbound receiver [pending: " + "" + getPending() + ", inboundDone: {}]",
						inboundDone);
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
			CANCEL.lazySet(this, () -> {
				if (eventLoop.inEventLoop()) {
					unsubscribeReceiver();
				}
				else {
					eventLoop.execute(this::unsubscribeReceiver);
				}
			});
			s.onSubscribe(this);
		}
		else {
			Operators.error(s,
					new IllegalStateException(
							"Only one connection receive subscriber allowed."));
		}
	}

	final boolean markInboundDone() {
		if (inboundDone) {
			Queue<?> q = receiverQueue;
			Subscriber<?> receiver = this.receiver;
			if (receiver == null && (q == null || q.isEmpty())) {
				cancel();
				return true;
			}
			return false;
		}
		inboundDone = true;
		return false;
	}

	final void onInboundNext(Object msg) {
		if (inboundDone) {
			if (log.isDebugEnabled()) {
				log.debug("Dropping frame {}", msg);
			}
			return;
		}
		ReferenceCountUtil.retain(msg);
		if (receiverFastpath && receiver != null) {
			try {
				receiver.onNext(msg);
			}
			finally {
				ReferenceCountUtil.release(msg);
			}

		}
		else {
			Queue<Object> q = receiverQueue;
			if (q == null) {
				q = QueueSupplier.unbounded()
				                 .get();
				receiverQueue = q;
			}
			q.offer(msg);
			if (drainReceiver()) {
				receiverFastpath = true;
			}
		}
	}

	final boolean onInboundComplete() {
		if (isCancelled() || inboundDone) {
			return false;
		}
		inboundDone = true;
		Subscriber<?> receiver = this.receiver;
		if (receiverFastpath && receiver != null) {
			receiver.onComplete();
			cancelReceiver();
			return true;
		}
		drainReceiver();
		return false;
	}

	final boolean onInboundError(Throwable err) {
		if (isCancelled() || inboundDone) {
			Operators.onErrorDropped(err);
			return false;
		}
		inboundDone = true;
		Subscriber<?> receiver = this.receiver;
		this.inboundError = err;
		if (receiverFastpath && receiver != null) {
			cancelReceiver();
			receiver.onError(err);
			return true;
		}
		else {
			drainReceiver();
		}
		return false;
	}

	final void terminateReceiver(Queue<?> q, Subscriber<?> a) {
		cancelReceiver();
		if (q != null) {
			q.clear();
		}
		Throwable ex = inboundError;
		if (ex != null) {
			a.onError(ex);
		}
		else {
			a.onComplete();
		}
		parent.context.fireContextActive(parent);
	}

	final void unsubscribeReceiver() {
		receiverDemand = 0L;
		receiver = null;
	}

	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<FluxReceive, Cancellation> CANCEL =
			AtomicReferenceFieldUpdater.newUpdater(FluxReceive.class,
					Cancellation.class,
					"receiverCancel");

	static final Cancellation CANCELLED = () -> {
	};

	static final Logger log = Loggers.getLogger(FluxReceive.class);
}
