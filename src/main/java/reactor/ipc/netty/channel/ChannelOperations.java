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
import reactor.core.Loopback;
import reactor.core.Producer;
import reactor.core.Trackable;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;
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
		implements NettyInbound, NettyOutbound, Producer, Trackable, Loopback,
		           NettyContext {

	/**
	 * The attribute in {@link Channel} to store the current {@link ChannelOperations}
	 */
	public static final AttributeKey<ChannelOperations> OPERATIONS_ATTRIBUTE_KEY =
			AttributeKey.newInstance("nettyOperations");

	/**
	 * Create a new {@link ChannelOperations} attached to the {@link Channel} attribute
	 * {@link #OPERATIONS_ATTRIBUTE_KEY}.
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
			ContextHandler<?> context,
			DirectProcessor<Void> processor) {
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
			channel.pipeline()
			       .addAfter(lastCodec.getKey(), name+"$extract", ByteBufHolderHandler.INSTANCE)
		           .addAfter(name+"$extract", name, handler);
			onClose(() -> {
				removeHandler(name);
				removeHandler(name+"$extract");
			});
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
		inbound.cancel();
	}

	@Override
	public final Object downstream() {
		return inbound;
	}

	@Override
	public final boolean isDisposed() {
		return !channel.isOpen() || channel.attr(OPERATIONS_ATTRIBUTE_KEY)
		              .get() != this;
	}

	@Override
	public boolean isTerminated() {
		return inbound.isTerminated();
	}

	@Override
	public final boolean isStarted() {
		return inbound.isStarted();
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
		if (channel.attr(OPERATIONS_ATTRIBUTE_KEY)
		           .compareAndSet(this, ops)) {
			if (channel.eventLoop()
			           .inEventLoop()) {
				Subscriber<?> s = inbound.receiver;
				inbound.unsubscribeReceiver();
				s.onComplete();
			}
			else {
				channel.eventLoop()
				       .execute(() -> {
					       Subscriber<?> s = inbound.receiver;
					       inbound.unsubscribeReceiver();
					       s.onComplete();
				       });
			}
			return true;
		}
		return false;
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
		if (discreteRemoteClose(err)) {
			return;
		}
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

	static final Logger     log  = Loggers.getLogger(ChannelOperations.class);
	static final BiFunction PING = (i, o) -> Flux.empty();

}