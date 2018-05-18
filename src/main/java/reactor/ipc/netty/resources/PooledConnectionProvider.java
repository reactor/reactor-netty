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

package reactor.ipc.netty.resources;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

/**
 * @author Stephane Maldini
 */
final class PooledConnectionProvider implements ConnectionProvider {

	interface PoolFactory {

		ChannelPool newPool(Bootstrap b,
				ChannelPoolHandler handler,
				ChannelHealthChecker checker);
	}

	final ConcurrentMap<PoolKey, Pool> channelPools;
	final String                       name;
	final PoolFactory                  poolFactory;

	PooledConnectionProvider(String name, PoolFactory poolFactory) {
		this.name = name;
		this.poolFactory = poolFactory;
		this.channelPools = PlatformDependent.newConcurrentHashMap();
	}

	@Override
	public Mono<Connection> acquire(Bootstrap b) {
		return Mono.create(sink -> {
			Bootstrap bootstrap = b.clone();

			ChannelOperations.OnSetup opsFactory =
					BootstrapHandlers.channelOperationFactory(bootstrap);

			ConnectionObserver obs = BootstrapHandlers.connectionObserver(bootstrap);

			NewConnectionProvider.convertLazyRemoteAddress(bootstrap);
			PoolKey holder = new PoolKey(bootstrap.config()
			                                      .remoteAddress(), opsFactory);

			Pool pool;
			for (; ; ) {
				pool = channelPools.get(holder);
				if (pool != null) {
					break;
				}
				if (log.isDebugEnabled()) {
					log.debug("Creating new client pool [{}] for {}",
							name,
							bootstrap.config()
							         .remoteAddress());
				}
				pool = new Pool(bootstrap, poolFactory, opsFactory);
				if (channelPools.putIfAbsent(holder, pool) == null) {
					break;
				}
				pool.close();
			}

			Future<Channel> f = pool.acquire();
			DisposableAcquire disposableAcquire =
					new DisposableAcquire(sink, f, pool, obs);
			f.addListener(disposableAcquire);
			sink.onCancel(disposableAcquire);

		});

	}

	@Override
	public void dispose() {
		disposeLater().subscribe();
	}

	@Override
	public Mono<Void> disposeLater() {
		return Mono.fromRunnable(() -> {
			Pool pool;
			for (PoolKey key : channelPools.keySet()) {
				pool = channelPools.remove(key);
				if (pool != null) {
					pool.close();
				}
			}
		});
	}

	@Override
	public boolean isDisposed() {
		return channelPools.isEmpty() || channelPools.values()
		                                             .stream()
		                                             .allMatch(AtomicBoolean::get);
	}

	static final Logger log = Loggers.getLogger(PooledConnectionProvider.class);

	static final AttributeKey<ConnectionObserver> OWNER =
			AttributeKey.valueOf("connectionOwner");

