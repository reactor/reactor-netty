/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

/**
 * {@link NettyInbound} and {@link NettyOutbound}  that apply to a {@link Connection}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public class ChannelOperations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		implements NettyInbound, NettyOutbound, Connection, CoreSubscriber<Void> {

	/**
	 * Create a new {@link ChannelOperations} attached to the {@link Channel} attribute
	 * {@link #OPERATIONS_KEY}. Attach the {@link NettyPipeline#ReactiveBridge} handle.
	 *
	 * @param connection the new {@link Connection} connection
	 * @param context the events callback
	 * @param <INBOUND> the {@link NettyInbound} type
	 * @param <OUTBOUND> the {@link NettyOutbound} type
	 *
	 * @return the created {@link ChannelOperations} bridge
	 */
	public static <INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound> ChannelOperations<INBOUND, OUTBOUND> bind(
			Connection connection,
			ConnectionEvents context) {
		@SuppressWarnings("unchecked") ChannelOperations<INBOUND, OUTBOUND> ops =
				new ChannelOperations<>(connection, context);

		context.onStart(ops);

		return ops;
	}

	/**
	 * Return the current {@link Channel} bound {@link ChannelOperations} or null if none
	 *
	 * @param ch the current {@link Channel}
	 *
	 * @return the current {@link Channel} bound {@link ChannelOperations} or null if none
	 */
	@Nullable
	public static ChannelOperations<?, ?> get(Channel ch) {
		return ch.attr(OPERATIONS_KEY)
		         .get();
	}

	final Connection       connection;
	final FluxReceive      inbound;
	final ConnectionEvents listener;

	@SuppressWarnings("unchecked")
	volatile Subscription outboundSubscription;

	protected ChannelOperations(ChannelOperations<INBOUND, OUTBOUND> replaced) {
		this(replaced.connection, replaced.listener);
	}

	protected ChannelOperations(Connection connection, ConnectionEvents listener) {
		this.connection = Objects.requireNonNull(connection, "connection");
		this.listener = Objects.requireNonNull(listener, "listener");
		this.inbound = new FluxReceive(this);

		//FIXME evaluate
//		Subscription[] _s = new Subscription[1];
//		Mono.fromDirect(context.onCloseOrRelease(channel))
//		    .doOnSubscribe(s -> _s[0] = s)
//		    .subscribe(onInactive);
//
//		if(_s[0] != null) { //remove closeFuture listener ref by onCloseOrRelease
//			// subscription when onInactive is called for any reason from
//			// onHandlerTerminate
//			onInactive.subscribe(null, null, _s[0]::cancel);
//		}

		connection.channel()
		          .attr(OPERATIONS_KEY)
		          .set(this);
	}

	@Override
	public ByteBufAllocator alloc() {
		return connection.channel()
		                 .alloc();
	}

	@Override
	public final Channel channel() {
		return connection.channel();
	}

	@Override
	public ChannelOperations<INBOUND, OUTBOUND> withConnection(Consumer<? super Connection> withConnection) {
		withConnection.accept(this);
		return this;
	}

	@Override
	public void dispose() {
		inbound.cancel();
		connection.dispose();
	}

	@Override
	public CoreSubscriber<Void> disposeSubscriber() {
		return this;
	}

	@Override
	public final boolean isDisposed() {
		return get(channel()) != this;
	}

	@Override
	public final Mono<Void> onDispose() {
		return connection.onDispose();
	}

	@Override
	public Connection onDispose(final Disposable onDispose) {
		connection.onDispose(onDispose);
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
			if (log.isDebugEnabled()) {
				log.error("An outbound error could not be processed", t);
			}
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
	public ByteBufFlux receive() {
		return ByteBufFlux.fromInbound(receiveObject(), connection.channel()
		                                                          .alloc());
	}

	@Override
	public NettyOutbound sendObject(Object message) {
		return then(FutureMono.deferFuture(() -> connection.channel()
		                                                   .writeAndFlush(message)));
	}

	@Override
	public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
			BiFunction<? super Connection, ? super S, ?> mappedInput,
			Consumer<? super S> sourceCleanup) {
		Objects.requireNonNull(sourceInput, "sourceInput");
		Objects.requireNonNull(mappedInput, "mappedInput");
		Objects.requireNonNull(sourceCleanup, "sourceCleanup");

		return then(Mono.using(
				sourceInput,
				s -> FutureMono.from(connection.channel()
				                               .writeAndFlush(mappedInput.apply(this, s))),
				sourceCleanup)
		);
	}

	@Override
	public String toString() {
		return connection.channel()
		                 .toString();
	}

	/**
	 * Return true if inbound traffic is not expected anymore
	 *
	 * @return true if inbound traffic is not expected anymore
	 */
	protected final boolean isInboundCancelled() {
		return inbound.isCancelled() || !connection.channel()
		                                           .isActive();
	}

	/**
	 * Return true if inbound traffic is not expected anymore
	 *
	 * @return true if inbound traffic is not expected anymore
	 */
	protected final boolean isOutboundDone() {
		return outboundSubscription == Operators.cancelledSubscription() || !connection.channel()
		                                                                               .isActive();
	}

	/**
	 * React on inbound {@link Channel#read}
	 *
	 * @param ctx the context
	 * @param msg the read payload
	 */
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		inbound.onInboundNext(msg);
	}

	/**
	 * Replace and complete previous operation inbound
	 *
	 * @param ops a new operations
	 *
	 * @return true if replaced
	 */
	protected final boolean replace(@Nullable ChannelOperations<?, ?> ops) {
		return connection.channel()
		                 .attr(OPERATIONS_KEY)
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
		inbound.onInboundComplete();
	}

	/**
	 * React on inbound close (channel closed prematurely)
	 */
	protected void onInboundClose() {
		onHandlerTerminate();
	}

	/**
	 * React on inbound/outbound completion (last packet)
	 */
	protected void onOutboundComplete() {
		if (log.isDebugEnabled()) {
			log.debug("[{}] {} User Handler requesting close connection", formatName(), channel());
		}
		markPersistent(false);
		onHandlerTerminate();
	}

	/**
	 * React on inbound/outbound error
	 *
	 * @param err the {@link Throwable} cause
	 */
	protected void onOutboundError(Throwable err) {
		discreteRemoteClose(err);
		markPersistent(false);
		onHandlerTerminate();
	}

	/**
	 * Try filtering out remote close unless traced, return true if filtered
	 *
	 * @param err the error to check
	 *
	 * @return true if filtered
	 */
	protected final boolean discreteRemoteClose(Throwable err) {
		if (AbortedException.isConnectionReset(err)) {
			if (log.isDebugEnabled()) {
				log.debug("{} [{}] Connection closed remotely",
						connection.channel()
						          .toString(),
						formatName(),
						err);
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
			if (log.isTraceEnabled()) {
				log.trace("{} Disposing ChannelOperation from a channel",
						channel(),
						new Exception("ChannelOperation terminal stack"));
			}
			try {
				Operators.terminate(OUTBOUND_CLOSE, this);
				listener.onDispose(connection.channel());
				// Do not call directly inbound.onInboundComplete()
				// HttpClientOperations need to notify with error
				// when there is no response state
				onInboundComplete();
			}
			finally {
				connection.channel()
				          .pipeline()
				          .fireUserEventTriggered(NettyPipeline.handlerTerminatedEvent());
			}
		}
	}

	/**
	 * Drop pending content and complete inbound
	 */
	protected final void discard(){
		if(log.isDebugEnabled()){
			log.debug("{} Discarding inbound content", connection.channel());
		}
		inbound.discard();
	}

	/**
	 * React on inbound error
	 *
	 * @param err the {@link Throwable} cause
	 */
	protected final void onInboundError(Throwable err) {
		discreteRemoteClose(err);
		inbound.onInboundError(err);
	}

	/**
	 * Return the available parent {@link ConnectionEvents} for user-facing lifecycle
	 * handling
	 *
	 * @return the available parent {@link ConnectionEvents}for user-facing lifecycle
	 * handling
	 */
	protected final ConnectionEvents listener() {
		return listener;
	}

	/**
	 * Return the delegate IO  {@link Connection} for  low-level IO access
	 *
	 * @return the delegate IO  {@link Connection} for  low-level IO access
	 */
	protected final Connection connection() {
		return connection;
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

	@Override
	public Context currentContext() {
		return listener.currentContext();
	}

	/**
	 * A {@link ChannelOperations} factory
	 */
	@FunctionalInterface
	public interface OnSetup {

		/**
		 * Create a new {@link ChannelOperations} given a netty channel, a parent {@link
		 * ConnectionEvents} and an optional message (nullable).
		 *
		 * @param c a {@link Connection}
		 * @param listener a {@link ConnectionEvents}
		 * @param msg an optional message
		 *
		 * @return a new {@link ChannelOperations}
		 */
		ChannelOperations<?, ?> create(Connection c, ConnectionEvents listener,
				@Nullable  Object msg);

		/**
		 * True if {@link ChannelOperations} should be created by {@link
		 * ChannelOperationsHandler} on channelActive event
		 *
		 * @return true if {@link ChannelOperations} should be created by {@link
		 * ChannelOperationsHandler} on channelActive event
		 */
		default boolean createOnConnected() {
			return true;
		}
	}

	/**
	 * The attribute in {@link Channel} to store the current {@link ChannelOperations}
	 */
	protected static final AttributeKey<ChannelOperations> OPERATIONS_KEY =
			AttributeKey.newInstance("nettyOperations");

	static final Logger log = Loggers.getLogger(ChannelOperations.class);

	static final AtomicReferenceFieldUpdater<ChannelOperations, Subscription>
			OUTBOUND_CLOSE = AtomicReferenceFieldUpdater.newUpdater(ChannelOperations.class,
			Subscription.class,
			"outboundSubscription");

}
