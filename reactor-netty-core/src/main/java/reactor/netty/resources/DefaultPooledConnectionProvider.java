/*
 * Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.core.publisher.Sinks;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.FutureMono;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.transport.TransportConfig;
import reactor.netty.transport.TransportConnector;
import reactor.pool.InstrumentedPool;
import reactor.pool.PooledRef;
import reactor.pool.PooledRefMetadata;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;

/**
 * A default implementation for pooled {@link ConnectionProvider}.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class DefaultPooledConnectionProvider extends PooledConnectionProvider<DefaultPooledConnectionProvider.PooledConnection> {
	final Map<SocketAddress, PoolFactory<PooledConnection>> poolFactoryPerRemoteHost = new HashMap<>();
	final Map<SocketAddress, Integer> maxConnections = new HashMap<>();

	DefaultPooledConnectionProvider(Builder builder) {
		this(builder, null);
	}

	// Used only for testing purposes
	DefaultPooledConnectionProvider(Builder builder, @Nullable Clock clock) {
		super(builder, clock);
		for (Map.Entry<SocketAddress, ConnectionPoolSpec<?>> entry : builder.confPerRemoteHost.entrySet()) {
			poolFactoryPerRemoteHost.put(entry.getKey(), new PoolFactory<>(entry.getValue(), builder.disposeTimeout));
			maxConnections.put(entry.getKey(), entry.getValue().maxConnections);
		}
	}

	@Override
	public Map<SocketAddress, Integer> maxConnectionsPerHost() {
		return maxConnections;
	}

	@Override
	protected CoreSubscriber<PooledRef<PooledConnection>> createDisposableAcquire(
			TransportConfig config,
			ConnectionObserver connectionObserver,
			long pendingAcquireTimeout,
			InstrumentedPool<PooledConnection> pool,
			MonoSink<Connection> sink) {
		return new DisposableAcquire(connectionObserver, config.channelOperationsProvider(),
				pendingAcquireTimeout, pool, sink);
	}

	@Override
	protected InstrumentedPool<PooledConnection> createPool(
			TransportConfig config,
			PoolFactory<PooledConnection> poolFactory,
			SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup) {
		return new PooledConnectionAllocator(config, poolFactory, remoteAddress, resolverGroup).pool;
	}

	@Override
	protected PoolFactory<PooledConnection> poolFactory(SocketAddress remoteAddress) {
		return poolFactoryPerRemoteHost.getOrDefault(remoteAddress, defaultPoolFactory);
	}

	static final Logger log = Loggers.getLogger(DefaultPooledConnectionProvider.class);

	static final AttributeKey<ConnectionObserver> OWNER = AttributeKey.valueOf("connectionOwner");

	static final class DisposableAcquire
			implements ConnectionObserver, Runnable, CoreSubscriber<PooledRef<PooledConnection>>, Disposable {
		final Disposable.Composite cancellations;
		final ConnectionObserver obs;
		final ChannelOperations.OnSetup opsFactory;
		final long pendingAcquireTimeout;
		final InstrumentedPool<PooledConnection> pool;
		final boolean retried;
		final MonoSink<Connection> sink;

		PooledRef<PooledConnection> pooledRef;
		Subscription subscription;

		DisposableAcquire(
				ConnectionObserver obs,
				ChannelOperations.OnSetup opsFactory,
				long pendingAcquireTimeout,
				InstrumentedPool<PooledConnection> pool,
				MonoSink<Connection> sink) {
			this.cancellations = Disposables.composite();
			this.obs = obs;
			this.opsFactory = opsFactory;
			this.pendingAcquireTimeout = pendingAcquireTimeout;
			this.pool = pool;
			this.retried = false;
			this.sink = sink;
		}

		DisposableAcquire(DisposableAcquire parent) {
			this.cancellations = parent.cancellations;
			this.obs = parent.obs;
			this.opsFactory = parent.opsFactory;
			this.pendingAcquireTimeout = parent.pendingAcquireTimeout;
			this.pool = parent.pool;
			this.retried = true;
			this.sink = parent.sink;
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		public void dispose() {
			subscription.cancel();
		}

		@Override
		public void onComplete() {
			// noop
		}

		@Override
		public void onError(Throwable throwable) {
			sink.error(throwable);
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
				c.eventLoop().execute(this);
			}
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == State.CONFIGURED) {
				// First send a notification that the connection is ready and then change the state
				// In case a cancellation was received, ChannelOperations will be disposed
				// and there will be no subscription to the I/O handler at all.
				// https://github.com/reactor/reactor-netty/issues/1165
				sink.success(connection);
			}
			obs.onStateChange(connection, newState);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(subscription, s)) {
				this.subscription = s;
				cancellations.add(this);
				if (!retried) {
					sink.onCancel(cancellations);
				}
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			sink.error(error);
			obs.onUncaughtException(connection, error);
		}

		@Override
		public void run() {
			PooledConnection pooledConnection = pooledRef.poolable();
			Channel c = pooledConnection.channel;

			// The connection might be closed after checking the eviction predicate
			if (!c.isActive()) {
				pooledRef.invalidate()
				         .subscribe(null, null, () -> {
				             if (log.isDebugEnabled()) {
				                 logPoolState(c, pool, "Channel closed");
				             }
				         });
				if (!retried) {
					if (log.isDebugEnabled()) {
						log.debug(format(c, "Immediately aborted pooled channel, re-acquiring new channel"));
					}
					pool.acquire(Duration.ofMillis(pendingAcquireTimeout))
					    .subscribe(new DisposableAcquire(this));
				}
				else {
					sink.error(new IOException("Error while acquiring from " + pool));
				}
				return;
			}

			// Set the owner only if the channel is active
			ConnectionObserver current = c.attr(OWNER)
			                              .getAndSet(this);

			if (current instanceof PendingConnectionObserver) {
				PendingConnectionObserver pending = (PendingConnectionObserver) current;
				PendingConnectionObserver.Pending p;
				current = null;
				registerClose(pooledRef, pool);

				while ((p = pending.pendingQueue.poll()) != null) {
					if (p.error != null) {
						onUncaughtException(p.connection, p.error);
					}
					else if (p.state != null) {
						onStateChange(p.connection, p.state);
					}
				}
			}
			else if (current == null) {
				registerClose(pooledRef, pool);
			}


			if (current != null) {
				if (log.isDebugEnabled()) {
					logPoolState(c, pool, "Channel acquired");
				}
				obs.onStateChange(pooledConnection, State.ACQUIRED);

				ChannelOperations<?, ?> ops = opsFactory.create(pooledConnection, pooledConnection, null);
				if (ops != null) {
					if (c.pipeline().get(NettyPipeline.H2MultiplexHandler) == null) {
						ops.bind();
						// First send a notification that the connection is ready and then change the state
						// In case a cancellation was received, ChannelOperations will be disposed
						// and there will be no subscription to the I/O handler at all.
						// https://github.com/reactor/reactor-netty/issues/1165
						sink.success(ops);
						obs.onStateChange(ops, State.CONFIGURED);
					}
					else {
						// First send a notification that the connection is ready and then change the state
						// In case a cancellation was received, ChannelOperations will be disposed
						// and there will be no subscription to the I/O handler at all.
						// https://github.com/reactor/reactor-netty/issues/1165
						sink.success(ops);
						obs.onStateChange(pooledConnection, State.CONFIGURED);
					}
				}
				else {
					// Already configured, just forward the connection
					sink.success(pooledConnection);
				}
				return;
			}
			// Connected, leave onStateChange forward the event if factory

			if (log.isDebugEnabled()) {
				logPoolState(c, pool, "Channel connected");
			}
			if (opsFactory == ChannelOperations.OnSetup.empty()) {
				sink.success(Connection.from(c));
			}
		}

		void registerClose(PooledRef<PooledConnection> pooledRef, InstrumentedPool<PooledConnection> pool) {
			Channel channel = pooledRef.poolable().channel;
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Registering pool release on close event for channel"));
			}
			channel.closeFuture()
			       .addListener(ff -> {
			           // When the connection is released the owner is NOOP
			           ConnectionObserver owner = channel.attr(OWNER).get();
			           if (owner instanceof DisposableAcquire) {
			               ((DisposableAcquire) owner).pooledRef
			                       .invalidate()
			                       .subscribe(null, null, () -> {
			                           if (log.isDebugEnabled()) {
			                               logPoolState(channel, pool, "Channel closed");
			                           }
			                       });
			           }
			       });
		}
	}

	static final class PendingConnectionObserver implements ConnectionObserver {
		final Queue<Pending> pendingQueue = Queues.<Pending>unbounded(4).get();
		final Context context;

		public PendingConnectionObserver() {
			this(Context.empty());
		}

		public PendingConnectionObserver(final Context context) {
			this.context = context;
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			pendingQueue.add(new Pending(connection, null, newState));
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			pendingQueue.add(new Pending(connection, error, null));
		}

		@Override
		public Context currentContext() {
			return this.context;
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

	static final class PooledConnection extends AtomicLong implements Connection, ConnectionObserver {
		final Channel channel;
		final Sinks.Empty<Void> onTerminate;
		final InstrumentedPool<PooledConnection> pool;

		PooledRef<PooledConnection> pooledRef;

		PooledConnection(Channel channel, InstrumentedPool<PooledConnection> pool) {
			this.channel = channel;
			this.onTerminate = Sinks.unsafe().empty();
			this.pool = pool;
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
		@SuppressWarnings("FutureReturnValueIgnored")
		public void onStateChange(Connection connection, State newState) {
			if (log.isDebugEnabled()) {
				log.debug(format(connection.channel(), "onStateChange({}, {})"), connection, newState);
			}

			if (newState == State.DISCONNECTING) {
				if (!isPersistent() && channel.isActive()) {
					// Will be released by closeFuture
					// "FutureReturnValueIgnored" this is deliberate
					channel.close();
					owner().onStateChange(connection, State.DISCONNECTING);
					return;
				}

				if (!channel.isActive()) {
					owner().onStateChange(connection, State.DISCONNECTING);
					// Will be released by closeFuture
					return;
				}

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
				                         logPoolState(pooledRef.poolable().channel, pool,
				                                 "Failed cleaning the channel from pool", t);
				                     }
				                     // EmitResult is ignored as it is guaranteed that this call happens in an event loop
				                     // and it is guarded by release(), so tryEmitEmpty() should happen just once
				                     onTerminate.tryEmitEmpty();
				                     obs.onStateChange(connection, State.RELEASED);
				                 },
				                 () -> {
				                     if (log.isDebugEnabled()) {
				                         logPoolState(pooledRef.poolable().channel, pool, "Channel cleaned");
				                     }
				                     // EmitResult is ignored as it is guaranteed that this call happens in an event loop
				                     // and it is guarded by release(), so tryEmitEmpty() should happen just once
				                     onTerminate.tryEmitEmpty();
				                     obs.onStateChange(connection, State.RELEASED);
				                 });
				return;
			}

			owner().onStateChange(connection, newState);
		}

		@Override
		public Mono<Void> onTerminate() {
			return onTerminate.asMono().or(onDispose());
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			owner().onUncaughtException(connection, error);
		}

		@Override
		public String toString() {
			return "PooledConnection{" + "channel=" + channel + '}';
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
	}

	static final class PooledConnectionAllocator {
		final TransportConfig config;
		final InstrumentedPool<PooledConnection> pool;
		final SocketAddress remoteAddress;
		final AddressResolverGroup<?> resolver;

		PooledConnectionAllocator(
				TransportConfig config,
				PoolFactory<PooledConnection> provider,
				SocketAddress remoteAddress,
				AddressResolverGroup<?> resolver) {
			this.config = config;
			this.remoteAddress = remoteAddress;
			this.resolver = resolver;
			this.pool = provider.newPool(connectChannel(), null, DEFAULT_DESTROY_HANDLER, DEFAULT_EVICTION_PREDICATE);
		}

		Publisher<PooledConnection> connectChannel() {
			return Mono.create(sink -> {
				PooledConnectionInitializer initializer = new PooledConnectionInitializer(sink);
				TransportConnector.connect(config, remoteAddress, resolver, initializer)
				                  .subscribe(initializer);
			});
		}

		final class PooledConnectionInitializer extends ChannelInitializer<Channel> implements CoreSubscriber<Channel> {
			final MonoSink<PooledConnection> sink;

			PooledConnection pooledConnection;

			PooledConnectionInitializer(MonoSink<PooledConnection> sink) {
				this.sink = sink;
			}

			@Override
			protected void initChannel(Channel ch) {
				if (log.isDebugEnabled()) {
					logPoolState(ch, pool, "Created a new pooled channel");
				}

				PooledConnection pooledConnection = new PooledConnection(ch, pool);

				this.pooledConnection = pooledConnection;

				ch.attr(OWNER).compareAndSet(null, new PendingConnectionObserver(sink.currentContext()));
				ch.pipeline().remove(this);
				ch.pipeline()
				  .addFirst(config.channelInitializer(pooledConnection, remoteAddress, false));

				pooledConnection.bind();
			}

			@Override
			public void onComplete() {
				// noop
			}

			@Override
			public void onError(Throwable t) {
				sink.error(t);
			}

			@Override
			public void onNext(Channel channel) {
				sink.success(pooledConnection);
			}

			@Override
			public void onSubscribe(Subscription s) {
				s.request(Long.MAX_VALUE);
			}
		}

		static final BiPredicate<PooledConnection, PooledRefMetadata> DEFAULT_EVICTION_PREDICATE =
				(pooledConnection, metadata) -> !pooledConnection.channel.isActive() || !pooledConnection.isPersistent();

		static final Function<PooledConnection, Publisher<Void>> DEFAULT_DESTROY_HANDLER =
				pooledConnection -> {
					if (!pooledConnection.channel.isActive()) {
						return Mono.empty();
					}
					return FutureMono.from(pooledConnection.channel.close());
				};
	}
}
