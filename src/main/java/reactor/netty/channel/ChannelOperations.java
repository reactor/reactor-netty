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

package reactor.netty.channel;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.netty.ByteBufFlux;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
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
		extends DefaultPromise<Void>
		implements NettyInbound, NettyOutbound, Connection, CoreSubscriber<Void>,
		           ChannelPromise {

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
		ch.pipeline()
		  .addLast(NettyPipeline.ReactiveBridge, new ChannelOperationsHandler(opsFactory, listener));
	}

	/**
	 * Return the current {@link Channel} bound {@link ChannelOperations} or null if none
	 *
	 * @param ch the current {@link Channel}
	 *
	 * @return the current {@link Channel} bound {@link ChannelOperations} or null if none
	 */
	@Nullable
	@SuppressWarnings("unchecked")
	public static ChannelOperations<?, ?> get(Channel ch) {
		return Connection.from(ch)
		                 .as(ChannelOperations.class);
	}

	final Connection         connection;
	final FluxReceive        inbound;
	final ConnectionObserver listener;

	@SuppressWarnings("unchecked")
	volatile Subscription outboundSubscription;

	protected ChannelOperations(ChannelOperations<INBOUND, OUTBOUND> replaced) {
		this(replaced.connection, replaced.listener);
	}

	/**
	 * Create a new {@link ChannelOperations} attached to the {@link Channel}. Attach the {@link NettyPipeline#ReactiveBridge} handle.
	 *
	 * @param connection the new {@link Connection} connection
	 * @param listener the events callback
	 */
	public ChannelOperations(Connection connection, ConnectionObserver listener) {
		super(connection.channel().eventLoop());
		this.connection = Objects.requireNonNull(connection, "connection");
		this.listener = Objects.requireNonNull(listener, "listener");
		this.inbound = new FluxReceive(this);
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
		withConnection.accept(this);
		return this;
	}

	@Override
	public void dispose() {
		if (inbound.isDisposed()) {
			return;
		}
		inbound.cancel();
		connection.dispose();
	}

	@Override
	public CoreSubscriber<Void> disposeSubscriber() {
		return this;
	}

	@Override
	public final boolean isDisposed() {
		return !channel().isActive() || get(channel()) != this;
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
	public NettyOutbound sendObject(Publisher<?> dataStream) {
		return then(FutureMono.disposableWriteAndFlush(connection.channel(), dataStream));
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
		return FutureMono.from((Future<Void>)this).or(connection.onDispose());
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
		return "ChannelOperations{"+connection.toString()+"}";
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
		terminate();
	}

	/**
	 * React on inbound/outbound completion (last packet)
	 */
	protected void onOutboundComplete() {
		if (log.isDebugEnabled()) {
			log.debug("[{}] {} User Handler requesting close connection", formatName(), channel());
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
				log.trace("{} Disposing ChannelOperation from a channel",
						channel(),
						new Exception("ChannelOperation terminal stack"));
			}

			Operators.terminate(OUTBOUND_CLOSE, this);
			listener.onStateChange(this, ConnectionObserver.State.DISCONNECTING);
			// Do not call directly inbound.onInboundComplete()
			// HttpClientOperations need to notify with error
			// when there is no response state
			onInboundComplete();
			if (isPersistent()) {
				channel().writeAndFlush(TERMINATED_OPS, this);
			}
			else {
				setSuccess(null);
			}
		}
	}

	/**
	 * Drop pending content and complete inbound
	 */
	protected final void discard(){
		inbound.discard();
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

	@Override
	public ChannelPromise setSuccess() {
		setSuccess(null);
		return this;
	}

	@Override
	public ChannelPromise setSuccess(Void result) {
		super.setSuccess(result);
		return this;
	}

	@Override
	public boolean trySuccess() {
		return trySuccess(null);
	}

	@Override
	public ChannelPromise unvoid() {
		return this;
	}

	@Override
	public boolean isVoid() {
		return true;
	}

	@Override
	public ChannelPromise setFailure(Throwable cause) {
		super.setFailure(cause);
		return this;
	}

	@Override
	public ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
		super.addListener(listener);
		return this;
	}

	@Override
	public ChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
		super.addListeners(listeners);
		return this;
	}

	@Override
	public ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
		super.removeListener(listener);
		return this;
	}

	@Override
	public ChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
		super.removeListeners(listeners);
		return this;
	}

	@Override
	public ChannelPromise sync() throws InterruptedException {
		super.sync();
		return this;
	}

	@Override
	public ChannelPromise await() throws InterruptedException {
		super.await();
		return this;
	}

	@Override
	public ChannelPromise awaitUninterruptibly() {
		super.awaitUninterruptibly();
		return this;
	}

	@Override
	public ChannelPromise syncUninterruptibly() {
		super.syncUninterruptibly();
		return this;
	}

	@Override
	public boolean isPersistent() {
		return connection.isPersistent();
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
