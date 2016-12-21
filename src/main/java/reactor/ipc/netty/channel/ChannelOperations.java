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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Loopback;
import reactor.core.Producer;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.NettyConnector;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.ipc.netty.channel.ContextHandler.CLOSE_CHANNEL;

/**
 * A bridge between an immutable {@link Channel} and {@link NettyInbound} /
 * {@link NettyOutbound} semantics exposed to user
 * {@link NettyConnector#newHandler(BiFunction)}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public class ChannelOperations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		implements NettyInbound, NettyOutbound, Producer, Loopback, NettyContext,
		           Subscriber<Void> {

	/**
	 * Create a new {@link ChannelOperations} attached to the {@link Channel} attribute
	 * {@link #OPERATIONS_KEY}.
	 * Attach the {@link NettyPipeline#ReactiveBridge} handle.
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

	/**
	 * Return a Noop {@link BiFunction} handler
	 *
	 * @param <INBOUND> reified inbound type
	 * @param <OUTBOUND> reified outbound type
	 *
	 * @return a Noop {@link BiFunction} handler
	 */
	@SuppressWarnings("unchecked")
	public static <INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound> BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> noopHandler() {
		return PING;
	}
	final BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>>
			                    handler;
	final Channel               channel;
	final FluxReceive           inbound;
	final DirectProcessor<Void> onInactive;
	final ContextHandler<?>     context;
	@SuppressWarnings("unchecked")
	volatile Subscription outboundSubscription;
	protected ChannelOperations(Channel channel,
			ChannelOperations<INBOUND, OUTBOUND> replaced) {
		this(channel, replaced.handler, replaced.context, replaced.onInactive);
	}

	protected ChannelOperations(Channel channel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			ContextHandler<?> context) {
		this(channel, handler, context, DirectProcessor.create());
	}

	protected ChannelOperations(Channel channel,
			BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler,
			ContextHandler<?> context, DirectProcessor<Void> processor) {
		this.handler = Objects.requireNonNull(handler, "handler");
		this.channel = Objects.requireNonNull(channel, "channel");
		this.context = Objects.requireNonNull(context, "context");
		this.inbound = new FluxReceive(this);
		this.onInactive = processor;
		context.onCloseOrRelease(channel)
		       .subscribe(onInactive);
	}

	@Override
	public ChannelOperations<INBOUND, OUTBOUND> addHandler(String name,
			ChannelHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		channel.pipeline()
		       .addBefore(NettyPipeline.ReactiveBridge, name, handler);

		onClose(() -> removeHandler(name));

		if (log.isDebugEnabled()) {
			log.debug("Added handler [{}]", name, channel.pipeline());
		}
		return this;
	}

	@Override
	public ChannelOperations<INBOUND, OUTBOUND> addDecoder(String name,
			ChannelHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");

		Map.Entry<String, ChannelHandler> lastCodec = null;
		ChannelHandler next;
		for (Map.Entry<String, ChannelHandler> c : channel.pipeline()) {
			next = c.getValue();
			if (next instanceof MessageToMessageDecoder ||
					next instanceof ByteToMessageDecoder ||
					next instanceof CombinedChannelDuplexHandler) {
				lastCodec = c;
			}
		}

		if (lastCodec == null) {
			channel.pipeline()
			       .addBefore(NettyPipeline.ReactiveBridge, name, handler);
			onClose(() -> removeHandler(name));
		}
		else {
			if(handler instanceof ByteToMessageDecoder) {
				channel.pipeline()
				       .addAfter(lastCodec.getKey(),
						       name + "$extract",
						       ByteBufHolderHandler.INSTANCE);
				channel.pipeline()
				       .addAfter(name + "$extract", name, handler);
			}
			onClose(() -> {
				removeHandler(name);
				if(handler instanceof ByteToMessageDecoder) {
					removeHandler(name + "$extract");
				}
			});
		}
		if (log.isDebugEnabled()) {
			log.debug("Added decoder [{}]{}",
					name,
					lastCodec != null ? " after ["+lastCodec.getKey()+"]": "",
					channel.pipeline());
		}
		return this;
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
	public final Channel channel() {
		return channel;
	}

	@Override
	public final Object connectedInput() {
		return inbound;
	}

	@Override
	public final Object connectedOutput() {
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
	public final NettyContext context() {
		return this;
	}

	@Override
	public void dispose() {
		onHandlerTerminate();
	}

	@Override
	public final Object downstream() {
		return inbound;
	}

	@Override
	public final boolean isDisposed() {
		return channel.attr(OPERATIONS_KEY)
		              .get() != this;
	}

	@Override
	public final Mono<Void> onClose() {
		return MonoSource.wrap(onInactive);
	}

	@Override
	public NettyContext onClose(final Runnable onClose) {
		onInactive.subscribe(null, e -> onClose.run(), onClose);
		return this;
	}

	@Override
	public final void onComplete() {
		Subscription s =
				OUTBOUND_CLOSE.getAndSet(this, Operators.cancelledSubscription());
		if (s == Operators.cancelledSubscription() || isDisposed()) {
			return;
		}
		onOutboundComplete();
	}

	@Override
	public final void onError(Throwable t) {
		Subscription s =
				OUTBOUND_CLOSE.getAndSet(this, Operators.cancelledSubscription());
		if (s == Operators.cancelledSubscription() || isDisposed()) {
			Operators.onErrorDropped(t);
			return;
		}
		onOutboundError(t);
	}

	@Override
	public final void onNext(Void aVoid) {
	}

	@Override
	public final void onSubscribe(Subscription s) {
		if (Operators.setOnce(OUTBOUND_CLOSE, this, s)) {
			s.request(Long.MAX_VALUE);
		}
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
	public String toString() {
		return channel.toString();
	}

	/**
	 * Return true if inbound traffic is not expected anymore
	 *
	 * @return true if inbound traffic is not expected anymore
	 */
	protected final boolean isInboundDone() {
		return inbound.isTerminated() || !channel.isOpen();
	}

	/**
	 * Return true if inbound traffic is not expected anymore
	 *
	 * @return true if inbound traffic is not expected anymore
	 */
	protected final boolean isOutboundDone() {
		return outboundSubscription == Operators.cancelledSubscription() || !channel.isOpen();
	}

	/**
	 * Safely remove handler from pipeline
	 *
	 * @param name handler name
	 */
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

	/**
	 * Connector handler provided by user
	 *
	 * @return Connector handler provided by user
	 */
	protected final BiFunction<? super INBOUND, ? super OUTBOUND, ? extends Publisher<Void>> handler() {
		return handler;
	}

	/**
	 * React on input initialization
	 *
	 */
	@SuppressWarnings("unchecked")
	protected void onHandlerStart() {
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
		inbound.onInboundNext(msg);
	}

	/**
	 * Replace and complete previous operation inbound
	 *
	 * @param ops a new operations
	 *
	 * @return true if replaced
	 */
	protected final boolean replace(ChannelOperations<?, ?> ops) {
		return channel.attr(OPERATIONS_KEY)
		              .compareAndSet(this, ops);
	}

	/**
	 * React on inbound cancel (receive() subscriber cancelled)
	 */
	protected void onInboundCancel() {
	}


	/**
	 * React on inbound completion (last packet)
	 */
	protected void onInboundComplete() {
		if (inbound.onInboundComplete()) {
			context.fireContextActive(this);
		}
	}

	/**
	 * React on inbound/outbound completion (last packet)
	 */
	protected void onOutboundComplete() {
		if (log.isDebugEnabled()) {
			log.debug("[{}] User Handler requesting close connection", formatName());
		}
		channel.attr(CLOSE_CHANNEL).set(true);
		onHandlerTerminate();
	}

	/**
	 * React on inbound/outbound error
	 *
	 * @param err the {@link Throwable} cause
	 */
	protected void onOutboundError(Throwable err) {
		discreteRemoteClose(err);
		channel.attr(CLOSE_CHANNEL).set(true);
		onHandlerTerminate();
	}

	/**
	 * Apply the user-provided {@link NettyConnector} handler
	 */
	@SuppressWarnings("unchecked")
	protected final void applyHandler() {
//		channel.pipeline()
//		       .fireUserEventTriggered(NettyPipeline.handlerStartedEvent());
		if (log.isDebugEnabled()) {
			log.debug("[{}] handler is being applied: {}", formatName(), handler);
		}
		handler.apply((INBOUND) this, (OUTBOUND) this)
		       .subscribe(this);
	}

	/**
	 * Try filtering out remote close unless traced, return true if filtered
	 *
	 * @param err the error to check
	 *
	 * @return true if filtered
	 */
	protected final boolean discreteRemoteClose(Throwable err) {
		if (err instanceof IOException && (err.getMessage() == null || err.getMessage()
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
	protected final void onHandlerTerminate() {
		if (replace(null)) {
			try {
				Operators.terminate(OUTBOUND_CLOSE, this);
				onInactive.onComplete(); //signal senders and other interests
				onInboundComplete(); // signal receiver

			}
			finally {
				channel.pipeline()
				       .fireUserEventTriggered(NettyPipeline.handlerTerminatedEvent());
				context.terminateChannel(channel); // release / cleanup channel
			}
		}
	}

	/**
	 * React on inbound error
	 *
	 * @param err the {@link Throwable} cause
	 */
	protected final void onInboundError(Throwable err) {
		discreteRemoteClose(err);
		if (inbound.onInboundError(err)) {
			context.fireContextError(err);
		}
	}

	/**
	 * Hold receiving side and mark as done
	 *
	 * @return true if successfully marked receiving
	 */
	protected final boolean markInboundDone() {
		return inbound.markInboundDone();
	}

	/**
	 * Return the available parent {@link ContextHandler} for user-facing lifecycle
	 * handling
	 *
	 * @return the available parent {@link ContextHandler}for user-facing lifecycle
	 * handling
	 */
	protected final ContextHandler<?> parentContext() {
		return context;
	}

	/**
	 * Return formatted name of this operation
	 *
	 * @return formatted name of this operation
	 */
	protected final String formatName() {
		return getClass().getSimpleName()
		                 .replace("Operations", "");
	}

	/**
	 * Ignore keep-alive or connection-pooling
	 */
	protected final void ignoreChannelPersistence() {
		channel.attr(CLOSE_CHANNEL)
		       .set(true);
	}

	/**
	 * A {@link ChannelOperations} factory
	 */
	@FunctionalInterface
	public interface OnNew<CHANNEL extends Channel> {

		/**
		 * Create a new {@link ChannelOperations} given a netty channel, a parent
		 * {@link ContextHandler} and an optional message (nullable).
		 *
		 * @param c a {@link Channel}
		 * @param contextHandler a {@link ContextHandler}
		 * @param msg an optional message
		 *
		 * @return a new {@link ChannelOperations}
		 */
		ChannelOperations<?, ?> create(CHANNEL c, ContextHandler<?> contextHandler, Object msg);
	}
	/**
	 * The attribute in {@link Channel} to store the current {@link ChannelOperations}
	 */
	protected static final AttributeKey<ChannelOperations> OPERATIONS_KEY = AttributeKey.newInstance("nettyOperations");
	static final Logger     log  = Loggers.getLogger(ChannelOperations.class);
	static final BiFunction PING = (i, o) -> Flux.empty();

	static final AtomicReferenceFieldUpdater<ChannelOperations, Subscription>
			OUTBOUND_CLOSE = AtomicReferenceFieldUpdater.newUpdater(ChannelOperations.class,
			Subscription.class,
			"outboundSubscription");

}