	final static class Pool extends AtomicBoolean
			implements ChannelPoolHandler, ChannelPool, ChannelHealthChecker {

		final ChannelPool               pool;
		final EventLoopGroup            defaultGroup;
		final Bootstrap                 bootstrap;
		final ChannelOperations.OnSetup opsFactory;

		final AtomicInteger activeConnections = new AtomicInteger();

		final Future<Boolean> HEALTHY;
		final Future<Boolean> UNHEALTHY;

		@SuppressWarnings("unchecked")
		Pool(Bootstrap bootstrap,
				PoolFactory provider,
				ChannelOperations.OnSetup opsFactory) {
			this.bootstrap = bootstrap;
			this.opsFactory = opsFactory;
			this.pool = provider.newPool(bootstrap, this, this);
			this.defaultGroup = bootstrap.config()
			                             .group();
			HEALTHY = defaultGroup.next()
			                      .newSucceededFuture(true);
			UNHEALTHY = defaultGroup.next()
			                        .newSucceededFuture(false);
		}

		@Override
		public Future<Boolean> isHealthy(Channel channel) {
			return channel.isActive() ? HEALTHY : UNHEALTHY;
		}

		@Override
		public Future<Channel> acquire() {
			return acquire(defaultGroup.next()
			                           .newPromise());
		}

		@Override
		public Future<Channel> acquire(Promise<Channel> promise) {
			return pool.acquire(promise);
		}

		@Override
		public Future<Void> release(Channel channel) {
			return pool.release(channel);
		}

		@Override
		public Future<Void> release(Channel channel, Promise<Void> promise) {
			return pool.release(channel, promise);
		}

		@Override
		public void close() {
			if (compareAndSet(false, true)) {
				pool.close();
			}
		}

		@Override
		public void channelReleased(Channel ch) {
			activeConnections.decrementAndGet();
			if (log.isDebugEnabled()) {
				log.debug("Released {}, now {} active connections",
						ch.toString(),
						activeConnections);
			}
		}

		@Override
		public void channelAcquired(Channel ch) {

		}

		@Override
		public void channelCreated(Channel ch) {
			/*
				Sometimes the Channel can be notified as created (by FixedChannelPool) but
				it actually fails to connect and the FixedChannelPool will decrement its
				active count, same as if it was released. The channel close promise is
				still invoked, which can lead to double-decrement and an assertion error.

				As such, it is best to only register the close handler on the channel in
				`PooledClientContextHandler`.

				see https://github.com/reactor/reactor-netty/issues/289
			 */

			if (log.isDebugEnabled()) {
				log.debug("Created new pooled channel {}, now {} active connections",
						ch.toString(),
						activeConnections);
			}

			PooledConnection pooledConnection = new PooledConnection(ch, this);

			pooledConnection.bind();

			Bootstrap bootstrap = this.bootstrap.clone();

			BootstrapHandlers.finalizeHandler(bootstrap, opsFactory, pooledConnection);

			ch.pipeline()
			  .addFirst(bootstrap.config()
			                     .handler());
		}

		@Override
		public String toString() {
			return "{ bootstrap=" + bootstrap + ", activeConnections=" + activeConnections + '}';
		}
	}

	final static class PendingConnectionObserver implements ConnectionObserver {

		final Queue<Pending> pendingQueue = Queues.<Pending>unbounded(4).get();

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			pendingQueue.add(new Pending(connection, error, null));
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			pendingQueue.add(new Pending(connection, null, newState));
		}

		static class Pending {
			final Connection connection;
			final Throwable error;
			final State state;

