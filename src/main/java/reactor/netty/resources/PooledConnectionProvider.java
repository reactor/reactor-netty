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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import io.netty.util.internal.PlatformDependent;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Operators;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.Metrics;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelOperations;
import reactor.pool.InstrumentedPool;
import reactor.pool.PooledRef;
import reactor.pool.PooledRefMetadata;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.NonNull;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class PooledConnectionProvider implements ConnectionProvider {

	interface PoolFactory {

		InstrumentedPool<PooledConnection> newPool(
				Publisher<PooledConnection> allocator,
				Function<PooledConnection, Publisher<Void>> destroyHandler,
				BiPredicate<PooledConnection, PooledRefMetadata> evictionPredicate);
	}

	final ConcurrentMap<PoolKey, InstrumentedPool<PooledConnection>> channelPools =
			PlatformDependent.newConcurrentHashMap();
	final String                       name;
	final PoolFactory                  poolFactory;
	final long                         acquireTimeout;
	final int                          maxConnections;

	PooledConnectionProvider(String name, PoolFactory poolFactory) {
		this(name, poolFactory, 0, -1);
	}

	PooledConnectionProvider(String name, PoolFactory poolFactory, long acquireTimeout, int maxConnections) {
		this.name = name;
		this.poolFactory = poolFactory;
		this.acquireTimeout = acquireTimeout;
		this.maxConnections = maxConnections;
	}

	@Override
	public void disposeWhen(@NonNull SocketAddress address) {
		List<Map.Entry<PoolKey, InstrumentedPool<PooledConnection>>> toDispose;

		toDispose = channelPools.entrySet()
		                        .stream()
		                        .filter(p -> compareAddresses(p.getKey().holder, address))
		                        .collect(Collectors.toList());

		toDispose.forEach(e -> {
			if (channelPools.remove(e.getKey(), e.getValue())) {
				if(log.isDebugEnabled()){
					log.debug("Disposing pool for {}", e.getKey().fqdn);
				}
				e.getValue().dispose();
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

			InstrumentedPool<PooledConnection> pool;
			for (; ; ) {
				pool = channelPools.get(holder);
				if (pool != null) {
					break;
				}
				pool = new PooledConnectionAllocator(bootstrap, poolFactory, opsFactory).pool;
				if (channelPools.putIfAbsent(holder, pool) == null) {
					if (log.isDebugEnabled()) {
						log.debug("Creating new client pool [{}] for {}",
								name,
								bootstrap.config()
								         .remoteAddress());
					}
					if (BootstrapHandlers.findMetricsSupport(bootstrap) != null) {
						PooledConnectionProviderMetrics.registerMetrics(name,
								holder.hashCode() + "",
								Metrics.formatSocketAddress(bootstrap.config().remoteAddress()),
								pool.metrics());
					}
					break;
				}
				pool.dispose();
			}

			disposableAcquire(sink, obs, pool, opsFactory, acquireTimeout);

		});

	}

	@Override
	public Mono<Void> disposeLater() {
		return Mono.<Void>fromRunnable(() -> {
			InstrumentedPool<PooledConnection> pool;
			for (PoolKey key : channelPools.keySet()) {
				pool = channelPools.remove(key);
				if (pool != null) {
					pool.dispose();
				}
			}
		})
		.subscribeOn(Schedulers.elastic());
	}

	@Override
	public boolean isDisposed() {
		return channelPools.isEmpty() || channelPools.values()
		                                             .stream()
		                                             .allMatch(Disposable::isDisposed);
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

	static void disposableAcquire(MonoSink<Connection> sink, ConnectionObserver obs, InstrumentedPool<PooledConnection> pool,
			ChannelOperations.OnSetup opsFactory, long acquireTimeout) {
		DisposableAcquire disposableAcquire =
				new DisposableAcquire(sink, pool, obs, opsFactory, acquireTimeout);

		Mono<PooledRef<PooledConnection>> mono = pool.acquire(Duration.ofMillis(acquireTimeout));
		mono.subscribe(disposableAcquire);
	}

	static final Logger log = Loggers.getLogger(PooledConnectionProvider.class);

	static final AttributeKey<ConnectionObserver> OWNER =
			AttributeKey.valueOf("connectionOwner");

	static final BiPredicate<PooledConnection, PooledRefMetadata> EVICTION_PREDICATE =
			(pooledConnection, metadata) -> !pooledConnection.channel.isActive() || !pooledConnection.isPersistent();

	static final Function<PooledConnection, Publisher<Void>> DESTROY_HANDLER =
			pooledConnection -> {
				if (!pooledConnection.channel.isActive()) {
					return Mono.empty();
				}
				return FutureMono.from(pooledConnection.channel.close());
			};

	final static class PooledConnectionAllocator {

		final InstrumentedPool<PooledConnection> pool;
		final Bootstrap                 bootstrap;
		final ChannelOperations.OnSetup opsFactory;

		PooledConnectionAllocator(Bootstrap b, PoolFactory provider, ChannelOperations.OnSetup opsFactory) {
			this.bootstrap = b.clone();
			this.opsFactory = opsFactory;
			this.pool = provider.newPool(connectChannel(), DESTROY_HANDLER, EVICTION_PREDICATE);
		}

		Publisher<PooledConnection> connectChannel() {
			return Mono.create(sink -> {
				Bootstrap b = bootstrap.clone();
				PooledConnectionInitializer initializer = new PooledConnectionInitializer(sink);
				b.handler(initializer);
				ChannelFuture f = b.connect();
				if (f.isDone()) {
					initializer.operationComplete(f);
				} else {
					f.addListener(initializer);
				}
			});
		}

		final class PooledConnectionInitializer implements ChannelHandler, ChannelFutureListener {
			final MonoSink<PooledConnection> sink;

			PooledConnection pooledConnection;

			PooledConnectionInitializer(MonoSink<PooledConnection> sink) {
				this.sink = sink;
			}

			@Override
			public void handlerAdded(ChannelHandlerContext ctx) {
				ctx.pipeline().remove(this);
				Channel ch = ctx.channel();

				if (log.isDebugEnabled()) {
					log.debug(format(ch, "Created new pooled channel, now {} active connections and {} inactive connections"),
							pool.metrics().acquiredSize(),
							pool.metrics().idleSize());
				}

				PooledConnection pooledConnection = new PooledConnection(ch, pool);

				this.pooledConnection = pooledConnection;

				pooledConnection.bind();

				Bootstrap b = bootstrap.clone();

				BootstrapHandlers.finalizeHandler(b, opsFactory, pooledConnection);

				ch.pipeline()
				  .addFirst(b.config()
				             .handler());
			}

			@Override
			public void handlerRemoved(ChannelHandlerContext ctx) {
			}

			@Override
			public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
				ctx.pipeline().remove(this);
			}

			@Override
			public void operationComplete(ChannelFuture future) {
				if (future.isSuccess()) {
					sink.success(pooledConnection);
				} else {
					sink.error(future.cause());
				}
			}
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
		final InstrumentedPool<PooledConnection> pool;
		final MonoProcessor<Void> onTerminate;

		PooledRef<PooledConnection> pooledRef;

		PooledConnection(Channel channel, InstrumentedPool<PooledConnection> pool) {
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

				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "Releasing channel"));
				}

				ConnectionObserver obs = channel.attr(OWNER)
						.getAndSet(ConnectionObserver.emptyListener());

				if (pooledRef == null) {
					return;
				}

				pooledRef.release()
				         .subscribe(
				                 null,
				                 t -> {
				                     if (log.isDebugEnabled()) {
				                         log.debug("Failed cleaning the channel from pool" +
				                                 ", now {} active connections and {} inactive connections",
				                             pool.metrics().acquiredSize(),
				                             pool.metrics().idleSize(),
				                             t);
				                     }
				                     onTerminate.onComplete();
				                     obs.onStateChange(connection, State.RELEASED);
				                 },
				                 () -> {
				                     if (log.isDebugEnabled()) {
				                         log.debug(format(pooledRef.poolable().channel, "Channel cleaned, now {} active connections and " +
				                                 "{} inactive connections"),
				                             pool.metrics().acquiredSize(),
				                             pool.metrics().idleSize());
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
			implements ConnectionObserver, Runnable, CoreSubscriber<PooledRef<PooledConnection>>, Disposable {

		final MonoSink<Connection>               sink;
		final InstrumentedPool<PooledConnection> pool;
		final ConnectionObserver                 obs;
		final ChannelOperations.OnSetup          opsFactory;
		final long                               acquireTimeout;

		PooledRef<PooledConnection> pooledRef;
		Subscription subscription;

		DisposableAcquire(MonoSink<Connection> sink,
				InstrumentedPool<PooledConnection> pool,
				ConnectionObserver obs,
				ChannelOperations.OnSetup opsFactory,
				long acquireTimeout) {
			this.pool = pool;
			this.sink = sink;
			this.obs = obs;
			this.opsFactory = opsFactory;
			this.acquireTimeout = acquireTimeout;
		}

		@Override
		public void onNext(PooledRef<PooledConnection> value) {
			pooledRef = value;

			PooledConnection pooledConnection = value.poolable();
			pooledConnection.pooledRef = pooledRef;

			Channel c = pooledConnection.channel;

			if (c.eventLoop().inEventLoop()) {
				run();
			}
			else {
				c.eventLoop()
				 .execute(this);
			}
		}

		@Override
		public void dispose() {
			subscription.cancel();
		}

		@Override
		public void onError(Throwable throwable) {
			sink.error(throwable);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(subscription, s)) {
				this.subscription = s;
				sink.onCancel(this);
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onComplete() {

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
		public void run() {
			PooledConnection pooledConnection = pooledRef.poolable();
			Channel c = pooledConnection.channel;

			ConnectionObserver current = c.attr(OWNER)
			                              .getAndSet(this);

			if (current instanceof PendingConnectionObserver) {
				PendingConnectionObserver pending = (PendingConnectionObserver)current;
				PendingConnectionObserver.Pending p;
				current = null;

				while((p = pending.pendingQueue.poll()) != null) {
					if (p.error != null) {
						onUncaughtException(p.connection, p.error);
					}
					else if (p.state != null) {
						onStateChange(p.connection, p.state);
					}
				}
			}


			if (current != null) {
				if (log.isDebugEnabled()) {
					log.debug(format(c, "Channel acquired, now {} active connections and {} inactive connections"),
							pool.metrics().acquiredSize(),
							pool.metrics().idleSize());
				}
				obs.onStateChange(pooledConnection, State.ACQUIRED);

				ChannelOperations<?, ?> ops = opsFactory.create(pooledConnection, pooledConnection, null);
				if (ops != null) {
					ops.bind();
					obs.onStateChange(ops, State.CONFIGURED);
					sink.success(ops);
				}
				else {
					//already configured, just forward the connection
					sink.success(pooledConnection);
				}
				return;
			}
			//Connected, leave onStateChange forward the event if factory

			if (log.isDebugEnabled()) {
				log.debug(format(c, "Channel connected, now {} active " +
								"connections and {} inactive connections"),
						pool.metrics().acquiredSize(),
						pool.metrics().idleSize());
			}
			if (opsFactory == ChannelOperations.OnSetup.empty()) {
				sink.success(Connection.from(c));
			}
		}
	}

	final static class PoolKey {

		final SocketAddress holder;
		final int pipelineKey;
		final String fqdn;

		PoolKey(SocketAddress holder, int pipelineKey) {
			this.holder = holder;
			this.fqdn = holder instanceof InetSocketAddress ? holder.toString() : "null";
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
