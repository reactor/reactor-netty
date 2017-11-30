/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.pool.ChannelPool;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import reactor.core.Disposable;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionEvents;
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.NettyPipeline;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

/**
 *
 * @author Stephane Maldini
 */
final class DisposableAcquire implements Connection, ConnectionEvents,
                                         Consumer<Future<?>>,
                                         Runnable {

	final ChannelPool                    pool;
	final ChannelOperations.OnSetup      opsFactory;
	final BiConsumer<Connection, Object> onProtocolEvents;
	final MonoSink<Connection>           sink;

	Channel channel;

	volatile Acquisition currentOwner;

	static final AtomicReferenceFieldUpdater<DisposableAcquire, Acquisition> OWNER =
			AtomicReferenceFieldUpdater.newUpdater(DisposableAcquire.class,
					Acquisition.class,
					"currentOwner");

	DisposableAcquire(MonoSink<Connection> sink,
			ChannelOperations.OnSetup opsFactory,
			ChannelPool pool,
			@Nullable BiConsumer<Connection, Object> protocolEvents) {
		this.sink = Objects.requireNonNull(sink, "sink");
		this.opsFactory = Objects.requireNonNull(opsFactory, "opsFactory");
		this.pool = Objects.requireNonNull(pool, "pool");
		this.onProtocolEvents = protocolEvents;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void accept(Future<?> future) {
		Objects.requireNonNull(future, "future");

		if (currentOwner == DISPOSED) {
			if (log.isDebugEnabled()) {
				log.debug("Cancelled existing channel from pool: {}", pool.toString());
			}
			sink.success();
			return;
		}

		Acquisition newOwner = new Acquisition((Future<Channel>) future, this);

		if (log.isDebugEnabled()) {
			log.debug("Acquiring existing channel from pool: {} {}",
					future,
					pool.toString());
		}
		((Future<Channel>) future).addListener(newOwner);

		sink.onCancel(newOwner);
	}

	@Override
	public Channel channel() {
		return channel;
	}

	@Override
	public Context currentContext() {
		return sink.currentContext();
	}

	@Override
	public void onDispose(Channel channel) {
		log.debug("onConnectionDispose({})", channel);
		if (channel.isActive()) {
			channel.close();
		}
	}

	@Override
	public Mono<Void> onDispose() {
		Acquisition current = this.currentOwner;
		return Mono.first(Mono.fromDirect(current.onReleaseEmitter),
				FutureMono.from(channel.closeFuture()));
	}

	@Override
	public void onProtocolEvent(Connection connection, Object evt) {
		if (onProtocolEvents != null) {
			onProtocolEvents.accept(connection, evt);
		}
	}

	@Override
	public void onReceiveError(Channel channel, Throwable error) {
		log.error("onReceiveError({})", channel);
		if (DISPOSED == currentOwner) {
			if (log.isDebugEnabled()) {
				log.debug("Dropping error {} because of {}", channel,
						"asynchronous user cancellation");
			}
			return;
		}
		sink.error(error);
	}

	@Override
	public void onSetup(Channel channel, @Nullable Object msg) {
		log.debug("onConnectionSetup({})", channel);
		opsFactory.create(this, this, msg);
	}

	@Override
	public void onStart(Connection connection) {
		log.debug("onConnectionStart({})", connection.channel());
		sink.success(connection);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void run() {
		Channel c = channel;
		if (currentOwner == DISPOSED) {
			return;
		}
		if (!c.isActive()) {
			log.debug("Immediately aborted pooled channel, re-acquiring new " + "channel: {}",
					c.toString());
			release(c);
			accept(pool.acquire());
			return;
		}

		ChannelHandler op = c.pipeline()
		                     .get(NettyPipeline.ReactiveBridge);

		if (op == null) {
			if (log.isDebugEnabled()) {
				log.debug("Created new pooled channel: " + c.toString());
			}
			c.closeFuture()
			 .addListener(ff -> release(c));
		}
		else if (log.isDebugEnabled()) {
			log.debug("Acquired active channel: " + c.toString());
		}
		onSetup(c, null);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void dispose() {
		Acquisition f = OWNER.getAndSet(this, DISPOSED);
		if (f == DISPOSED) {
			return;
		}
		try {
			Channel c = channel;

			if (!c.eventLoop()
			      .inEventLoop()) {
				c.eventLoop()
				 .execute(() -> disposeOperationThenRelease(c));

			}
			else {
				disposeOperationThenRelease(c);
			}

		}
		catch (Exception e) {
			log.error("Failed releasing channel", e);
			currentOwner.onReleaseEmitter.onError(e);
		}
	}

	final void disposeOperationThenRelease(Channel c) {
		ChannelOperations<?, ?> ops = ChannelOperations.get(c);
		//defer to operation dispose if present
		if (ops != null) {
			ops.dispose();
			return;
		}

		release(c);
	}

	final void release(Channel c) {
		if (log.isDebugEnabled()) {
			log.debug("Releasing channel: {}", c.toString());
		}

		if (!Connection.isPersistent(c) && c.isActive()) {
			c.close();
		}

		pool.release(c)
		    .addListener(f -> {
			    if (f.isSuccess()) {
				    currentOwner.onReleaseEmitter.onComplete();
			    }
			    else {
				    currentOwner.onReleaseEmitter.onError(f.cause());
			    }
		    });

	}

	static final class Acquisition
			implements GenericFutureListener<Future<Channel>>, Disposable {

		final Future<Channel>       future;
		final DisposableAcquire     parent;
		final DirectProcessor<Void> onReleaseEmitter;

		Acquisition(Future<Channel> future, DisposableAcquire parent) {
			this.parent = parent;
			this.future = future;
			this.onReleaseEmitter = DirectProcessor.create();
		}

		@Override
		public void operationComplete(Future<Channel> future) throws Exception {
			if (future.isCancelled()) {
				if (log.isDebugEnabled()) {
					log.debug("Cancelled {}", future.toString());
				}
				return;
			}

			Acquisition current;

			for (; ; ) {
				current = parent.currentOwner;
				if (DISPOSED == current) {
					if (log.isDebugEnabled()) {
						log.debug("Dropping acquisition {} because of {}",
								future,
								"asynchronous user cancellation");
					}
					if (future.isSuccess()) {
						parent.disposeOperationThenRelease(future.get());
					}
					return;
				}

				if (!future.isSuccess()) {
					if (future.cause() != null) {
						parent.sink.error(future.cause());
					}
					else {
						parent.sink.error(new AbortedException("error while acquiring connection"));
					}
					return;
				}
				if (OWNER.compareAndSet(parent, current, this)) {
					break;
				}
			}

			Channel c = future.get();
			parent.channel = c;

			if (c.eventLoop()
			     .inEventLoop()) {
				parent.run();
			}
			else {
				c.eventLoop()
				 .execute(parent);
			}
		}

		@Override
		public void dispose() {
			future.cancel(false);
		}

		@Override
		public boolean isDisposed() {
			return future.isCancelled() || future.isDone();
		}
	}

	static final Logger      log      = Loggers.getLogger(DisposableAcquire.class);
	static final Acquisition DISPOSED = new Acquisition(null, null);
}
