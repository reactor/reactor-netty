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
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
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
	public ChannelOperations<INBOUND, OUTBOUND> bind() {
		Connection.super.bind();
		listener.onStateChange(this, ConnectionObserver.State.CONFIGURED);
		return this;
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
		return inbound.isCancelled();
	}

	/**
	 * Return true if inbound traffic is not incoming or expected anymore
	 *
	 * @return true if inbound traffic is not incoming or expected anymore
	 */
	protected final boolean isInboundDisposed() {
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
		markPersistent(false);
		onHandlerTerminate();
	}


	/**
	 * Final release/close (last packet)
	 */
	protected final void onHandlerTerminate() {
		if (rebind(connection)) {
			if (log.isTraceEnabled()) {
				log.trace("{} Disposing ChannelOperation from a channel",
						channel(),
						new Exception("ChannelOperation terminal stack"));
			}

			Operators.terminate(OUTBOUND_CLOSE, this);
			listener.onStateChange(connection, ConnectionObserver.State.DISCONNECTING);
			// Do not call directly inbound.onInboundComplete()
			// HttpClientOperations need to notify with error
			// when there is no response state
			onInboundComplete();
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
		inbound.onInboundError(err);
	}

	/**
	 * Return the available parent {@link ConnectionObserver} for user-facing lifecycle
	 * handling
	 *
	 * @return the available parent {@link ConnectionObserver}for user-facing lifecycle
	 * handling
	 */
	protected final ConnectionObserver listener() {
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
		 * ConnectionObserver} and an optional message (nullable).
		 *
		 * @param c a {@link Connection}
		 * @param listener a {@link ConnectionObserver}
		 * @param msg an optional message
		 *
		 */
		void create(Connection c, ConnectionObserver listener, @Nullable  Object msg);

	}

	static final Logger log = Loggers.getLogger(ChannelOperations.class);

	@SuppressWarnings("rawtypes")
	static final AtomicReferenceFieldUpdater<ChannelOperations, Subscription>
			OUTBOUND_CLOSE = AtomicReferenceFieldUpdater.newUpdater(ChannelOperations.class,
			Subscription.class,
			"outboundSubscription");

}
