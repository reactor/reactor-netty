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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.Attribute;
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
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyHandlerNames;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.QueueSupplier;

/**
 * A bridge between an immutable {@link Channel} and {@link NettyInbound} /
 * {@link NettyOutbound} semantics exposed to user
 * {@link NettyConnector#newHandler(BiFunction)}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public class ChannelOperations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		implements Trackable, NettyInbound, NettyOutbound, Subscription, Producer,
		           Loopback, Publisher<Object>, ChannelFutureListener {

	/**
	 * The attribute in {@link Channel} to store the current {@link ChannelOperations}
	 */
	public static final AttributeKey<ChannelOperations> OPERATIONS_ATTRIBUTE_KEY =
			AttributeKey.newInstance("nettyOperations");

	/**
	 * Create a new {@link ChannelOperations} attached to the {@link Channel} attribute
	 * {@link #OPERATIONS_ATTRIBUTE_KEY}.
	 * Attach the {@link NettyHandlerNames#ReactiveBridge} handle.
	 *
	 * @param channel the new {@link Channel} connection
	 * @param handler the user-provided {@link BiFunction} i/o handler
	 * @param context the dispose callback
	 * @param <INBOUND> the {@link NettyInbound} type
	 * @param <OUTBOUND> the {@link NettyOutbound} type
	 *
	 * @return the created {@link ChannelOperations} bridge
	 */
	public static <INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound> ChannelOperations<INBOUND, OUTBOUND> bind(
			Channel channel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		@SuppressWarnings("unchecked") ChannelOperations<INBOUND, OUTBOUND> ops =
				new ChannelOperations<>(channel, handler, context);

		return ops;
	}

	@SuppressWarnings("unchecked")
	public static <INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound> BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> noopHandler() {
		return PING;
	}

	final BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>>
			handler;

	final Channel               channel;
	final Scheduler             ioScheduler;
	final Flux<?>               inbound;
	final DirectProcessor<Void> onInactive;
	final ContextHandler<?>     context;

	// guarded //
	Subscriber<? super Object> receiver;
	boolean                    receiverFastpath;
	long                       receiverDemand;
	Queue<Object>              inboundQueue;
	boolean                    inboundDone;
	Throwable                  inboundError;

	volatile Cancellation receiverCancel;
	volatile boolean      outboundFlushEach;

	protected ChannelOperations(Channel channel,
			ChannelOperations<INBOUND, OUTBOUND> replaced) {
		this(channel, replaced.handler, replaced.context);
		this.receiver = replaced.receiver;
		this.receiverFastpath = replaced.receiverFastpath;
		this.inboundQueue = replaced.inboundQueue;
		this.inboundDone = replaced.inboundDone;
		this.inboundError = replaced.inboundError;
		this.receiverDemand = replaced.receiverDemand;
		this.receiverCancel = replaced.receiverCancel;
		this.outboundFlushEach = replaced.outboundFlushEach;
	}

	protected ChannelOperations(Channel channel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		this.handler = Objects.requireNonNull(handler, "handler");
		this.channel = Objects.requireNonNull(channel, "channel");
		this.context = Objects.requireNonNull(context, "context");
		this.ioScheduler = Schedulers.fromExecutor(channel.eventLoop());
		this.inbound = Flux.from(this)
		                   .subscribeOn(ioScheduler);
		this.onInactive = DirectProcessor.create();
		channel.closeFuture()
		       .addListener(this);
	}

	@Override
	public <T> Attribute<T> attr(AttributeKey<T> key) {
		return channel.attr(key);
	}

	@Override
	final public void cancel() {
		if (cancelReceiver()) {
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
		return inbound;
	}

	@Override
	final public Object connectedOutput() {
		io.netty.channel.Channel parent = channel.parent();
		SocketAddress remote = channel.remoteAddress();
		SocketAddress local = channel.localAddress();
		String src = local != null ? local.toString() : "";
		String dst = remote != null ? remote.toString() : "";
		if (parent == null) {
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
		outboundFlushEach = true;
		return this;
	}

	@Override
	public long getCapacity() {
		return Long.MAX_VALUE;
	}

	@Override
	final public Throwable getError() {
		return inboundError;
	}

	@Override
	final public long getPending() {
		return inboundQueue != null ? inboundQueue.size() : 0;
	}

	@Override
	final public boolean isCancelled() {
		return receiverCancel == CANCELLED;
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
		return inboundDone;
	}

	@Override
	public final ChannelOperations<INBOUND, OUTBOUND> onClose(final Runnable onClose) {
		onInactive.subscribe(null, null, onClose);
		return this;
	}

	@Override
	public void operationComplete(ChannelFuture future) throws Exception {
		if (!future.isSuccess()) {
			onInactive.onError(future.cause());
		}
		else {
			onInactive.onComplete();
		}
	}

	@Override
	public final ChannelOperations<INBOUND, OUTBOUND> onReadIdle(long idleTimeout,
			final Runnable onReadIdle) {
		channel.pipeline()
		       .addBefore(NettyHandlerNames.ReactiveBridge,
				       NettyHandlerNames.OnChannelReadIdle,
				       new IdleStateHandler(idleTimeout, 0, 0, TimeUnit.MILLISECONDS) {
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
	public final ChannelOperations<INBOUND, OUTBOUND> onWriteIdle(long idleTimeout,
			final Runnable onWriteIdle) {
		channel.pipeline()
		       .addAfter(NettyHandlerNames.ReactiveBridge,
				       NettyHandlerNames.OnChannelWriteIdle,
				       new IdleStateHandler(0, idleTimeout, 0, TimeUnit.MILLISECONDS) {
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
		return inbound;
	}

	@Override
	public InetSocketAddress remoteAddress() {
		return (InetSocketAddress) channel.remoteAddress();
	}

	@Override
	public void request(long n) {
		if (Operators.validate(n)) {
			this.receiverDemand = Operators.addCap(receiverDemand, n);
			drainReceiver();
		}
	}

	@Override
	public long requestedFromDownstream() {
		return receiverDemand;
	}

	@Override
	public Mono<Void> sendObject(final Publisher<?> dataStream) {
		if (isDisposed()) {
			return Mono.error(new IllegalStateException("This outbound is not active " + "anymore"));
		}
		return new MonoSend(dataStream);
	}

	@Override
	public final void subscribe(Subscriber<? super Object> s) {
		if (receiver == null) {
			if (log.isDebugEnabled()) {
				log.debug("[{}] Subscribing inbound receiver [pending: " + "" + getPending() + ", inboundDone: {}]",
						formatName(),
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

	protected NettyContext context(){
		return context;
	}

	/**
	 * React on input initialization
	 *
	 * @param ctx the current {@link ChannelHandlerContext}
	 */
	@SuppressWarnings("unchecked")
	protected void onChannelActive(ChannelHandlerContext ctx) {
		applyHandler();
		context.fireContextActive(context());
	}

	/**
	 * React on inbound {@link Channel#read}
	 *
	 * @param ctx the context
	 * @param msg the read payload
	 */
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg == null) {
			onInboundError(new NullPointerException("msg is null"));
			return;
		}
		if (inboundDone) {
			if (log.isDebugEnabled()) {
				log.debug("[{}] Dropping frame {}", formatName(), msg);
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
			Queue<Object> q = inboundQueue;
			if (q == null) {
				q = QueueSupplier.unbounded()
				                 .get();
				inboundQueue = q;
			}
			q.offer(msg);
			if (drainReceiver()) {
				receiverFastpath = true;
			}
		}
	}

	/**
	 * React on inbound completion (last packet)
	 */
	protected void onInboundComplete() {
		if (isCancelled() || inboundDone) {
			return;
		}
		inboundDone = true;
		Subscriber<?> receiver = this.receiver;
		if (receiverFastpath && receiver != null) {
			receiver.onComplete();
			cancelReceiver();
			context.fireContextActive(context());
		}
		else {
			drainReceiver();
		}
	}

	/**
	 * React on inbound/outbound completion (last packet)
	 */
	protected void onOutboundComplete() {
		if (log.isDebugEnabled()) {
			log.debug("[{}] User Handler requesting close connection", formatName());
		}
		onChannelTerminate();
	}

	/**
	 * React on inbound/outbound error
	 *
	 * @param err the {@link Throwable} cause
	 */
	protected void onOutboundError(Throwable err) {
		discreteRemoteClose(err);
		onChannelTerminate();
	}

	/**
	 * React on channel release/close event
	 */
	protected void onChannelTerminate() {
		onChannelInactive();
	}

	protected ChannelFuture sendNext(Object data) {
		return channel.write(data);
	}

	/**
	 * Apply the user-provided {@link NettyConnector} handler
	 */
	@SuppressWarnings("unchecked")
	protected final void applyHandler() {
		if (log.isDebugEnabled()) {
			log.debug("[{}] handler is being applied: {}", formatName(), handler);
		}
		handler.apply((INBOUND) this, (OUTBOUND) this)
		       .subscribe(new OutboundCloseSubscriber(this));
	}

	/**
	 * Try filtering out remote close unless traced, return true if filtered
	 *
	 * @param err the error to check
	 *
	 * @return true if filtered
	 */
	protected final boolean discreteRemoteClose(Throwable err) {
		if (err instanceof IOException && (err.getMessage()
		                                      .contains("Broken pipe") || err.getMessage()
		                                                                     .contains(
				                                                                     "Connection reset by peer"))) {
			if (log.isDebugEnabled()) {
				log.debug("[{}] Connection closed remotely", formatName(), err);
			}
			return true;
		}

		log.error("[" + formatName() + "] Error processing connection. Requesting close the channel",
				err);
		return false;
	}

	/**
	 * Final release/close (last packet)
	 */
	protected final void onChannelInactive() {
		channel.closeFuture()
		       .removeListener(this);
		try {
			onInboundComplete(); // signal receiver
			onInactive.onComplete(); //signal senders and other interests
		}
		finally {
			context.terminateChannel(channel); // release / cleanup channel
		}
	}

	/**
	 * React on inbound error
	 *
	 * @param err the {@link Throwable} cause
	 */
	protected final void onInboundError(Throwable err) {
		if (err == null) {
			err = new NullPointerException("error is null");
		}
		if (discreteRemoteClose(err)){
			return;
		}
		if (isCancelled() || inboundDone) {
			Operators.onErrorDropped(err);
			return;
		}
		inboundDone = true;
		Subscriber<?> receiver = this.receiver;
		this.inboundError = err;
		if (receiverFastpath && receiver != null) {
			cancelReceiver();
			receiver.onError(err);
		}
		else {
			drainReceiver();
		}
		context.fireContextError(err);
	}

	/**
	 * Callback when a writer {@link Subscriber} has effectively terminated listening
	 * on further {@link Publisher} signals.
	 *
	 * @param last an optional callback for the last written payload
	 * @param promise the promise to fulfil for acknowledging termination success
	 * @param exception non null if the writer has terminated with a failure
	 */
	protected final void onTerminatedSend(ChannelFuture last,
			final ChannelPromise promise,
			final Throwable exception) {
		if (channel.isOpen() && last != null) {
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

			last.addListener(listener);
		}
		else {
			if (exception != null) {
				promise.setFailure(exception);
			}
			else {
				promise.setSuccess();
			}
		}
	}

	/**
	 * Attach a {@link Publisher} as a {@link Channel} writer. Forward write listener
	 * commit/failures events to the passed {@link Subscriber}
	 *
	 * @param encodedWriter the {@link Publisher} to write from
	 * @param postWriter the {@link Subscriber} to forward commit success/failure
	 */
	final protected void onOuboundSend(final Publisher<?> encodedWriter,
			final Subscriber<? super Void> postWriter) {

		if (log.isDebugEnabled()) {
			log.debug("[{}] New Outbound Sender: {}", formatName(), encodedWriter);
		}

		final ChannelFutureListener postWriteListener = future -> {
			postWriter.onSubscribe(Operators.emptySubscription());
			if (future.isSuccess()) {
				postWriter.onComplete();
			}
			else {
				postWriter.onError(future.cause());
			}
		};

		ChannelPromise p = channel.newPromise();
		p.addListener(postWriteListener);

		FlushMode mode;
		if (outboundFlushEach) {
			mode = FlushMode.AUTO_EACH;
		}
		else {
			mode = FlushMode.MANUAL_COMPLETE;
		}

		if (channel.eventLoop()
		           .inEventLoop()) {
			doOutboundSend(encodedWriter, mode, p);
		}
		else {
			channel.eventLoop()
			       .execute(() -> doOutboundSend(encodedWriter, mode, p));
		}
	}

	/**
	 * Hold receiving side and mark as done
	 * @return true if successfully marked receiving
	 */
	final protected boolean markReceiving(){
		if(inboundDone){
			Queue<?> q = inboundQueue;
			Subscriber<?> receiver = this.receiver;
			if(receiver == null && (q == null || q.isEmpty())){
				cancel();
			}
			return true;
		}
		inboundDone = true;
		return false;
	}

	/**
	 * Return the available parent {@link ContextHandler} for user-facing lifecycle
	 * handling
	 *
	 * @return the available parent {@link ContextHandler}for user-facing lifecycle
	 * handling
	 */
	final protected ContextHandler<?> parentContext() {
		return context;
	}

	/**
	 * Return formatted name of this operation
	 * @return formatted name of this operation
	 */
	protected final String formatName() {
		return getClass().getSimpleName()
		                 .replace("Operations", "");
	}

	final boolean cancelReceiver() {
		Cancellation c = receiverCancel;
		if (c != CANCELLED) {
			c = CANCEL.getAndSet(this, CANCELLED);
			if (c != CANCELLED) {
				if (c != null) {
					c.dispose();
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * React on new outbound {@link Publisher} writer
	 *
	 * @param writeStream the {@link Publisher} to send
	 * @param flushMode the flush strategy
	 * @param promise an actual promise fulfilled on writer success/error
	 */
	final void doOutboundSend(Publisher<?> writeStream,
			FlushMode flushMode,
			ChannelPromise promise) {
		if (flushMode == FlushMode.MANUAL_COMPLETE) {
			writeStream.subscribe(new OutboundFlushLastSubscriber(this, promise));
		}
		else {
			writeStream.subscribe(new OutboundFlushEachSubscriber(this, promise));
		}
	}

	final boolean drainReceiver() {

		final Queue<Object> q = inboundQueue;
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
		});
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
		context.fireContextActive(context());
	}

	final void unsubscribeReceiver() {
		receiverDemand = 0L;
		receiver = null;
	}
	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<ChannelOperations, Cancellation> CANCEL =
			AtomicReferenceFieldUpdater.newUpdater(ChannelOperations.class,
					Cancellation.class,
					"receiverCancel");
	static final Cancellation CANCELLED = () -> {
	};
	static final Logger       log       = Loggers.getLogger(ChannelOperations.class);
	static final BiFunction                                                   PING   =
			(i, o) -> Flux.empty();

	final class MonoSend extends Mono<Void> implements Receiver, Loopback {

		final Publisher<?> dataStream;

		public MonoSend(Publisher<?> dataStream) {
			this.dataStream = dataStream;
		}

		@Override
		public Object connectedInput() {
			return ChannelOperations.this;
		}

		@Override
		public Object connectedOutput() {
			return ChannelOperations.this;
		}

		@Override
		public void subscribe(Subscriber<? super Void> s) {
			try {
				onOuboundSend(dataStream, s);
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