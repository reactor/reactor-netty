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

package reactor.ipc.netty.common;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Cancellation;
import reactor.core.Exceptions;
import reactor.core.Loopback;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.Trackable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.Channel;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.QueueSupplier;

/**
 * Netty {@link io.netty.channel.ChannelInboundHandler} implementation that passes data to a Reactor {@link
 * Channel}.
 *
 * @author Stephane Maldini
 */
public class NettyChannelHandler<C extends NettyChannel> extends ChannelDuplexHandler
		implements Producer, Publisher<Object> {

	protected static final Logger log = Loggers.getLogger(NettyChannelHandler.class);

	protected final Function<? super NettyChannel, ? extends Publisher<Void>> handler;
	protected final ChannelBridge<C>                               bridgeFactory;
	protected final Flux<Object>                                   input;

	final InboundSink inboundEmitter;

	public NettyChannelHandler(
			Function<? super NettyChannel, ? extends Publisher<Void>> handler,
			ChannelBridge<C> bridgeFactory,
			io.netty.channel.Channel ch) {
		this(handler, bridgeFactory, ch, null);
	}

	@SuppressWarnings("unchecked")
	public NettyChannelHandler(Function<? super NettyChannel, ? extends Publisher<Void>> handler,
			ChannelBridge<C> bridgeFactory,
			io.netty.channel.Channel ch,
			NettyChannelHandler parent) {
		this.handler = handler;
		if (parent == null) {
			this.inboundEmitter = new InboundSink(ch);
			//guard requests/cancel/subscribe
			this.input = Flux.from(this)
			                 .subscribeOn(Schedulers.fromExecutor(ch.eventLoop()));
		}
		else {

			this.inboundEmitter = parent.inboundEmitter;
			this.input = parent.input;
		}
		this.bridgeFactory = bridgeFactory;

	}

	@Override
	public FluxSink<Object> downstream() {
		return inboundEmitter;
	}

	@Override
	public void channelActive(final ChannelHandlerContext ctx) throws Exception {
		super.channelActive(ctx);
		handler.apply(bridgeFactory.createChannelBridge(ctx.channel(), input))
		       .subscribe(new CloseSubscriber(ctx));
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		try {
			inboundEmitter.complete();
			super.channelInactive(ctx);
		}
		catch (Throwable err) {
			Exceptions.throwIfFatal(err);
			inboundEmitter.error(err);
		}
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		doRead(msg);
	}

	@SuppressWarnings("unchecked")
	protected final void doRead(Object msg) {
		if (msg == null) {
			return;
		}
		try {
			if (msg == Unpooled.EMPTY_BUFFER || msg instanceof EmptyByteBuf) {
				return;
			}
			inboundEmitter.next(msg);
		}
		catch (Throwable err) {
			Exceptions.throwIfFatal(err);
			inboundEmitter.error(err);
		}
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		if (inboundEmitter.requested != 0L) {
			ctx.read();
		}
		else{
			if(log.isDebugEnabled()) {
				log.debug("Pausing read due to lack of request");
			}
		}
		ctx.fireChannelReadComplete();
	}

	@Override
	public void write(final ChannelHandlerContext ctx, Object msg, final ChannelPromise promise) throws Exception {
		if (msg instanceof ChannelWriter) {
			@SuppressWarnings("unchecked") ChannelWriter dataWriter = (ChannelWriter) msg;
			if (dataWriter.flushMode == FlushMode.MANUAL_COMPLETE) {
				dataWriter.writeStream.subscribe(new FlushOnTerminateSubscriber(ctx,
						promise));
			}
			else {
				dataWriter.writeStream.subscribe(new FlushOnEachSubscriber(ctx, promise));
			}
		}
		else {
			super.write(ctx, msg, promise);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable err) throws Exception {
		Exceptions.throwIfFatal(err);
		inboundEmitter.error(err);
	}

	protected ChannelFuture doOnWrite(Object data, ChannelHandlerContext ctx) {
		if (Unpooled.EMPTY_BUFFER != data) {
			return ctx.channel().write(data);
		}
		return null;
	}

	protected void doOnTerminate(ChannelHandlerContext ctx, ChannelFuture last, final ChannelPromise promise, final
			Throwable exception) {
		if (ctx.channel()
		       .isOpen()) {
			ChannelFutureListener listener = future -> {
				if (exception != null) {
					promise.tryFailure(exception);
				}
				else if (future.isSuccess()) {
					promise.trySuccess();
				}
				else {
					promise.tryFailure(future.cause());
				}
			};

			if (last != null) {
				last.addListener(listener);
				ctx.flush();
			}
		}
		else {
			if(exception != null) {
				promise.tryFailure(exception);
			}
			else {
				promise.trySuccess();
			}
		}
	}

	/**
	 *
	 */
	public enum FlushMode {
		AUTO_EACH, AUTO_LOOP, MANUAL_COMPLETE, MANUAL_BOUNDARY
	}

	/**
	 *
	 */
	final public static class ChannelWriter {

		final Publisher<?> writeStream;
		final FlushMode    flushMode;

		public ChannelWriter(Publisher<?> writeStream, FlushMode flushMode) {
			this.writeStream = writeStream;
			this.flushMode = flushMode;
		}
	}

	final static class CloseSubscriber implements Subscriber<Void> {

		private final ChannelHandlerContext ctx;

		public CloseSubscriber(ChannelHandlerContext ctx) {
			this.ctx = ctx;
		}

		@Override
		public void onSubscribe(Subscription s) {
			s.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(Void aVoid) {
		}

		@Override
		public void onError(Throwable t) {
			if(t instanceof IOException && t.getMessage().contains("Broken pipe")){
				if (log.isDebugEnabled()) {
					log.debug("Connection closed remotely", t);
				}
				return;
			}

			log.error("Error processing connection. Closing the channel.", t);

			ctx.channel().eventLoop().execute(ctx::close);
		}

		@Override
		public void onComplete() {
			if (log.isDebugEnabled()) {
				log.debug("Closing connection");
			}
			ctx.channel().eventLoop().execute(ctx::close);
		}
	}

	final class FlushOnTerminateSubscriber
			implements Subscriber<Object>, ChannelFutureListener, Loopback {

		private final ChannelHandlerContext ctx;
		private final ChannelPromise        promise;
		ChannelFuture lastWrite;
		Subscription  subscription;

		public FlushOnTerminateSubscriber(ChannelHandlerContext ctx, ChannelPromise promise) {
			this.ctx = ctx;
			this.promise = promise;
		}

		@Override
		public void operationComplete(ChannelFuture future) throws Exception {
			Subscription subscription = this.subscription;
			this.subscription = null;
			if(subscription != null && inboundEmitter.getPending() == 0L) {
				if (log.isDebugEnabled()) {
					log.debug("Cancel from remotely closed connection");
				}
				subscription.cancel();
			}
		}

		@Override
		public Object connectedInput() {
			return NettyChannelHandler.this;
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
		public void onNext(final Object w) {
			if (w == null) {
				throw Exceptions.argumentIsNullException();
			}
			if (subscription == null) {
				throw Exceptions.failWithCancel();
			}
			try {
				ChannelFuture cf = doOnWrite(w, ctx);
				lastWrite = cf;
				if (cf != null && log.isDebugEnabled()) {
					cf.addListener(new ChannelFutureListener() {
						@Override
						public void operationComplete(ChannelFuture future) throws Exception {
							if (!future.isSuccess()) {
								log.error("write error :" + w, future.cause());
								if (ByteBuf.class.isAssignableFrom(w.getClass())) {
									((ByteBuf) w).resetReaderIndex();
								}
							}
						}
					});
				}
			}
			catch (Throwable t) {
				log.error("Write error for "+w, t);
				onError(t);
			}
		}

		@Override
		public void onError(Throwable t) {
			if (t == null) {
				throw Exceptions.argumentIsNullException();
			}
			if (subscription == null) {
				throw new IllegalStateException("already flushed", t);
			}
			log.error("Write error", t);
			subscription = null;
			ctx.channel()
			   .closeFuture()
			   .removeListener(this);
			ctx.channel().eventLoop().execute(() -> doOnTerminate(ctx, lastWrite, promise,
					t));
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
			ctx.channel().eventLoop().execute(() -> doOnTerminate(ctx, lastWrite,
					promise, null));
		}
	}

	final class FlushOnEachSubscriber
			implements Subscriber<Object>, ChannelFutureListener, Loopback,
			           Trackable, Receiver {

		private final ChannelHandlerContext ctx;
		private final ChannelPromise        promise;

		volatile Subscription subscription;

		private final ChannelFutureListener writeListener = new ChannelFutureListener() {

			@Override
			public void operationComplete(ChannelFuture future) throws Exception {
				if (!future.isSuccess()) {
					promise.tryFailure(future.cause());
					if(log.isDebugEnabled()) {
						log.debug("Write error", future.cause());
					}
					return;
				}
				Subscription subscription = FlushOnEachSubscriber.this.subscription;
					if (subscription != null) {
						subscription.request(1L);
					}
			}
		};

		public FlushOnEachSubscriber(ChannelHandlerContext ctx, ChannelPromise promise) {
			this.ctx = ctx;
			this.promise = promise;
		}

		@Override
		public boolean isCancelled() {
			return !ctx.channel().isOpen();
		}

		@Override
		public boolean isStarted() {
			return subscription != null;
		}

		@Override
		public boolean isTerminated() {
			return !ctx.channel().isOpen();
		}

		@Override
		public Object connectedInput() {
			return NettyChannelHandler.this;
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
		public void onNext(Object w) {
			if (w == null) {
				throw Exceptions.argumentIsNullException();
			}
			if (subscription == null) {
				throw Exceptions.failWithCancel();
			}
			try {
				ChannelFuture cf = doOnWrite(w, ctx);
				if (cf != null) {
					cf.addListener(writeListener);
				}
				ctx.flush();
			}
			catch (Throwable t) {
				log.error("Write error for "+w, t);
				onError(t);
				throw Exceptions.failWithCancel();
			}
		}

		@Override
		public void onError(Throwable t) {
			if (t == null) {
				throw Exceptions.argumentIsNullException();
			}
			if (subscription == null) {
				throw new IllegalStateException("already flushed", t);
			}
			log.error("Write error", t);
			subscription = null;
			ctx.channel()
			   .closeFuture()
			   .removeListener(this);
			ctx.channel().eventLoop().execute(() -> doOnTerminate(ctx, null, promise, t));
		}

		@Override
		public void onComplete() {
			if (subscription == null) {
				throw new IllegalStateException("already flushed");
			}
			subscription = null;
			if (log.isDebugEnabled()) {
				log.debug("Flush Connection");
			}
			ctx.channel()
			   .closeFuture()
			   .removeListener(this);

			ctx.channel().eventLoop().execute(() -> doOnTerminate(ctx, null, promise,
					null));
		}

		@Override
		public void operationComplete(ChannelFuture future) throws Exception {
			Subscription subscription = this.subscription;
			this.subscription = null;
			if(subscription != null && inboundEmitter.getPending() == 0L) {
				if (log.isDebugEnabled()) {
					log.debug("Cancel from remotely closed connection");
				}
				subscription.cancel();
			}
		}

		@Override
		public Object upstream() {
			return subscription;
		}
	}

	public Function<? super NettyChannel, ? extends Publisher<Void>> getHandler() {
		return handler;
	}

	@Override
	public void subscribe(Subscriber<? super Object> s) {
		if(log.isDebugEnabled()){
			log.debug("Subscribing inbound receiver [pending: " +
					""+inboundEmitter.getPending()+", done: "+inboundEmitter.done+"]");
		}
		if (inboundEmitter.actual == null) {
			if (inboundEmitter.done) {
				if (inboundEmitter.error != null) {
					Operators.error(s, inboundEmitter.error);
					return;
				}
				else if (inboundEmitter.getPending() == 0) {
					Operators.complete(s);
					return;
				}
			}

			inboundEmitter.init(s);
			s.onSubscribe(inboundEmitter);
		}
		else {
			Operators.error(s,
					new IllegalStateException(
							"Only one connection receive subscriber allowed."));
		}
	}

	static final class InboundSink
			implements FluxSink<Object>, Trackable, Cancellation, Subscription, Producer {

		final io.netty.channel.Channel ch;

		/**
		 * guarded by event loop
		 */
		Subscriber<? super Object> actual;
		boolean caughtUp;
		Queue<Object> queue;
		boolean done;
		Throwable error;
		long requested;
		int wip;

		volatile Cancellation cancel;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<InboundSink, Cancellation> CANCEL =
				AtomicReferenceFieldUpdater.newUpdater(InboundSink.class,
						Cancellation.class,
						"cancel");

		static final Cancellation CANCELLED = () -> {
		};

		public InboundSink(io.netty.channel.Channel channel) {
			this.ch = channel;
			CANCEL.lazySet(this, this);
		}

		void init(Subscriber<? super Object> s){
			actual = s;
			CANCEL.lazySet(this, this);
			wip = 0;
		}

		@Override
		public void next(Object value) {
			if (value == null) {
				error(new NullPointerException("value is null"));
				return;
			}
			if (done) {
				Operators.onNextDropped(value);
				return;
			}
			if (caughtUp && actual != null) {
				try {
					actual.onNext(value);
				}
				finally {
					ch.read();
					ReferenceCountUtil.release(value);
				}

			}
			else {
				Queue<Object> q = queue;
				if (q == null) {
					q = QueueSupplier.unbounded().get();
					queue = q;
				}
				q.offer(value);
				if (drain()) {
					caughtUp = true;
				}
			}
		}

		@Override
		public void error(Throwable error) {
			if (error == null) {
				error = new NullPointerException("error is null");
			}
			if (isCancelled() || done) {
				Operators.onErrorDropped(error);
				return;
			}
			done = true;
			if (caughtUp && actual != null) {
				actual.onError(error);
			}
			else {
				this.error = error;
				done = true;
				drain();
			}
		}

		@Override
		public boolean isCancelled() {
			return cancel == CANCELLED;
		}

		@Override
		public void complete() {
			if (isCancelled() || done) {
				return;
			}
			done = true;
			if (caughtUp && actual != null) {
				actual.onComplete();
			}
			drain();
		}

		boolean drain() {
			if (wip++ != 0) {
				return false;
			}

			int missed = 1;

			for (; ; ) {
				final Queue<Object> q = queue;
				final Subscriber<? super Object> a = actual;

				if (a == null) {
					return false;
				}

				long r = requested;
				long e = 0L;

				while (e != r) {
					if (isCancelled()) {
						return false;
					}

					boolean d = done;
					Object v = q != null ? q.poll() : null;
					boolean empty = v == null;

					if (d && empty) {
						cancelResource();
						if (q != null) {
							q.clear();
						}
						Throwable ex = error;
						if (ex != null) {
							a.onError(ex);
						}
						else {
							a.onComplete();
						}
						return false;
					}

					if (empty) {
						ch.read();
						break;
					}

					try {
						a.onNext(v);
					}
					finally {
						ReferenceCountUtil.release(v);
						ch.read();
					}

					e++;
				}

				if (e == r) {
					if (isCancelled()) {
						return false;
					}

					if (done && (q == null || q.isEmpty())) {
						cancelResource();
						if (q != null) {
							q.clear();
						}
						Throwable ex = error;
						if (ex != null) {
							a.onError(ex);
						}
						else {
							a.onComplete();
						}
						return false;
					}
				}

				if (e != 0L) {
					if (r != Long.MAX_VALUE) {
						if ((requested -=e) > 0L) {
							ch.read();
						}
					}
				}

				missed = (wip = wip - missed);
				if (missed == 0) {
					if (r == Long.MAX_VALUE) {
						ch.config()
						  .setAutoRead(true);
						  ch.read();
						return true;
					}
					return false;
				}
			}
		}

		@Override
		public void setCancellation(Cancellation c) {
			if (!CANCEL.compareAndSet(this, null, c)) {
				if (cancel != CANCELLED && c != null) {
					c.dispose();
				}
			}
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				this.requested = Operators.addCap(requested, n);
				drain();
			}
		}

		void cancelResource() {
			Cancellation c = cancel;
			if (c != CANCELLED) {
				c = CANCEL.getAndSet(this, CANCELLED);
				if (c != null && c != CANCELLED) {
					requested = 0L;
					c.dispose();
				}
			}
		}

		@Override
		public void cancel() {
			cancelResource();

			if (wip++ == 0) {
				Queue<Object> q = queue;
				if (q != null) {
					Object o;
					while((o = q.poll()) != null){
						ReferenceCountUtil.release(o);
					}
				}
			}
		}

		@Override
		public FluxSink<Object> serialize() {
			return this; //should use event loop
		}

		@Override
		public long requestedFromDownstream() {
			return requested;
		}

		@Override
		public long getCapacity() {
			return Long.MAX_VALUE;
		}

		@Override
		public long getPending() {
			return queue != null ? queue.size() : 0;
		}

		@Override
		public Throwable getError() {
			return error;
		}

		@Override
		public Object downstream() {
			return actual;
		}

		void dereference() {
			actual = null;
		}

		@Override
		public void dispose() {
			if (ch.eventLoop()
			      .inEventLoop()) {
				dereference();
			}
			else {
				ch.eventLoop()
				  .execute(this::dereference);
			}

			ch.config()
			  .setAutoRead(false);
		}
	}
}
