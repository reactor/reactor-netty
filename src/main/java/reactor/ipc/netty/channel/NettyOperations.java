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
	 * Return a new {@link ChannelFutureListener} to emit that will bind connection
	 * errors to the passed sink. It will be synchronously disposable via
	 * {@link Cancellation#dispose()}. It won't emit any success by default and will
	 * leave further {@link #onActive(ChannelHandlerContext)} or
	 * {@link #onNext(Object)} emit it.
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
	 * Return a new {@link ChannelFutureListener} to emit that will bind connection
	 * errors to the passed sink. It will be synchronously disposable via
	 * {@link Cancellation#dispose()}.
	 *
	 * @param sink the {@link Channel} to monitor
	 * @param onClose the {@link Channel} to monitor
	 * @param emitChannelState emit a {@link #newNettyState(Channel)} on success
	 *
	 * @return a new {@link ChannelFutureListener}
	 */
	public static Cancellation newConnectHandler(ChannelFuture f,
			MonoSink<NettyState> sink,
			Cancellation onClose,
			boolean emitChannelState) {

		ConnectHandler c = new ConnectHandler(f, sink, onClose, emitChannelState);
		f.addListener(c);
		return c;
	}

	/**
	 * Return a new {@link NettyState} bound to the given {@link Channel}
	 *
	 * @param ch the {@link Channel} to monitor
	 *
	 * @return a new {@link NettyState} bound to the given {@link Channel}
	 */
	public static NettyState newNettyState(Channel ch) {
		return new ChannelState(ch);
	}

	/**
	 * Create a new {@link NettyOperations} attached to the {@link Channel} attribute
	 * {@link #OPERATIONS_ATTRIBUTE_KEY}.
	 * Attach the {@link NettyHandlerNames#ReactiveBridge} handle.
	 *
	 * @param channel the new {@link Channel} connection
	 * @param handler the user-provided {@link BiFunction} i/o handler
	 * @param sink the user-facing {@link Mono} emitting {@link NettyState}
	 * @param <INBOUND> the {@link NettyInbound} type
	 * @param <OUTBOUND> the {@link NettyOutbound} type
	 *
	 * @return the created {@link NettyOperations} bridge
	 */
	public static <INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound> NettyOperations<INBOUND, OUTBOUND> bind(
			Channel channel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> sink) {

		@SuppressWarnings("unchecked") NettyOperations<INBOUND, OUTBOUND> ops =
				new NettyOperations<>(channel, handler, sink);

		channel.attr(OPERATIONS_ATTRIBUTE_KEY)
		       .set(ops);

		addReactiveBridgeHandler(channel);

		return ops;
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

	final BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>>
			handler;

	final MonoSink<NettyState> clientSink;
	final Channel              channel;
	final Scheduler            ioScheduler;
	final Flux<?>              input;

	// guarded //
	Subscriber<? super Object> receiver;
	boolean                    caughtUp;
	Queue<Object>              inboundQueue;
	boolean                    done;
	Throwable                  error;
	long                       requestedInbound;
	int                        wip;
	volatile Cancellation cancel;
	volatile boolean      flushEach;

	protected NettyOperations(Channel channel,
			NettyOperations<INBOUND, OUTBOUND> replaced) {
		this(channel, replaced.handler, replaced.clientSink);
		this.receiver = replaced.receiver;
		this.caughtUp = replaced.caughtUp;
		this.inboundQueue = replaced.inboundQueue;
		this.done = replaced.done;
		this.error = replaced.error;
		this.requestedInbound = replaced.requestedInbound;
		this.wip = replaced.wip;
		this.cancel = replaced.cancel;
		this.flushEach = replaced.flushEach;
	}

	protected NettyOperations(Channel channel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			MonoSink<NettyState> clientSink) {
		this.handler = Objects.requireNonNull(handler, "handler");
		this.channel = Objects.requireNonNull(channel, "channel");
		this.clientSink = clientSink;
		this.ioScheduler = Schedulers.fromExecutor(channel.eventLoop());
		this.input = Flux.from(this)
		                 .subscribeOn(ioScheduler);
	}

	final public long bufferedInbound() {
		return getPending();
	}

	@Override
	final public void cancel() {
		cancelResource();

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
	final public Channel channel() {
		return channel;
	}

	@Override
	public boolean isDisposed() {
		return channel.attr(OPERATIONS_ATTRIBUTE_KEY)
		                                   .get() != this;
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
	final public boolean isStarted() {
		return channel.isActive();
	}

	@Override
	final public boolean isTerminated() {
		return !channel.isOpen();
	}

	/**
	 * @param ctx
	 */
	@SuppressWarnings("unchecked")
	public void onActive(ChannelHandlerContext ctx) {
		handler.apply((INBOUND) this, (OUTBOUND) this)
		       .subscribe(new CloseSubscriber(ctx));

		if (clientSink != null) {
			clientSink.success(new ChannelState(ctx.channel()));
		}
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

	/**
	 *
	 */
	public void onComplete() {
		if (isCancelled() || done) {
			return;
		}
		done = true;
		if (caughtUp && receiver != null) {
			receiver.onComplete();
		}
		if (clientSink != null) {
			clientSink.success();
		}
		drainReceiver();
	}

	/**
	 * @param err
	 */
	public void onError(Throwable err) {
		if (err == null) {
			err = new NullPointerException("error is null");
		}
		if (isCancelled() || done) {
			Operators.onErrorDropped(err);
			return;
		}
		done = true;
		if (caughtUp && receiver != null) {
			receiver.onError(err);
		}
		else {
			this.error = err;
			done = true;
			drainReceiver();
		}
	}

	/**
	 * @param ctx
	 */
	public void onInboundRequest(ChannelHandlerContext ctx) {
		if (requestedInbound != 0L) {
			ctx.read();
		}
		else {
			if (log.isDebugEnabled()) {
				log.debug("Pausing read due to lack of request");
			}
		}
		ctx.fireChannelReadComplete();
	}

	/**
	 * @param msg
	 */
	public void onNext(Object msg) {
		if (msg == null) {
			onError(new NullPointerException("msg is null"));
			return;
		}
		if (done) {
			Operators.onNextDropped(msg);
			return;
		}
		ReferenceCountUtil.retain(msg);
		if (caughtUp && receiver != null) {
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
				caughtUp = true;
			}
		}
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

	/**
	 * @param ctx
	 * @param writeStream
	 * @param flushMode
	 * @param promise
	 */
	public void onWrite(ChannelHandlerContext ctx,
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
			Operators.error(s, new IllegalStateException("This inbound is not " +
					"active " + "anymore"));
			return;
		}
		if (log.isDebugEnabled()) {
			log.debug("Subscribing inbound receiver [pending: " + "" + getPending() + ", done: " + done + "]");
		}
		if (receiver == null) {
			if (done) {
				if (error != null) {
					Operators.error(s, error);
					return;
				}
				else if (getPending() == 0) {
					Operators.complete(s);
					return;
				}
			}

			init(s);
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
	 * Return the available {@link MonoSink} for user-facing lifecycle handling
	 * @return the available {@link MonoSink} for user-facing lifecycle handling
	 */
	final protected MonoSink<NettyState> clientSink() {
		return clientSink;
	}

	/**
	 * Return the user-provided {@link reactor.ipc.netty.NettyConnector} handler
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

	final void cancelResource() {
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

	final void dereferenceReceiver() {
		receiver = null;
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

	final void init(Subscriber<? super Object> s) {
		receiver = s;
		CANCEL.lazySet(this, () -> {
			if (channel.eventLoop()
			           .inEventLoop()) {
				dereferenceReceiver();
			}
			else {
				channel.eventLoop()
				       .execute(this::dereferenceReceiver);
			}

			channel.config()
			       .setAutoRead(false);
		});
		wip = 0;
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