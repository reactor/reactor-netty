/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.channel;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCounted;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.publisher.Sinks;
import reactor.netty.ByteBufFlux;
import reactor.netty.ChannelOperationsId;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import static java.util.Objects.requireNonNull;
import static reactor.netty.ReactorNetty.format;

/**
 * {@link NettyInbound} and {@link NettyOutbound}  that apply to a {@link Connection}
 *
 * @author Stephane Maldini
 * @since 0.6
 */
public class ChannelOperations<INBOUND extends NettyInbound, OUTBOUND extends NettyOutbound>
		implements NettyInbound, NettyOutbound, Connection, CoreSubscriber<Void>, ChannelOperationsId {

	/**
	 * Add {@link NettyPipeline#ReactiveBridge} handler at the end of {@link Channel}
	 * pipeline. The bridge will buffer outgoing write and pass along incoming read to
	 * the current {@link ChannelOperations#get(Channel)}.
	 *
	 * @param ch the channel to bridge
	 * @param opsFactory the operations factory to invoke on channel active
	 * @param listener the listener to forward connection events to
	 */
	public static void addReactiveBridge(Channel ch, OnSetup opsFactory, ConnectionObserver listener) {
		requireNonNull(ch, "channel");
		requireNonNull(opsFactory, "opsFactory");
		requireNonNull(listener, "listener");
		ch.pipeline()
		  .addLast(NettyPipeline.ReactiveBridge, new ChannelOperationsHandler(opsFactory, listener));
	}

	/**
	 * Add {@link NettyPipeline#ChannelMetricsHandler} to the channel pipeline.
	 *
	 * @param ch the channel
	 * @param recorder the configured metrics recorder
	 * @param remoteAddress the remote address
	 * @param onServer true if {@link ChannelMetricsRecorder} is for the server, false if it is for the client
	 */
	public static void addMetricsHandler(Channel ch, ChannelMetricsRecorder recorder,
			@Nullable SocketAddress remoteAddress, boolean onServer) {
		requireNonNull(ch, "channel");
		requireNonNull(recorder, "recorder");
		SocketAddress remote = remoteAddress;
		if (remote == null) {
			remote = ch.remoteAddress();
		}
		ChannelHandler handler = recorder instanceof ContextAwareChannelMetricsRecorder ?
				new ContextAwareChannelMetricsHandler((ContextAwareChannelMetricsRecorder) recorder, remote, onServer) :
				new ChannelMetricsHandler(recorder, remote, onServer);
		ch.pipeline()
		  .addFirst(NettyPipeline.ChannelMetricsHandler, handler);
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
		return Connection.from(ch)
		                 .as(ChannelOperations.class);
	}

	final Connection          connection;
	final FluxReceive         inbound;
	final ConnectionObserver  listener;
	final Sinks.Empty<Void>   onTerminate;
	final String              shortId;

	volatile Subscription outboundSubscription;

	boolean localActive;
	String longId;

	protected ChannelOperations(ChannelOperations<INBOUND, OUTBOUND> replaced) {
		this.connection = replaced.connection;
		this.listener = replaced.listener;
		this.onTerminate = replaced.onTerminate;
		this.inbound = new FluxReceive(this);
		this.shortId = replaced.shortId;
		this.longId = replaced.longId;
		this.localActive = replaced.localActive;
	}

	/**
	 * Create a new {@link ChannelOperations} attached to the {@link Channel}. Attach the {@link NettyPipeline#ReactiveBridge} handle.
	 *
	 * @param connection the new {@link Connection} connection
	 * @param listener the events callback
	 */
	public ChannelOperations(Connection connection, ConnectionObserver listener) {
		this.connection = requireNonNull(connection, "connection");
		this.listener = requireNonNull(listener, "listener");
		this.onTerminate = Sinks.unsafe().empty();
		this.inbound = new FluxReceive(this);
		shortId = initShortId();
	}

	@Nullable
	@Override
	public <T extends Connection> T as(Class<T> clazz) {
		if (clazz == ChannelOperations.class) {
			@SuppressWarnings("unchecked")
			T thiz = (T) this;
			return thiz;
		}
		return Connection.super.as(clazz);
	}

	@Override
	public ByteBufAllocator alloc() {
		return connection.channel()
		                 .alloc();
	}

	@Override
	public NettyInbound inbound() {
		return this;
	}

	@Override
	public NettyOutbound outbound() {
		return this;
	}

	@Override
	public final Channel channel() {
		return connection.channel();
	}

	@Override
	public ChannelOperations<INBOUND, OUTBOUND> withConnection(Consumer<? super Connection> withConnection) {
		requireNonNull(withConnection, "withConnection");
		withConnection.accept(this);
		return this;
	}

	@Override
	public void dispose() {
		if (log.isTraceEnabled()) {
			log.trace(format(channel(), "Disposing ChannelOperation from a channel"),
					new Exception("ChannelOperation dispose stack"));
		}
		OUTBOUND_CLOSE.set(this, Operators.cancelledSubscription());
		if (!inbound.isDisposed()) {
			discard();
		}
		connection.dispose();
	}

	@Override
	public CoreSubscriber<Void> disposeSubscriber() {
		return this;
	}

	@Override
	public final boolean isDisposed() {
		return !channel().isActive() || isSubscriptionDisposed();
	}

	/**
	 * Return true if dispose subscription has been terminated
	 *
	 * @return true if dispose subscription has been terminated
	 */
	public final boolean isSubscriptionDisposed() {
		return OUTBOUND_CLOSE.get(this) == Operators.cancelledSubscription();
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
		if (isDisposed()) {
			return;
		}
		OUTBOUND_CLOSE.set(this, Operators.cancelledSubscription());
		onOutboundComplete();
	}

	@Override
	public final void onError(Throwable t) {
		if (isDisposed()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "An outbound error could not be processed"), t);
			}
			return;
		}
		OUTBOUND_CLOSE.set(this, Operators.cancelledSubscription());
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
	public NettyOutbound send(Publisher<? extends ByteBuf> dataStream, Predicate<ByteBuf> predicate) {
		requireNonNull(predicate, "predicate");
		if (!channel().isActive()) {
			return then(Mono.error(AbortedException.beforeSend()));
		}
		if (dataStream instanceof Mono) {
			return then(((Mono<?>) dataStream).flatMap(m -> FutureMono.from(channel().writeAndFlush(m)))
			                                 .doOnDiscard(ByteBuf.class, ByteBuf::release));
		}
		return then(MonoSendMany.byteBufSource(dataStream, channel(), predicate));
	}

	@Override
	public NettyOutbound sendObject(Publisher<?> dataStream, Predicate<Object> predicate) {
		requireNonNull(predicate, "predicate");
		if (!channel().isActive()) {
			return then(Mono.error(AbortedException.beforeSend()));
		}
		if (dataStream instanceof Mono) {
			return then(((Mono<?>) dataStream).flatMap(m -> FutureMono.from(channel().writeAndFlush(m)))
			                                 .doOnDiscard(ReferenceCounted.class, ReferenceCounted::release));
		}
		return then(MonoSendMany.objectSource(dataStream, channel(), predicate));
	}

	@Override
	public NettyOutbound sendObject(Object message) {
		if (!channel().isActive()) {
			ReactorNetty.safeRelease(message);
			return then(Mono.error(AbortedException.beforeSend()));
		}
		return then(FutureMono.deferFuture(() -> connection.channel()
		                                                   .writeAndFlush(message)),
				() -> ReactorNetty.safeRelease(message));
	}

	@Override
	public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
			BiFunction<? super Connection, ? super S, ?> mappedInput,
			Consumer<? super S> sourceCleanup) {
		requireNonNull(sourceInput, "sourceInput");
		requireNonNull(mappedInput, "mappedInput");
		requireNonNull(sourceCleanup, "sourceCleanup");

		return then(Mono.using(
				sourceInput,
				s -> FutureMono.from(connection.channel()
				                               .writeAndFlush(mappedInput.apply(this, s))),
				sourceCleanup)
		);
	}

	/**
	 * Return a Mono succeeding when a {@link ChannelOperations} has been terminated
	 *
	 * @return a Mono succeeding when a {@link ChannelOperations} has been terminated
	 */
	@Override
	public final Mono<Void> onTerminate() {
		if (!isPersistent()) {
			return connection.onDispose();
		}
		return onTerminate.asMono().or(connection.onDispose());
	}

	/**
	 * Return the available parent {@link ConnectionObserver} for user-facing lifecycle
	 * handling
	 *
	 * @return the available parent {@link ConnectionObserver}for user-facing lifecycle
	 * handling
	 */
	public final ConnectionObserver listener() {
		return listener;
	}

	@Override
	public String toString() {
		return "ChannelOperations{" + connection.toString() + "}";
	}

	/**
	 * Drop pending content and complete inbound.
	 * Always discard content regardless whether there is a receiver.
	 */
	public final void discard() {
		inbound.dispose();
	}

	/**
	 * Drop pending content and complete inbound.
	 * Discard content only in case there is no receiver.
	 */
	protected final void discardWhenNoReceiver() {
		if (inbound.receiver == null) {
			discard();
		}
	}

	/**
	 * Return true if inbound traffic is not expected anymore
	 *
	 * @return true if inbound traffic is not expected anymore
	 */
	public final boolean isInboundCancelled() {
		return inbound.isCancelled();
	}

	/**
	 * Return true if inbound traffic is not incoming or expected anymore
	 *
	 * @return true if inbound traffic is not incoming or expected anymore
	 */
	public final boolean isInboundDisposed() {
		return inbound.isDisposed();
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
	 * React on inbound cancel (receive() subscriber cancelled)
	 */
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			String info = isDisposed() ?
					(!channel().isActive() ? "channel disconnected" : "subscription disposed") :
					"operation cancelled";
			log.debug(format(channel(), "[{}] Channel inbound receiver cancelled ({})."), formatName(), info);
		}
	}


	/**
	 * React on inbound completion (last packet)
	 */
	protected void onInboundComplete() {
		inbound.onInboundComplete();
	}

	/**
	 * React after inbound completion (last packet)
	 */
	protected void afterInboundComplete() {
		// noop
	}

	/**
	 * React on inbound close (channel closed prematurely)
	 */
	protected void onInboundClose() {
		discardWhenNoReceiver();
		terminate();
	}

	/**
	 * React on inbound/outbound completion (last packet)
	 */
	protected void onOutboundComplete() {
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), "[{}] User Handler requesting close connection"), formatName());
		}
		markPersistent(false);
		terminate();
	}

	/**
	 * React on inbound/outbound error
	 *
	 * @param err the {@link Throwable} cause
	 */
	protected void onOutboundError(Throwable err) {
		markPersistent(false);
		terminate();
	}


	/**
	 * Final release/close (last packet)
	 */
	protected final void terminate() {
		if (rebind(connection)) {
			if (log.isTraceEnabled()) {
				log.trace(format(channel(), "Disposing ChannelOperation from a channel"),
						new Exception("ChannelOperation terminal stack"));
			}

			Operators.terminate(OUTBOUND_CLOSE, this);
			// Do not call directly inbound.onInboundComplete()
			// HttpClientOperations need to notify with error
			// when there is no response state
			onInboundComplete();
			afterInboundComplete();
			// EmitResult is ignored as it is guaranteed that this call happens in an event loop,
			// and it is guarded by rebind(connection), so tryEmitEmpty() should happen just once
			onTerminate.tryEmitEmpty();
			listener.onStateChange(this, ConnectionObserver.State.DISCONNECTING);
		}
	}

	/**
	 * React on inbound error
	 *
	 * @param err the {@link Throwable} cause
	 */
	protected final void onInboundError(Throwable err) {
		inbound.onInboundError(err);
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

	protected String initShortId() {
		return channel().id().asShortText();
	}

	/**
	 * Wrap an inbound error
	 *
	 * @param err the {@link Throwable} cause
	 */
	protected Throwable wrapInboundError(Throwable err) {
		if (err instanceof ClosedChannelException) {
			return new AbortedException(err);
		}
		else if (err instanceof OutOfMemoryError) {
			return ReactorNetty.wrapException(err);
		}
		else {
			return err;
		}
	}

	/**
	 * Transforms the object to a string for debug logs.
	 *
	 * @param o the object to be transformed
	 * @return the string to be logged
	 * @since 1.0.24
	 */
	protected String asDebugLogMessage(Object o) {
		return o.toString();
	}

	/**
	 * React on  Channel writability change.
	 *
	 * @since 1.0.37
	 */
	protected void onWritabilityChanged() {
	}

	@Override
	public boolean isPersistent() {
		return connection.isPersistent();
	}

	@Override
	public Context currentContext() {
		return listener.currentContext();
	}

	@Override
	public String asShortText() {
		return shortId;
	}

	@Override
	public String asLongText() {
		boolean active = channel().isActive();
		if (localActive == active && longId != null) {
			return longId;
		}

		SocketAddress remoteAddress = channel().remoteAddress();
		SocketAddress localAddress = channel().localAddress();
		String shortText = asShortText();
		if (remoteAddress != null) {
			String localAddressStr = String.valueOf(localAddress);
			String remoteAddressStr = String.valueOf(remoteAddress);
			StringBuilder buf =
					new StringBuilder(shortText.length() + 4 + localAddressStr.length() + 3 + 2 + remoteAddressStr.length())
					.append(shortText)
					.append(", L:")
					.append(localAddressStr)
					.append(active ? " - " : " ! ")
					.append("R:")
					.append(remoteAddressStr);
			longId = buf.toString();
		}
		else if (localAddress != null) {
			String localAddressStr = String.valueOf(localAddress);
			StringBuilder buf = new StringBuilder(shortText.length() + 4 + localAddressStr.length())
					.append(shortText)
					.append(", L:")
					.append(localAddressStr);
			longId = buf.toString();
		}
		else {
			longId = shortText;
		}

		localActive = active;
		return longId;
	}

	/**
	 * A {@link ChannelOperations} factory
	 */
	@FunctionalInterface
	public interface OnSetup {

		/**
		 * Return an empty, no-op factory
		 *
		 * @return an empty, no-op factory
		 */
		static OnSetup empty() {
			return EMPTY_SETUP;
		}

		/**
		 * Create a new {@link ChannelOperations} given a netty channel, a parent {@link
		 * ConnectionObserver} and an optional message (nullable).
		 *
		 * @param c a {@link Connection}
		 * @param listener a {@link ConnectionObserver}
		 * @param msg an optional message
		 *
		 * @return the new {@link ChannelOperations}
		 */
		@Nullable
		ChannelOperations<?, ?> create(Connection c, ConnectionObserver listener, @Nullable Object msg);

	}

	static final Logger log = Loggers.getLogger(ChannelOperations.class);

	static final Object TERMINATED_OPS = new Object();

	static final OnSetup EMPTY_SETUP = (c, l, msg) -> null;

	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<ChannelOperations, Subscription>
			OUTBOUND_CLOSE = AtomicReferenceFieldUpdater.newUpdater(ChannelOperations.class,
			Subscription.class,
			"outboundSubscription");

}
