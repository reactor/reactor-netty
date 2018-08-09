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

package reactor.netty.resources;

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
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.MonoSink;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;

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

			disposableAcquire(sink, obs, pool);

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

	@SuppressWarnings("FutureReturnValueIgnored")
	static void disposableAcquire(MonoSink<Connection> sink, ConnectionObserver obs, Pool pool) {
		Future<Channel> f = pool.acquire();
		DisposableAcquire disposableAcquire =
				new DisposableAcquire(sink, f, pool, obs);
		// Returned value is deliberately ignored
		f.addListener(disposableAcquire);
		sink.onCancel(disposableAcquire);
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
				log.debug(format(ch, "Channel cleaned, now {} active connections"),
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
				log.debug(format(ch, "Created new pooled channel, now {} active connections"),
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
		final MonoProcessor<Void> onTerminate;

		PooledConnection(Channel channel, Pool pool) {
			this.channel = channel;
			this.pool = pool;
			this.onTerminate = MonoProcessor.create();
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
		public Mono<Void> onTerminate() {
			return onTerminate.or(onDispose());
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
			log.error(format(connection.channel(), "Pooled connection observed an error"), error);
			owner().onUncaughtException(connection, error);
		}

		@Override
		public void operationComplete(Future<Void> future) {
			if (log.isDebugEnabled() && !future.isSuccess()) {
				log.debug("Failed cleaning the channel from pool", future.cause());
			}
			onTerminate.onComplete();
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if(log.isDebugEnabled()) {
				log.debug(format(connection.channel(), "onStateChange({}, {})"), connection, newState);
			}
			if (newState == State.DISCONNECTING) {

				if (!isPersistent() && channel.isActive()) {
					//will be released by closeFuture internals
					owner().onStateChange(connection, State.DISCONNECTING);
					channel.close();
					return;
				}

				if (!channel.isActive()) {
					owner().onStateChange(connection, State.DISCONNECTING);
					//will be released by poolResources internals
					return;
				}

				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "Releasing channel"));
				}

				channel.attr(OWNER)
						.getAndSet(ConnectionObserver.emptyListener())
						.onStateChange(this, State.RELEASED);
				pool.release(channel)
				    .addListener(this);
				return;
			}

			owner().onStateChange(connection, newState);
		}

		@Override
		public String toString() {
			return "PooledConnection{" + "channel=" + channel + '}';
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
		@SuppressWarnings("FutureReturnValueIgnored")
		public final void dispose() {
			if (isDisposed()) {
				return;
			}

			// Returned value is deliberately ignored
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


			ConnectionObserver current = c.attr(OWNER)
			                              .getAndSet(this);

			if (current instanceof PendingConnectionObserver) {
				@SuppressWarnings("unchecked")
				PendingConnectionObserver pending = (PendingConnectionObserver)current;
				PendingConnectionObserver.Pending p;
				current = null;
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


			if (current != null) {
				if (log.isDebugEnabled()) {
					log.debug(format(c, "Channel acquired, now {} active connection(s)"),
							pool.activeConnections);
				}
				obs.onStateChange(conn, State.ACQUIRED);

				PooledConnection con = conn.as(PooledConnection.class);
				if (con != null) {
					ChannelOperations<?, ?> ops = pool.opsFactory.create(con, con, null);
					if (ops != null) {
						ops.bind();
						sink.success(ops);
						obs.onStateChange(ops, State.CONFIGURED);
					}
					else {
						//already configured, just forward the connection
						sink.success(con);
					}
				}
				else {
					//already bound, just forward the connection
					sink.success(conn);
				}
			}
			else {
				if (log.isDebugEnabled()) {
					log.debug(format(c, "Channel connected, now {} active connection(s)"),
							pool.activeConnections);
				}
				sink.success(conn);
			}
		}

		void registerClose(Channel c) {
			if (log.isDebugEnabled()) {
				log.debug(format(c, "Registering pool release on close event for channel"));
			}
			c.closeFuture()
			 .addListener(ff -> pool.release(c));
		}

		@Override
		public final void operationComplete(Future<Channel> f) throws Exception {
			if (!f.isSuccess()) {
				if (f.isCancelled()) {
					if (log.isDebugEnabled()) {
						log.debug("Cancelled acquiring from pool {}", pool);
					}
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

				if (!c.isActive()) {
					//TODO is this case necessary
					if (log.isDebugEnabled()) {
						log.debug(format(c, "Immediately aborted pooled channel, re-acquiring new channel"));
					}
					disposableAcquire(sink, obs, pool);
					return;
				}
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
}
