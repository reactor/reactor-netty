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
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nullable;

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
import reactor.netty.ReactorNetty;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 */
final class FluxReceive extends Flux<Object> implements Subscription, Disposable {

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
	volatile int wip;

	final static AtomicIntegerFieldUpdater<FluxReceive> WIP = AtomicIntegerFieldUpdater.newUpdater
			(FluxReceive.class, "wip");

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
		return (inboundDone && (receiverQueue == null || receiverQueue.isEmpty()));
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
		if (eventLoop.inEventLoop()){
			startReceiver(s);
		}
		else {
			eventLoop.execute(() -> startReceiver(s));
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

	final void cleanQueue(@Nullable Queue<Object> q){
		if (q != null) {
			Object o;
			while ((o = q.poll()) != null) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel, "Dropping frame {}, {} in buffer"), o, getPending());
				}
				ReferenceCountUtil.release(o);
			}
		}
	}

	final void drainReceiver() {
		if(WIP.getAndIncrement(this) != 0){
			return;
		}
		int missed = 1;
		for(;;) {
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
				missed = WIP.addAndGet(this, -missed);
				if(missed == 0){
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
					catch(Throwable t) {
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
				if (needRead) {
					channel.config()
					       .setAutoRead(true);
				}
				missed = WIP.addAndGet(this, -missed);
				if(missed == 0){
					break;
				}
				receiverFastpath = true;
			}

			if ((receiverDemand -= e) > 0L || e > 0L) {
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

			missed = WIP.addAndGet(this, -missed);
			if(missed == 0){
				break;
			}
		}
	}

	final void startReceiver(CoreSubscriber<? super Object> s) {
		if (receiver == null) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Subscribing inbound receiver [pending: {}, cancelled:{}, " +
								"inboundDone: {}]"),
						getPending(),
						isCancelled(),
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

			s.onSubscribe(this);
		}
		else {
			Operators.error(s,
					new IllegalStateException(
							"Only one connection receive subscriber allowed."));
		}
	}

	final void onInboundNext(Object msg) {
		if (inboundDone || isCancelled()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Dropping frame {}, {} in buffer"), msg, getPending());
			}
			ReferenceCountUtil.release(msg);
			return;
		}

		if (receiverFastpath && receiver != null) {
			try {
				if (log.isDebugEnabled()){
					if(msg instanceof ByteBuf) {
						((ByteBuf) msg).touch(format(channel, "Unbounded receiver, bypass inbound " +
								"buffer queue"));
					}
					else if (msg instanceof ByteBufHolder){
						((ByteBufHolder) msg).touch(format(channel,"Unbounded receiver, bypass inbound " +
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
				q = Queues.unbounded()
				          .get();
				receiverQueue = q;
			}
			if (log.isDebugEnabled()){
				if(msg instanceof ByteBuf) {
					((ByteBuf) msg).touch(format(channel,"Buffered ByteBuf in Inbound Flux Queue"));
				}
				else if (msg instanceof ByteBufHolder){
					((ByteBufHolder) msg).touch(format(channel,"Buffered ByteBufHolder in Inbound Flux" +
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
				log.warn(format(channel, "An exception has been observed post termination"), err);
			} else if (log.isWarnEnabled()) {
				log.warn(format(channel, "An exception has been observed post termination, use DEBUG level to see the full stack: {}"), err.toString());
			}
			return;
		}
		CoreSubscriber<?> receiver = this.receiver;
		this.inboundDone = true;
		if(channel.isActive()){
			parent.markPersistent(false);
		}

		if (err instanceof OutOfMemoryError) {
			if (log.isWarnEnabled()) {
//				log.error(format(channel, "An attempt to allocate memory has failed"), err);
			}
			this.inboundError = ReactorNetty.wrapException(err);
			parent.terminate(); //get rid of the resource
		}
		else if (err instanceof ClosedChannelException) {
			this.inboundError = ReactorNetty.wrapException(err);
		}
		else {
			this.inboundError = err;
		}

		if (receiverFastpath && receiver != null) {
			//parent.listener.onReceiveError(channel, err);
			receiver.onError(err);
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
		if(isCancelled()) {
			parent.onInboundCancel();
		}
	}

	@Override
	public String toString() {
		return "FluxReceive{receiverQueueSize" +
				"=" + (receiverQueue != null ? receiverQueue.size() : 0) + ", inboundDone=" + inboundDone
				+ ",inboundError=" + inboundError + '}';
	}

	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<FluxReceive, Disposable> CANCEL =
			AtomicReferenceFieldUpdater.newUpdater(FluxReceive.class,
					Disposable.class,
					"receiverCancel");

	static final Disposable CANCELLED = () -> {
	};

	static final Logger log = Loggers.getLogger(FluxReceive.class);
}
