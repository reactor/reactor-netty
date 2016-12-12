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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Cancellation;
import reactor.core.Loopback;
import reactor.core.Producer;
import reactor.core.Trackable;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;
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
		           Loopback, NettyContext, Publisher<Object> {

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
		this(channel, replaced.handler, replaced.context, replaced.onInactive);
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
		this(channel, handler, context, DirectProcessor.create());
	}

	protected ChannelOperations(Channel channel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			ContextHandler<?> context,
			DirectProcessor<Void> processor) {
		this.handler = Objects.requireNonNull(handler, "handler");
		this.channel = Objects.requireNonNull(channel, "channel");
		this.context = Objects.requireNonNull(context, "context");
		this.ioScheduler = Schedulers.fromExecutor(channel.eventLoop());
		this.inbound = Flux.from(this)
		                   .subscribeOn(ioScheduler);
		this.onInactive = processor;
		context.onCloseOrRelease(channel)
		       .subscribe(onInactive);
	}

	@Override
	public ChannelOperations<INBOUND, OUTBOUND> addHandler(ChannelHandler handler) {
		return addHandler(Objects.toString(handler), handler);
	}

	@Override
	public ChannelOperations<INBOUND, OUTBOUND> addHandler(String name, ChannelHandler
			handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		channel.pipeline()
		       .addBefore(NettyHandlerNames.ReactiveBridge,
				       name,
				       handler);

		onClose(() -> removeHandler(name));
		return this;
	}

	protected final void removeHandler(String name) {
		if (channel.isOpen() && channel.pipeline()
		                               .context(name) != null) {
			channel.pipeline()
			       .remove(name);
			if (log.isDebugEnabled()) {
				log.debug("[{}] Removed handler: {}, pipeline: {}",
						formatName(),
						name,
						channel.pipeline());
			}
		}
		else if (log.isDebugEnabled()) {
			log.debug("[{}] Non Removed handler: {}, context: {}, pipeline: {}",
					formatName(),
					name,
					channel.pipeline()
					       .context(name),
					channel.pipeline());
		}
	}

	@Override
	public InetSocketAddress address() {
		Channel c = channel();
		if (c instanceof SocketChannel) {
			return ((SocketChannel) c).remoteAddress();
		}
		if (c instanceof DatagramChannel) {
			return ((DatagramChannel) c).localAddress();
		}
		throw new IllegalStateException("Does not have an InetSocketAddress");
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
	public void dispose() {
		cancel();
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
	public final boolean isDisposed() {
		return channel.attr(OPERATIONS_ATTRIBUTE_KEY)
		              .get() != this;
	}

	@Override
	public final Mono<Void> onClose() {
		return MonoSource.wrap(onInactive);
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
	public NettyContext onClose(final Runnable onClose) {
		onInactive.subscribe(null, e -> onClose.run(), onClose);
		return this;
	}

	@Override
	public Flux<?> receiveObject() {
		return inbound;
	}

	@Override
	public final InetSocketAddress remoteAddress() {
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

	@Override
	public final NettyContext context() {
		return this;
	}

	/**
	 * React on input initialization
	 *
	 * @param ctx the current {@link ChannelHandlerContext}
	 */
	@SuppressWarnings("unchecked")
	protected void onChannelActive(ChannelHandlerContext ctx) {
		applyHandler();
		context.fireContextActive(this);
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
			context.fireContextActive(this);
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
		       .subscribe(closeSubscriber(this));
	}

	/**
	 * Return a new {@code OutboundCloseSubscriber} given an operations instance
	 *
	 * @param ops the operation to callback on complete/error.
	 *
	 * @return a new {@code OutboundCloseSubscriber} given an operations instance
	 */
	protected final Subscriber<? super Void> closeSubscriber(ChannelOperations<INBOUND, OUTBOUND> ops) {
		return new OutboundCloseSubscriber(ops);
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
		try {
			onInactive.onComplete(); //signal senders and other interests
			onInboundComplete(); // signal receiver
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
		context.fireContextActive(this);
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


}