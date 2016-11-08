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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Cancellation;
import reactor.core.Loopback;
import reactor.core.Producer;
import reactor.core.Receiver;
import reactor.core.Trackable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyState;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.QueueSupplier;

/**
 * A bridge between an immutable {@link Channel} and {@link NettyInbound} /
 * {@link NettyOutbound} semantics exposed to user
 * {@link reactor.ipc.netty.NettyConnector#newHandler(BiFunction)}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public class NettyOperations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		implements Trackable, NettyInbound, NettyOutbound, Subscription, Producer,
		           Loopback, Publisher<Object> {

	/**
	 * The attribute in {@link Channel} to store the current {@link NettyOperations}
	 */
	public static final AttributeKey<NettyOperations> OPERATIONS_ATTRIBUTE_KEY =
			AttributeKey.newInstance("nettyOperations");

	/**
	 * Add the shareable {@link NettyHandlerNames#ReactiveBridge} handler to the channel.
	 *
	 * @param ch a given channel
	 */
	public static void addReactiveBridgeHandler(Channel ch) {
		ch.pipeline()
		  .addLast(NettyHandlerNames.ReactiveBridge, BRIDGE);
	}

	/**
	 * Create a new {@link NettyOperations} attached to the {@link Channel} attribute
	 * {@link #OPERATIONS_ATTRIBUTE_KEY}.
	 * Attach the {@link NettyHandlerNames#ReactiveBridge} handle.
	 *
	 * @param channel the new {@link Channel} connection
	 * @param handler the user-provided {@link BiFunction} i/o handler
	 * @param sink the user-facing {@link Mono} emitting {@link NettyState}
	 * @param onClose the dispose callback
	 * @param <INBOUND> the {@link NettyInbound} type
	 * @param <OUTBOUND> the {@link NettyOutbound} type
	 *
	 * @return the created {@link NettyOperations} bridge
	 */
	public static <INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound> NettyOperations<INBOUND, OUTBOUND> bind(
			Channel channel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> sink,
			Cancellation onClose) {

		@SuppressWarnings("unchecked") NettyOperations<INBOUND, OUTBOUND> ops =
				new NettyOperations<>(channel, handler, sink, onClose);

		channel.attr(OPERATIONS_ATTRIBUTE_KEY)
		       .set(ops);

		addReactiveBridgeHandler(channel);

		return ops;
	}

	/**
	 * Return a new {@link ChannelFutureListener} to emit that will bind connection
	 * errors to the passed sink. It will be synchronously disposable via
	 * {@link Cancellation#dispose()}.
	 *
	 * @param sink the {@link Channel} to monitor
	 * @param onClose the {@link Channel} to monitor
	 * @param emitChannelState emit a {@link #newNettyState(Channel, Cancellation)} on
	 * success
	 *
	 * @return a new {@link ChannelFutureListener}
	 */
	public static Cancellation newConnectHandler(ChannelFuture f,
			MonoSink<NettyState> sink,
			Cancellation onClose,
			boolean emitChannelState) {

		ChannelConnectHandler c =
				new ChannelConnectHandler(f, sink, onClose, emitChannelState);
		f.addListener(c);
		return c;
	}

	/**
	 * Return a new {@link ChannelFutureListener} to emit that will bind connection
	 * errors to the passed sink. It will be synchronously disposable via
	 * {@link Cancellation#dispose()}. It won't emit any success by default and will
	 * leave further {@link #onChannelActive(ChannelHandlerContext)} or
	 * {@link #onInboundNext(Object)} emit it.
	 *
	 * @param sink the {@link Channel} to monitor
	 * @param onClose the {@link Channel} to monitor
	 *
	 * @return a new {@link ChannelFutureListener}
	 */
	public static Cancellation newConnectHandler(ChannelFuture f,
			MonoSink<NettyState> sink,
			Cancellation onClose) {
		return newConnectHandler(f, sink, onClose, false);
	}

	/**
	 * Create a {@link ChannelWriter} used by {@link NettyOperations} to prepare the
	 * outbound flow via
	 * {@link ChannelOutboundHandler#write(ChannelHandlerContext, Object, ChannelPromise)}
	 *
	 * @param writeStream Outgoing datasource
	 * @param flushMode Flushing behavior
	 *
	 * @return a new {@link ChannelWriter}
	 */
	public static ChannelWriter newWriter(Publisher<?> writeStream,
			ChannelWriter.FlushMode flushMode) {
		return new ChannelWriter(writeStream, flushMode);
	}

	@SuppressWarnings("unchecked")
	public static <INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound> BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> noopHandler() {
		return PING;
	}

	/**
	 * Return a new {@link NettyState} bound to the given {@link Channel}
	 *
	 * @param ch the {@link Channel} to monitor
	 * @param onClose the dispose callback
	 *
	 * @return a new {@link NettyState} bound to the given {@link Channel}
	 */
	static NettyState newNettyState(Channel ch, Cancellation onClose) {
		return new ChannelState(ch, onClose);
	}

	final BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>>
			handler;

	final MonoSink<NettyState> clientSink;
	final Channel              channel;
	final Scheduler            ioScheduler;
	final Flux<?>              input;
	final Cancellation         onClose;

	// guarded //
	Subscriber<? super Object> receiver;
	boolean                    receiverCaughtUp;
	Queue<Object>              inboundQueue;
	boolean                    inboundDone;
	Throwable                  error;
	long                       requestedInbound;
	int                        wip;
	volatile Cancellation cancel;
	volatile boolean      flushEach;

	protected NettyOperations(Channel channel,
			NettyOperations<INBOUND, OUTBOUND> replaced) {
		this(channel, replaced.handler, replaced.clientSink, replaced.onClose);
		this.receiver = replaced.receiver;
		this.receiverCaughtUp = replaced.receiverCaughtUp;
		this.inboundQueue = replaced.inboundQueue;
		this.inboundDone = replaced.inboundDone;
		this.error = replaced.error;
		this.requestedInbound = replaced.requestedInbound;
		this.wip = replaced.wip;
		this.cancel = replaced.cancel;
		this.flushEach = replaced.flushEach;
	}

	protected NettyOperations(Channel channel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> clientSink,
			Cancellation onClose) {
		this.handler = Objects.requireNonNull(handler, "handler");
		this.channel = Objects.requireNonNull(channel, "channel");
		this.clientSink = clientSink;
		this.onClose = onClose;
		this.ioScheduler = Schedulers.fromExecutor(channel.eventLoop());
		this.input = Flux.from(this)
		                 .subscribeOn(ioScheduler);
	}

	@Override
	final public void cancel() {
		cancelReceiver();

		if (wip++ == 0) {
			Queue<Object> q = inboundQueue;
			if (q != null) {
				Object o;
				while ((o = q.poll()) != null) {
					ReferenceCountUtil.release(o);
				}
			}
		}
	}

	@Override
	final public Channel channel() {
		return channel;
	}

	@Override
	final public Object connectedInput() {
		return input;
	}

	@Override
	final public Object connectedOutput() {
		io.netty.channel.Channel parent = channel.parent();
		SocketAddress remote = channel.remoteAddress();
		SocketAddress local = channel.localAddress();
		String src = local != null ? local.toString() : "";
		String dst = remote != null ? remote.toString() : "";
		if (parent != null) {
		}
		else {
			String _src = src;
			src = dst;
			dst = _src;
		}

		return src.replaceFirst("localhost", "") + ":" + dst.replaceFirst("localhost",
				"");
	}

	@Override
	final public Object downstream() {
		return receiver;
	}

	@Override
	public NettyOutbound flushEach() {
		flushEach = true;
		return this;
	}

	@Override
	public long getCapacity() {
		return Long.MAX_VALUE;
	}

	@Override
	final public Throwable getError() {
		return error;
	}

	@Override
	final public long getPending() {
		return inboundQueue != null ? inboundQueue.size() : 0;
	}

	@Override
	final public boolean isCancelled() {
		return cancel == CANCELLED;
	}

	@Override
	public boolean isDisposed() {
		return channel.attr(OPERATIONS_ATTRIBUTE_KEY)
		              .get() != this;
	}

	@Override
	final public boolean isStarted() {
		return channel.isActive();
	}

	@Override
	final public boolean isTerminated() {
		return !channel.isOpen();
	}

	/**
	 * Return available dispose callback
	 * @return available dispose callback
	 */
	final protected Cancellation dependentCancellation(){
		return onClose;
	}

	@Override
	public NettyOperations<INBOUND, OUTBOUND> onClose(final Runnable onClose) {
		channel.pipeline()
		       .addLast(new ChannelDuplexHandler() {
			       @Override
			       public void channelInactive(ChannelHandlerContext ctx)
					       throws Exception {
				       onClose.run();
				       super.channelInactive(ctx);
			       }
		       });
		return this;
	}

	@Override
	public NettyOperations<INBOUND, OUTBOUND> onReadIdle(long idleTimeout,
			final Runnable onReadIdle) {
		channel.pipeline()
		       .addFirst(new IdleStateHandler(idleTimeout, 0, 0, TimeUnit.MILLISECONDS) {
			       @Override
			       protected void channelIdle(ChannelHandlerContext ctx,
					       IdleStateEvent evt) throws Exception {
				       if (evt.state() == IdleState.READER_IDLE) {
					       onReadIdle.run();
				       }
				       super.channelIdle(ctx, evt);
			       }
		       });
		return this;
	}

	@Override
	public NettyOperations<INBOUND, OUTBOUND> onWriteIdle(long idleTimeout,
			final Runnable onWriteIdle) {
		channel.pipeline()
		       .addLast(new IdleStateHandler(0, idleTimeout, 0, TimeUnit.MILLISECONDS) {
			       @Override
			       protected void channelIdle(ChannelHandlerContext ctx,
					       IdleStateEvent evt) throws Exception {
				       if (evt.state() == IdleState.WRITER_IDLE) {
					       onWriteIdle.run();
				       }
				       super.channelIdle(ctx, evt);
			       }
		       });
		return this;
	}

	@Override
	public Flux<?> receiveObject() {
		return input;
	}

	@Override
	public InetSocketAddress remoteAddress() {
		return (InetSocketAddress) channel.remoteAddress();
	}

	@Override
	public void request(long n) {
		if (Operators.validate(n)) {
			this.requestedInbound = Operators.addCap(requestedInbound, n);
			drainReceiver();
		}
	}

	@Override
	public long requestedFromDownstream() {
		return requestedInbound;
	}

	@Override
	public Mono<Void> send(final Publisher<? extends ByteBuf> dataStream) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		return new MonoWriter(dataStream);
	}

	@Override
	public Mono<Void> sendObject(final Publisher<?> dataStream) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		return new MonoWriter(dataStream);
	}

	@Override
	public void subscribe(Subscriber<? super Object> s) {
		if (isDisposed()) {
			Operators.error(s,
					new IllegalStateException("This inbound is not " + "active " + "anymore"));
			return;
		}
		if (log.isDebugEnabled()) {
			log.debug("Subscribing inbound receiver [pending: " + "" + getPending() + ", inboundDone: " + inboundDone + "]");
		}
		if (receiver == null) {
			if (inboundDone) {
				if (error != null) {
					Operators.error(s, error);
					return;
				}
				else if (getPending() == 0) {
					Operators.complete(s);
					return;
				}
			}

			initReceiver(s);
			s.onSubscribe(this);
		}
		else {
			Operators.error(s,
					new IllegalStateException(
							"Only one connection receive subscriber allowed."));
		}
	}

	@Override
	public String toString() {
		return channel.toString();
	}

	/**
	 * React after inbound {@link Channel#read}
	 *
	 * @param ctx the current {@link ChannelHandlerContext}
	 */
	protected void afterInboundNext(ChannelHandlerContext ctx) {
		ctx.fireChannelReadComplete();
	}

	/**
	 * Return buffered packets in backlog
	 *
	 * @return buffered packet count
	 */
	final protected long bufferedInbound() {
		return getPending();
	}

	/**
	 * React on input initialization
	 *
	 * @param ctx the current {@link ChannelHandlerContext}
	 */
	@SuppressWarnings("unchecked")
	protected void onChannelActive(ChannelHandlerContext ctx) {
		handler.apply((INBOUND) this, (OUTBOUND) this)
		       .subscribe(new CloseSubscriber(ctx));

		if (clientSink != null) {
			clientSink.success(newNettyState(ctx.channel(), onClose));
		}
	}

	/**
	 * React on inbound/outbound completion (last packet)
	 */
	protected void onChannelComplete() {
		if (isCancelled() || inboundDone) {
			return;
		}
		inboundDone = true;
		Subscriber<?> receiver = this.receiver;
		if (receiverCaughtUp && receiver != null) {
			cancelReceiver();
			receiver.onComplete();
		}
		if (clientSink != null) {
			clientSink.success();
		}
		drainReceiver();
	}

	/**
	 * React on inbound/outbound error
	 *
	 * @param err the {@link }
	 */
	protected void onChannelError(Throwable err) {
		if (err == null) {
			err = new NullPointerException("error is null");
		}
		if (isCancelled() || inboundDone) {
			Operators.onErrorDropped(err);
			return;
		}
		inboundDone = true;
		if (receiverCaughtUp && receiver != null) {
			receiver.onError(err);
		}
		else {
			this.error = err;
			inboundDone = true;
			drainReceiver();
		}
	}

	/**
	 * React on inbound {@link Channel#read}
	 *
	 * @param msg the read payload
	 */
	protected void onInboundNext(Object msg) {
		if (msg == null) {
			onChannelError(new NullPointerException("msg is null"));
			return;
		}
		if (inboundDone) {
			Operators.onNextDropped(msg);
			return;
		}
		ReferenceCountUtil.retain(msg);
		if (receiverCaughtUp && receiver != null) {
			try {
				receiver.onNext(msg);
			}
			finally {
				ReferenceCountUtil.release(msg);
				channel.read();
			}

		}
		else {
			Queue<Object> q = inboundQueue;
			if (q == null) {
				q = QueueSupplier.unbounded()
				                 .get();
				inboundQueue = q;
			}
			q.offer(msg);
			if (drainReceiver()) {
				receiverCaughtUp = true;
			}
		}
	}

	/**
	 * React on new outbound {@link Publisher} writer
	 *
	 * @param ctx the current {@link ChannelHandlerContext}
	 * @param writeStream the {@link Publisher} to send
	 * @param flushMode the flush strategy
	 * @param promise an actual promise fulfilled on writer success/error
	 */
	protected void onOutboundWriter(ChannelHandlerContext ctx,
			Publisher<?> writeStream,
			ChannelWriter.FlushMode flushMode,
			ChannelPromise promise) {
		if (flushMode == ChannelWriter.FlushMode.MANUAL_COMPLETE) {
			writeStream.subscribe(new FlushOnTerminateSubscriber(this, ctx, promise));
		}
		else {
			writeStream.subscribe(new FlushOnEachSubscriber(this, ctx, promise));
		}
	}

	/**
	 * Return the available {@link MonoSink} for user-facing lifecycle handling
	 *
	 * @return the available {@link MonoSink} for user-facing lifecycle handling
	 */
	final protected MonoSink<NettyState> clientSink() {
		return clientSink;
	}

	/**
	 * Return the user-provided {@link reactor.ipc.netty.NettyConnector} handler
	 *
	 * @return the user-provided {@link reactor.ipc.netty.NettyConnector} handler
	 */
	final protected BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler() {
		return handler;
	}

	/**
	 * Attach a {@link Publisher} as a {@link Channel} writer. Forward write listener
	 * commit/failures events to the passed {@link Subscriber}
	 *
	 * @param encodedWriter the {@link Publisher} to write from
	 * @param postWriter the {@link Subscriber} to forward commit success/failure
	 */
	final protected void doChannelWriter(final Publisher<?> encodedWriter,
			final Subscriber<? super Void> postWriter) {

		final ChannelFutureListener postWriteListener = future -> {
			postWriter.onSubscribe(Operators.emptySubscription());
			if (future.isSuccess()) {
				postWriter.onComplete();
			}
			else {
				postWriter.onError(future.cause());
			}
		};

		ChannelWriter.FlushMode mode;
		if (flushEach) {
			mode = ChannelWriter.FlushMode.AUTO_EACH;
		}
		else {
			mode = ChannelWriter.FlushMode.MANUAL_COMPLETE;
		}

		ChannelWriter writer = newWriter(encodedWriter, mode);

		if (channel.eventLoop()
		           .inEventLoop()) {

			channel.write(writer)
			       .addListener(postWriteListener);
		}
		else {
			channel.eventLoop()
			       .execute(() -> channel.write(writer)
			                             .addListener(postWriteListener));
		}
	}

	/**
	 * Write an individual packet (to be encoded further if the pipeline permits).
	 *
	 * @param data the payload to write on the {@link Channel}
	 * @param ctx the actual {@link ChannelHandlerContext}
	 *
	 * @return the {@link ChannelFuture} of the successful/not payload write
	 */
	protected ChannelFuture doOnWrite(Object data, ChannelHandlerContext ctx) {
		if (Unpooled.EMPTY_BUFFER != data) {
			return ctx.channel()
			          .write(data);
		}
		return null;
	}

	/**
	 * Callback when a writer {@link Subscriber} has effectively terminated listening
	 * on further {@link Publisher} signals.
	 *
	 * @param ctx the actual {@link ChannelHandlerContext}
	 * @param last an optional callback for the last written payload
	 * @param promise the promise to fulfil for acknowledging termination success
	 * @param exception non null if the writer has terminated with a failure
	 */
	protected void doOnTerminatedWriter(ChannelHandlerContext ctx,
			ChannelFuture last,
			final ChannelPromise promise,
			final Throwable exception) {
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
			if (exception != null) {
				promise.tryFailure(exception);
			}
			else {
				promise.trySuccess();
			}
		}
	}

	final void cancelReceiver() {
		Cancellation c = cancel;
		if (c != CANCELLED) {
			c = CANCEL.getAndSet(this, CANCELLED);
			if (c != CANCELLED) {
				requestedInbound = 0L;
				if (c != null) {
					c.dispose();
				}
			}
		}
	}

	final boolean drainReceiver() {
		if (wip++ != 0) {
			return false;
		}

		int missed = 1;

		for (; ; ) {
			final Queue<Object> q = inboundQueue;
			final Subscriber<? super Object> a = receiver;

			if (a == null) {
				return false;
			}

			long r = requestedInbound;
			long e = 0L;

			while (e != r) {
				if (isCancelled()) {
					return false;
				}

				boolean d = inboundDone;
				Object v = q != null ? q.poll() : null;
				boolean empty = v == null;

				if (d && empty) {
					cancelReceiver();
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
					channel.read();
					break;
				}

				try {
					a.onNext(v);
				}
				finally {
					ReferenceCountUtil.release(v);
					channel.read();
				}

				e++;
			}

			if (e == r) {
				if (isCancelled()) {
					return false;
				}

				if (inboundDone && (q == null || q.isEmpty())) {
					cancelReceiver();
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
					if ((requestedInbound -= e) > 0L) {
						channel.read();
					}
				}
			}

			missed = (wip = wip - missed);
			if (missed == 0) {
				if (r == Long.MAX_VALUE) {
					channel.config()
					       .setAutoRead(true);
					channel.read();
					return true;
				}
				return false;
			}
		}
	}

	final void initReceiver(Subscriber<? super Object> s) {
		receiver = s;
		CANCEL.lazySet(this, () -> {
			if (channel.eventLoop()
			           .inEventLoop()) {
				unsubscribeReceiver();
			}
			else {
				channel.eventLoop()
				       .execute(this::unsubscribeReceiver);
			}

			channel.config()
			       .setAutoRead(false);
		});
		wip = 0;
	}

	final void unsubscribeReceiver() {
		receiver = null;
	}

	/**
	 *
	 */
	static final ChannelHandler                                             BRIDGE    =
			new NettyChannelHandler();
	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<NettyOperations, Cancellation> CANCEL    =
			AtomicReferenceFieldUpdater.newUpdater(NettyOperations.class,
					Cancellation.class,
					"cancel");
	static final Cancellation                                               CANCELLED =
			() -> {
			};
	static final Logger                                                     log       =
			Loggers.getLogger(NettyOperations.class);

	static final BiFunction PING = (i, o) -> Flux.empty();

	final class MonoWriter extends Mono<Void> implements Receiver, Loopback {

		final Publisher<?> dataStream;

		public MonoWriter(Publisher<?> dataStream) {
			this.dataStream = dataStream;
		}

		@Override
		public Object connectedInput() {
			return NettyOperations.this;
		}

		@Override
		public Object connectedOutput() {
			return NettyOperations.this;
		}

		@Override
		public void subscribe(Subscriber<? super Void> s) {
			try {
				doChannelWriter(dataStream, s);
			}
			catch (Throwable throwable) {
				Operators.error(s, throwable);
			}
		}

		@Override
		public Object upstream() {
			return dataStream;
		}
	}

}