			Pending(Connection connection, @Nullable Throwable error, @Nullable State state) {
				this.connection = connection;
				this.error = error;
				this.state = state;
			}
		}
	}

	final static class PooledConnection implements Connection, ConnectionObserver,
	                                               GenericFutureListener<Future<Void>> {

		final Channel channel;
		final Pool    pool;

		PooledConnection(Channel channel, Pool pool) {
			this.channel = channel;
			this.pool = pool;
		}

		ConnectionObserver owner() {
			ConnectionObserver obs;

			for (;;) {
				obs = channel.attr(OWNER)
				             .get();
				if (obs == null) {
					obs = new PendingConnectionObserver();
				}
				else {
					return obs;
				}
				if (channel.attr(OWNER)
				           .compareAndSet(null, obs)) {
					return obs;
				}
			}
		}

		@Override
		public Channel channel() {
			return channel;
		}

		@Override
		public Context currentContext() {
			return owner().currentContext();
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			log.error("Pooled Connection observed an error", error);
			owner().onUncaughtException(connection, error);
		}

		@Override
		public void operationComplete(Future<Void> future) {
			if (log.isDebugEnabled() && !future.isSuccess()) {
				log.debug("Failed cleaning the channel from pool", future.cause());
			}
			fireReleaseState();
		}

		void fireReleaseState() {
			channel.attr(OWNER)
			       .getAndSet(ConnectionObserver.emptyListener())
			       .onStateChange(this, State.RELEASED);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			log.debug("onStateChange({}, {})", connection, newState);
			if (newState == State.DISCONNECTING) {
				if (log.isDebugEnabled()) {
					log.debug("Releasing channel: {}", channel);
				}

				if (!Connection.isPersistent(channel) && channel.isActive()) {
					//will be released by closeFuture internals
					channel.close();
					return;
				}

				if (!channel.isActive()) {
					fireReleaseState();
					//will be released by poolResources internals
					return;
				}

				pool.release(channel)
				    .addListener(this);
				return;
			}

			owner().onStateChange(connection, newState);
		}
	}

	final static class DisposableAcquire
			implements Disposable, GenericFutureListener<Future<Channel>>,
			           ConnectionObserver , Runnable {

		final Future<Channel>      f;
		final MonoSink<Connection> sink;
		final Pool                 pool;
		final ConnectionObserver   obs;

		DisposableAcquire(MonoSink<Connection> sink,
				Future<Channel> future,
				Pool pool,
				ConnectionObserver obs) {
			this.f = future;
			this.pool = pool;
			this.sink = sink;
			this.obs = obs;
		}

		@Override
		public final void dispose() {
			if (isDisposed()) {
				return;
			}

			f.removeListener(this);

			if (!f.isDone()) {
				f.cancel(true);
			}
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			sink.error(error);
			obs.onUncaughtException(connection, error);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == State.CONFIGURED) {
				sink.success(connection);
			}
			obs.onStateChange(connection, newState);
		}

		@Override
		public boolean isDisposed() {
			return f.isCancelled() || f.isDone();
		}

		@Override
		public void run() {
			Channel c = f.getNow();
			pool.activeConnections.incrementAndGet();
			if (log.isDebugEnabled()) {
				log.debug("Acquired {}, now {} active connections",
						c,
						pool.activeConnections);
			}

			ConnectionObserver current = c.attr(OWNER)
			                              .getAndSet(this);

			if (current instanceof PendingConnectionObserver) {
				@SuppressWarnings("unchecked")
				PendingConnectionObserver pending = (PendingConnectionObserver)current;
				PendingConnectionObserver.Pending p;
				registerClose(c);

				while((p = pending.pendingQueue.poll()) != null) {
					if (p.error != null) {
						onUncaughtException(p.connection, p.error);
					}
					else if (p.state != null) {
						onStateChange(p.connection, p.state);
					}
				}
			}
			else if (current == null) {
				registerClose(c);
			}

			Connection conn = Connection.from(c);

			obs.onStateChange(conn, State.ACQUIRED);

			if (current != null) {
				PooledConnection con = conn.as(PooledConnection.class);
				if (con != null) {
					ChannelOperations<?, ?> ops = pool.opsFactory.create(con, con, null);
					if (ops != null) {
						ops.bind();
						obs.onStateChange(ops, State.CONFIGURED);
					}
				}
			}
		}

		void registerClose(Channel c) {
			if (log.isDebugEnabled()) {
				log.debug("Registering pool release on close event for channel {}", c);
			}
			c.closeFuture()
			 .addListener(ff -> pool.release(c));
		}

		@Override
		public final void operationComplete(Future<Channel> f) throws Exception {
			if (!f.isSuccess()) {
				if (f.isCancelled()) {
					log.debug("Cancelled acquiring from pool {}", pool);
					return;
				}
				if (f.cause() != null) {
					sink.error(f.cause());
				}
				else {
					sink.error(new IOException("error while acquiring from " + pool));
				}
			}
			else {
				Channel c = f.get();
				if (c.eventLoop().inEventLoop()) {
					run();
				}
				else {
					c.eventLoop()
					 .execute(this);
				}
			}
		}
	}

	final static class PoolKey {

		final SocketAddress             holder;
		final String                    fqdn;
		final ChannelOperations.OnSetup opsFactory;

		PoolKey(SocketAddress holder, ChannelOperations.OnSetup opsFactory) {
			this.holder = holder;
			this.opsFactory = opsFactory;
			this.fqdn = holder instanceof InetSocketAddress ? holder.toString() : null;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}

			PoolKey that = (PoolKey) o;

			return holder.equals(that.holder) && (fqdn != null ? fqdn.equals(that.fqdn) :
					that.fqdn == null) && opsFactory.equals(that.opsFactory);
		}

		@Override
		public int hashCode() {
			int result = holder.hashCode();
			result = 31 * result + (fqdn != null ? fqdn.hashCode() : 0);
			result = 31 * result + opsFactory.hashCode();
			return result;
		}
	}
