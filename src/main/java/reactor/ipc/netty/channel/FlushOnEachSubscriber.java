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

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.Loopback;
import reactor.core.Receiver;
import reactor.core.Trackable;
import reactor.core.publisher.Operators;

/**
 * @author Stephane Maldini
 */
final class FlushOnEachSubscriber
		implements Subscriber<Object>, ChannelFutureListener, Loopback, Trackable,
		           Receiver {

	final NettyOperations<?, ?> parent;
	final ChannelHandlerContext ctx;
	final ChannelPromise        promise;
	final ChannelFutureListener writeListener = new WriteListener();

	volatile Subscription subscription;

	public FlushOnEachSubscriber(NettyOperations<?, ?> parent,
			ChannelHandlerContext ctx,
			ChannelPromise promise) {
		this.parent = parent;
		this.ctx = ctx;
		this.promise = promise;
	}

	@Override
	public Object connectedInput() {
		return parent;
	}

	@Override
	public boolean isCancelled() {
		return !ctx.channel()
		           .isOpen();
	}

	@Override
	public boolean isStarted() {
		return subscription != null;
	}

	@Override
	public boolean isTerminated() {
		return !ctx.channel()
		           .isOpen();
	}

	@Override
	public void onComplete() {
		if (subscription == null) {
			throw new IllegalStateException("already flushed");
		}
		subscription = null;
		if (NettyOperations.log.isDebugEnabled()) {
			NettyOperations.log.debug("Flush Connection");
		}
		ctx.channel()
		   .closeFuture()
		   .removeListener(this);

		ctx.channel()
		   .eventLoop()
		   .execute(() -> parent.doOnTerminatedWriter(ctx, null, promise, null));
	}

	@Override
	public void onError(Throwable t) {
		if (t == null) {
			throw Exceptions.argumentIsNullException();
		}
		if (subscription == null) {
			throw new IllegalStateException("already flushed", t);
		}
		NettyOperations.log.error("Write error", t);
		subscription = null;
		ctx.channel()
		   .closeFuture()
		   .removeListener(this);
		ctx.channel()
		   .eventLoop()
		   .execute(() -> parent.doOnTerminatedWriter(ctx, null, promise, t));
	}

	@Override
	public void onNext(Object w) {
		if (w == null) {
			throw Exceptions.argumentIsNullException();
		}
		if (subscription == null) {
			throw Exceptions.failWithCancel();
		}
		try {
			ChannelFuture cf = parent.doOnWrite(w, ctx);
			if (cf != null) {
				cf.addListener(writeListener);
			}
			ctx.flush();
		}
		catch (Throwable t) {
			NettyOperations.log.error("Write error for " + w, t);
			onError(t);
			throw Exceptions.failWithCancel();
		}
	}

	@Override
	public void onSubscribe(final Subscription s) {
		if (Operators.validate(subscription, s)) {
			subscription = s;

			ctx.channel()
			   .closeFuture()
			   .addListener(this);

			s.request(1L);
		}
	}

	@Override
	public void operationComplete(ChannelFuture future) throws Exception {
		Subscription subscription = this.subscription;
		this.subscription = null;
		if (subscription != null && future.channel()
		                                  .attr(NettyOperations.OPERATIONS_ATTRIBUTE_KEY)
		                                  .get()
		                                  .bufferedInbound() == 0L) {
			if (NettyOperations.log.isDebugEnabled()) {
				NettyOperations.log.debug("Cancel from remotely closed connection");
			}
			subscription.cancel();
		}
	}

	@Override
	public Object upstream() {
		return subscription;
	}

	private class WriteListener implements ChannelFutureListener {

		@Override
		public void operationComplete(ChannelFuture future) throws Exception {
			if (!future.isSuccess()) {
				promise.tryFailure(future.cause());
				if (NettyOperations.log.isDebugEnabled()) {
					NettyOperations.log.debug("Write error", future.cause());
				}
				return;
			}
			Subscription subscription = FlushOnEachSubscriber.this.subscription;
			if (subscription != null) {
				subscription.request(1L);
			}
		}
	}
}
