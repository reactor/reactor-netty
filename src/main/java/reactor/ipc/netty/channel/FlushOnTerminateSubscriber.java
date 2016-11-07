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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.Loopback;
import reactor.core.publisher.Operators;

/**
 * @author Stephane Maldini
 */
final class FlushOnTerminateSubscriber
		implements Subscriber<Object>, ChannelFutureListener, Loopback {

	final ChannelHandlerContext ctx;
	final ChannelPromise        promise;
	final NettyOperations<?, ?> parent;

	ChannelFuture lastWrite;
	Subscription  subscription;

	public FlushOnTerminateSubscriber(NettyOperations<?, ?> parent,
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
	public void onComplete() {
		if (subscription == null) {
			throw new IllegalStateException("already flushed");
		}
		subscription = null;
		ctx.channel()
		   .closeFuture()
		   .removeListener(this);
		ctx.channel()
		   .eventLoop()
		   .execute(() -> parent.doOnTerminatedWriter(ctx, lastWrite, promise, null));
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
		   .execute(() -> parent.doOnTerminatedWriter(ctx, lastWrite, promise, t));
	}

	@Override
	public void onNext(final Object w) {
		if (w == null) {
			throw Exceptions.argumentIsNullException();
		}
		if (subscription == null) {
			throw Exceptions.failWithCancel();
		}
		try {
			ChannelFuture cf = parent.doOnWrite(w, ctx);
			lastWrite = cf;
			if (cf != null && NettyOperations.log.isDebugEnabled()) {
				cf.addListener((ChannelFutureListener) future -> {
					if (!future.isSuccess()) {
						NettyOperations.log.error("write error :" + w, future.cause());
						if (ByteBuf.class.isAssignableFrom(w.getClass())) {
							((ByteBuf) w).resetReaderIndex();
						}
					}
				});
			}
		}
		catch (Throwable t) {
			NettyOperations.log.error("Write error for " + w, t);
			onError(t);
		}
	}

	@Override
	public void onSubscribe(final Subscription s) {
		if (Operators.validate(subscription, s)) {
			this.subscription = s;

			ctx.channel()
			   .closeFuture()
			   .addListener(this);

			s.request(Long.MAX_VALUE);
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
}
