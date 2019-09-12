/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.resources;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
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
import reactor.core.scheduler.Schedulers;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.NonNull;
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
	final int                          maxConnections;

	PooledConnectionProvider(String name, PoolFactory poolFactory) {
		this.name = name;
		this.poolFactory = poolFactory;
		this.channelPools = PlatformDependent.newConcurrentHashMap();
		this.maxConnections = -1;
	}

	PooledConnectionProvider(String name, PoolFactory poolFactory, int maxConnections) {
		this.name = name;
		this.poolFactory = poolFactory;
		this.channelPools = PlatformDependent.newConcurrentHashMap();
		this.maxConnections = maxConnections;
	}

	@Override
	public void disposeWhen(@NonNull SocketAddress address) {
		List<Map.Entry<PoolKey, Pool>> toDispose;

		toDispose = channelPools.entrySet()
		                        .stream()
		                        .filter(p -> compareAddresses(p.getKey().holder, address))
		                        .collect(Collectors.toList());

		toDispose.forEach(e -> {
			if (channelPools.remove(e.getKey(), e.getValue())) {
				if(log.isDebugEnabled()){
					log.debug("Disposing pool for {}", e.getKey().fqdn);
				}
				e.getValue().pool.close();
			}
		});
	}

	private boolean compareAddresses(SocketAddress origin, SocketAddress target) {
		if (origin.equals(target)) {
			return true;
		}
		else if (origin instanceof InetSocketAddress &&
				target instanceof InetSocketAddress) {
			InetSocketAddress isaOrigin = (InetSocketAddress) origin;
			InetSocketAddress isaTarget = (InetSocketAddress) target;
			InetAddress iaTarget = isaTarget.getAddress();
			return iaTarget != null && iaTarget.isAnyLocalAddress() &&
					isaOrigin.getPort() == isaTarget.getPort();
		}
		return false;
	}

	@Override
	public Mono<Connection> acquire(Bootstrap b) {
		return Mono.create(sink -> {
			Bootstrap bootstrap = b.clone();

			ChannelOperations.OnSetup opsFactory =
					BootstrapHandlers.channelOperationFactory(bootstrap);

			ConnectionObserver obs = BootstrapHandlers.connectionObserver(bootstrap);

			NewConnectionProvider.convertLazyRemoteAddress(bootstrap);
			ChannelHandler handler = bootstrap.config().handler();
			PoolKey holder = new PoolKey(bootstrap.config().remoteAddress(),
					handler != null ? handler.hashCode() : -1);

			Pool pool = channelPools.computeIfAbsent(holder, poolKey -> {
				if (log.isDebugEnabled()) {
					log.debug("Creating new client pool [{}] for {}",
							name,
							bootstrap.config()
									.remoteAddress());
				}
				return new Pool(bootstrap, poolFactory, opsFactory);
			});

			disposableAcquire(sink, obs, pool, false);

		});

	}

	@Override
	public Mono<Void> disposeLater() {
		return Mono.<Void>fromRunnable(() -> {
			Pool pool;
			for (PoolKey key : channelPools.keySet()) {
				pool = channelPools.remove(key);
				if (pool != null) {
					pool.close();
				}
			}
		})
		.subscribeOn(Schedulers.elastic());
	}

	@Override
	public boolean isDisposed() {
		return channelPools.isEmpty() || channelPools.values()
		                                             .stream()
		                                             .allMatch(AtomicBoolean::get);
	}

	@Override
	public int maxConnections() {
		return maxConnections;
	}

	@Override
	public String toString() {
		return "PooledConnectionProvider {" +
				"name=" + name +
				", poolFactory=" + poolFactory +
				'}';
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	static void disposableAcquire(MonoSink<Connection> sink, ConnectionObserver obs, Pool pool, boolean retried) {
		Future<Channel> f = pool.acquire();
		DisposableAcquire disposableAcquire =
				new DisposableAcquire(sink, f, pool, obs, retried);
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
		final AtomicInteger inactiveConnections = new AtomicInteger();

		final Future<Boolean> HEALTHY;
		final Future<Boolean> UNHEALTHY;

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
			inactiveConnections.incrementAndGet();
			if (log.isDebugEnabled()) {
				log.debug(format(ch, "Channel cleaned, now {} active connections and {} inactive connections"),
						activeConnections, inactiveConnections);
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

			inactiveConnections.incrementAndGet();
			if (log.isDebugEnabled()) {
				log.debug(format(ch, "Created new pooled channel, now {} active connections and {} inactive connections"),
						activeConnections, inactiveConnections);
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
			return "{ bootstrap=" + bootstrap +
					", activeConnections=" + activeConnections +
					", inactiveConnections=" + inactiveConnections +
					'}';
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

	final static class PooledConnection implements Connection, ConnectionObserver {

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
			owner().onUncaughtException(connection, error);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if(log.isDebugEnabled()) {
				log.debug(format(connection.channel(), "onStateChange({}, {})"), connection, newState);
			}
			if (newState == State.DISCONNECTING) {

				if (!isPersistent() && channel.isActive()) {
					//will be released by closeFuture internals
					channel.close();
					owner().onStateChange(connection, State.DISCONNECTING);
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

				ConnectionObserver obs = channel.attr(OWNER)
						.getAndSet(ConnectionObserver.emptyListener());

				pool.release(channel)
				    .addListener(f -> {
					    if (log.isDebugEnabled() && !f.isSuccess()) {
						    log.debug("Failed cleaning the channel from pool", f.cause());
					    }
					    onTerminate.onComplete();
						obs.onStateChange(connection, State.RELEASED);
				    });
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
		final boolean              retried;

		DisposableAcquire(MonoSink<Connection> sink,
				Future<Channel> future,
				Pool pool,
				ConnectionObserver obs,
				boolean retried) {
			this.f = future;
			this.pool = pool;
			this.sink = sink;
			this.obs = obs;
			this.retried = retried;
		}

		@Override
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
			pool.inactiveConnections.decrementAndGet();


			ConnectionObserver current = c.attr(OWNER)
			                              .getAndSet(this);

			if (current instanceof PendingConnectionObserver) {
				PendingConnectionObserver pending = (PendingConnectionObserver)current;
				PendingConnectionObserver.Pending p;
				current = null;
				registerClose(c, pool);

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
				registerClose(c, pool);
			}


			if (current != null) {
				Connection conn = Connection.from(c);
				if (log.isDebugEnabled()) {
					log.debug(format(c, "Channel acquired, now {} active connections and {} inactive connections"),
							pool.activeConnections, pool.inactiveConnections);
				}
				obs.onStateChange(conn, State.ACQUIRED);

				PooledConnection con = conn.as(PooledConnection.class);
				if (con != null) {
					ChannelOperations<?, ?> ops = pool.opsFactory.create(con, con, null);
					if (ops != null) {
						ops.bind();
						obs.onStateChange(ops, State.CONFIGURED);
						sink.success(ops);
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
				return;
			}
			//Connected, leave onStateChange forward the event if factory

			if (log.isDebugEnabled()) {
				log.debug(format(c, "Channel connected, now {} active " +
								"connections and {} inactive connections"),
						pool.activeConnections, pool.inactiveConnections);
			}
			if (pool.opsFactory == ChannelOperations.OnSetup.empty()) {
				sink.success(Connection.from(c));
			}
		}

		// The close lambda expression refers to pool,
		// so it will have a implicit reference to `DisposableAcquire.this`,
		// As a result this will avoid GC from recycling other references of this.
		// Use Pool in the method declaration to avoid it.
		void registerClose(Channel c, Pool pool) {
			if (log.isDebugEnabled()) {
				log.debug(format(c, "Registering pool release on close event for channel"));
			}
			c.closeFuture()
			 .addListener(ff -> {
			     if (AttributeKey.exists("channelPool." + System.identityHashCode(pool.pool))) {
			         pool.release(c);
			     }
			     pool.inactiveConnections.decrementAndGet();
			     if (log.isDebugEnabled()) {
			         log.debug(format(c, "Channel closed, now {} active connections and {} inactive connections"),
			                 pool.activeConnections, pool.inactiveConnections);
			     }
			 });
		}

		@Override
		public final void operationComplete(Future<Channel> f) throws Exception {
			if (!f.isSuccess()) {
				if (f.isCancelled()) {
					pool.inactiveConnections.decrementAndGet();
					if (log.isDebugEnabled()) {
						log.debug("Cancelled acquiring from pool {}", pool);
					}
					return;
				}
				Throwable cause = f.cause();
				if (cause != null) {
					if (!(cause instanceof TimeoutException) && !(cause instanceof IllegalStateException)) {
						pool.inactiveConnections.decrementAndGet();
					}
					sink.error(f.cause());
				}
				else {
					pool.inactiveConnections.decrementAndGet();
					sink.error(new IOException("Error while acquiring from " + pool));
				}
			}
			else {
				Channel c = f.get();

				if (!c.isActive()) {
					registerClose(c, pool);
					if (!retried) {
						if (log.isDebugEnabled()) {
							log.debug(format(c, "Immediately aborted pooled channel, re-acquiring new channel"));
						}
						disposableAcquire(sink, obs, pool, true);
					}
					else {
						Throwable cause = f.cause();
						if (cause != null) {
							sink.error(cause);
						}
						else {
							sink.error(new IOException("Error while acquiring from " + pool));
						}
					}
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

		final SocketAddress holder;
		final int pipelineKey;
		final String fqdn;

		PoolKey(SocketAddress holder, int pipelineKey) {
			this.holder = holder;
			this.fqdn = holder instanceof InetSocketAddress ? holder.toString() : null;
			this.pipelineKey = pipelineKey;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			PoolKey poolKey = (PoolKey) o;
			return pipelineKey == poolKey.pipelineKey &&
					Objects.equals(holder, poolKey.holder) &&
					Objects.equals(fqdn, poolKey.fqdn);
		}

		@Override
		public int hashCode() {
			return Objects.hash(holder, pipelineKey, fqdn);
		}
	}
}