/*

	final class PooledClientContextHandler<CHANNEL extends Channel>
			extends ContextHandler<CHANNEL>
			implements GenericFutureListener<Future<CHANNEL>> {

		static final Logger log = Loggers.getLogger(PooledClientContextHandler.class);

		final ClientOptions         clientOptions;
		final boolean               secure;
		final ChannelPool           pool;
		final DirectProcessor<Void> onReleaseEmitter;

		volatile Future<CHANNEL> future;

		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<PooledClientContextHandler, Future> FUTURE =
				AtomicReferenceFieldUpdater.newUpdater(PooledClientContextHandler.class,
						Future.class,
						"future");

		static final Future<?> DISPOSED = new SucceededFuture<>(null, null);

		@Override
		@SuppressWarnings("unchecked")
		public void setFuture(Future<?> future) {
			Objects.requireNonNull(future, "future");

			Future<CHANNEL> f;
			for (; ; ) {
				f = this.future;

				if (f == DISPOSED) {
					if (log.isDebugEnabled()) {
						log.debug("Cancelled existing channel from pool: {}",
								pool.toString());
					}
					sink.success();
					return;
				}

				if (FUTURE.compareAndSet(this, f, future)) {
					break;
				}
			}
			if (log.isDebugEnabled()) {
				log.debug("Acquiring existing channel from pool: {} {}",
						future,
						pool.toString());
			}
			((Future<CHANNEL>) future).addListener(this);
		}

		@Override
		@SuppressWarnings("unchecked")
		protected void terminateChannel(Channel channel) {
			release((CHANNEL) channel);
		}

		@Override
		public void operationComplete(Future<CHANNEL> future) throws Exception {
			if (future.isCancelled()) {
				if (log.isDebugEnabled()) {
					log.debug("Cancelled {}", future.toString());
				}
				return;
			}

			if (DISPOSED == this.future) {
				if (log.isDebugEnabled()) {
					log.debug("Dropping acquisition {} because of {}",
							future,
							"asynchronous user cancellation");
				}
				if (future.isSuccess()) {
					disposeOperationThenRelease(future.get());
				}
				sink.success();
				return;
			}

			if (!future.isSuccess()) {
				if (future.cause() != null) {
					fireContextError(future.cause());
				}
				else {
					fireContextError(new AbortedException("error while acquiring connection"));
				}
				return;
			}

			CHANNEL c = future.get();

			if (c.eventLoop()
			     .inEventLoop()) {
				connectOrAcquire(c);
			}
			else {
				c.eventLoop()
				 .execute(() -> connectOrAcquire(c));
			}
		}

		@Override
		protected Publisher<Void> onCloseOrRelease(Channel channel) {
			return onReleaseEmitter;
		}

		@SuppressWarnings("unchecked")
		final void connectOrAcquire(CHANNEL c) {
			if (DISPOSED == this.future) {
				if (log.isDebugEnabled()) {
					log.debug("Dropping acquisition because of asynchronous user cancellation");
				}
				disposeOperationThenRelease(c);
				sink.success();
				return;
			}

			if (!c.isActive()) {
				log.debug("Immediately aborted pooled channel, re-acquiring new channel: {}",
						c.toString());
				setFuture(pool.acquire());
				return;
			}

			if (log.isDebugEnabled()) {
				log.debug("Acquired active channel: " + c.toString());
			}
			if (createOperations(c, null) == null) {
				setFuture(pool.acquire());
			}
		}



		@Override
		@SuppressWarnings("unchecked")
		public void dispose() {
			Future<CHANNEL> f = FUTURE.getAndSet(this, DISPOSED);
			if (f == null || f == DISPOSED) {
				return;
			}
			if (!f.isDone()) {
				return;
			}

			try {
				CHANNEL c = f.get();

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
				onReleaseEmitter.onError(e);
			}
		}

		final void disposeOperationThenRelease(CHANNEL c) {
			ChannelOperations<?, ?> ops = ChannelOperations.get(c);
			//defer to operation dispose if present
			if (ops != null) {
				ops.inbound.cancel();
				return;
			}

			release(c);
		}

		final void release(CHANNEL c) {
			if (log.isDebugEnabled()) {
				log.debug("Releasing channel: {}", c.toString());
			}

			if (!NettyContext.isPersistent(c) && c.isActive()) {
				c.close();
				onReleaseEmitter.onComplete();
				//will be released by poolResources internals
				return;
			}

			if (!c.isActive()) {
				onReleaseEmitter.onComplete();
				//will be released by poolResources internals
				return;
			}

			pool.release(c)
			    .addListener(f -> {
				    if (log.isDebugEnabled() && !f.isSuccess()){
					    log.debug("Failed cleaning the channel from pool", f.cause());
				    }
				    onReleaseEmitter.onComplete();
			    });

		}

		@Override
		protected Tuple2<String, Integer> getSNI() {
			if (providedAddress instanceof InetSocketAddress) {
				InetSocketAddress ipa = (InetSocketAddress) providedAddress;
				return Tuples.of(ipa.getHostString(), ipa.getPort());
			}
			return null;
		}
	}*/
}
