/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslHandler;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.PooledConnectionProvider;
import reactor.netty.transport.TransportConfig;
import reactor.pool.AllocationStrategy;
import reactor.pool.InstrumentedPool;
import reactor.pool.PooledRef;
import reactor.pool.PooledRefMetadata;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import static reactor.netty.ConnectionObserver.State.DISCONNECTING;
import static reactor.netty.ReactorNetty.format;
import static reactor.netty.http.client.HttpClientState.STREAM_CONFIGURED;
import static reactor.netty.http.client.HttpClientState.UPGRADE_REJECTED;
import static reactor.netty.http.client.HttpClientState.UPGRADE_SUCCESSFUL;

/**
 * A HTTP/2 implementation for pooled {@link ConnectionProvider}.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class Http2ConnectionProvider extends PooledConnectionProvider<Connection> {
	final ConnectionProvider parent;
	final int maxHttp2Connections;

	Http2ConnectionProvider(ConnectionProvider parent, int maxHttp2Connections) {
		super(ConnectionProvider.builder("http2").maxConnections(maxHttp2Connections).pendingAcquireMaxCount(-1));
		this.maxHttp2Connections = maxHttp2Connections;
		this.parent = parent;
	}

	@Override
	protected CoreSubscriber<PooledRef<Connection>> createDisposableAcquire(
			ConnectionObserver connectionObserver,
			ChannelOperations.OnSetup opsFactory,
			long pendingAcquireTimeout,
			InstrumentedPool<Connection> pool,
			MonoSink<Connection> sink) {
		return new DisposableAcquire(connectionObserver, opsFactory, pendingAcquireTimeout, pool, sink);
	}

	@Override
	protected InstrumentedPool<Connection> createPool(
			TransportConfig config,
			PooledConnectionProvider.PoolFactory<Connection> poolFactory,
			SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup) {
		return new PooledConnectionAllocator(parent, config, maxHttp2Connections, poolFactory, () -> remoteAddress, resolverGroup).pool;
	}

	static void invalidate(@Nullable ConnectionObserver owner, Channel channel) {
		if (owner instanceof DisposableAcquire) {
			DisposableAcquire da = ((DisposableAcquire) owner);
			da.pooledRef
			  .invalidate()
			  .subscribe(null, null, () -> {
			      if (log.isDebugEnabled()) {
			          log.debug(format(channel, "Channel removed from the pool, now {} active connections and {} inactive connections"),
			              da.pool.metrics().acquiredSize(),
			              da.pool.metrics().idleSize());
			      }
			  });
		}
	}

	static void registerClose(Channel channel) {
		ConnectionObserver owner = channel.attr(OWNER).get();
		channel.closeFuture()
		       .addListener(f -> {
		           Channel parent = channel.parent();
		           Http2FrameCodec frameCodec = parent.pipeline().get(Http2FrameCodec.class);
		           int numActiveStreams = frameCodec.connection().local().numActiveStreams();
		           if (log.isDebugEnabled()) {
		               log.debug(format(channel, "Stream closed, now {} active streams, {} max active streams."),
		                       numActiveStreams,
		                       frameCodec.connection().local().maxActiveStreams());
		           }

		           if (numActiveStreams == 0) {
		               channel.attr(OWNER).set(null);
		               invalidate(owner, parent);
		           }
		       });
	}

	static final Logger log = Loggers.getLogger(Http2ConnectionProvider.class);

	static final AttributeKey<ConnectionObserver> OWNER = AttributeKey.valueOf("http2ConnectionOwner");

	static final class DelegatingConnectionObserver implements ConnectionObserver {

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			owner(connection.channel()).onUncaughtException(connection, error);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			owner(connection.channel()).onStateChange(connection, newState);
		}

		ConnectionObserver owner(Channel channel) {
			ConnectionObserver obs;

			for (; ; ) {
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

	static final class DisposableAcquire
			implements CoreSubscriber<PooledRef<Connection>>, ConnectionObserver, Disposable, GenericFutureListener<Future<Http2StreamChannel>> {
		final Disposable.Composite cancellations;
		final ConnectionObserver obs;
		final ChannelOperations.OnSetup opsFactory;
		final long pendingAcquireTimeout;
		final InstrumentedPool<Connection> pool;
		final boolean retried;
		final MonoSink<Connection> sink;

		PooledRef<Connection> pooledRef;
		Subscription subscription;

		DisposableAcquire(
				ConnectionObserver obs,
				ChannelOperations.OnSetup opsFactory,
				long pendingAcquireTimeout,
				InstrumentedPool<Connection> pool,
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
		public void onError(Throwable t) {
			sink.error(t);
		}

		@Override
		public void onNext(PooledRef<Connection> pooledRef) {
			this.pooledRef = pooledRef;
			Channel channel = pooledRef.poolable().channel();

			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Channel activated, now {} active connections and {} inactive connections"),
						pool.metrics().acquiredSize(),
						pool.metrics().idleSize());
			}

			ConnectionObserver current = channel.attr(OWNER)
			                                    .getAndSet(this);

			if (current instanceof PendingConnectionObserver) {
				PendingConnectionObserver pending = (PendingConnectionObserver) current;
				PendingConnectionObserver.Pending p;

				while ((p = pending.pendingQueue.poll()) != null) {
					if (p.error != null) {
						onUncaughtException(p.connection, p.error);
					}
					else if (p.state != null) {
						onStateChange(p.connection, p.state);
					}
				}
			}

			if (notHttp2()) {
				return;
			}

			if (isH2cUpgrade()) {
				return;
			}

			HttpClientConfig.openStream(channel, obs, opsFactory)
			                .addListener(this);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == UPGRADE_SUCCESSFUL || newState == DISCONNECTING) {
				release(connection.channel());
			}
			else if (newState == UPGRADE_REJECTED) {
				invalidate(connection.channel().attr(OWNER).get(), connection.channel());
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
			obs.onUncaughtException(connection, error);
		}

		@Override
		public void operationComplete(Future<Http2StreamChannel> future) {
			Channel channel = pooledRef.poolable().channel();
			Http2FrameCodec frameCodec = channel.pipeline().get(Http2FrameCodec.class);
			if (future.isSuccess()) {
				Http2StreamChannel ch = future.getNow();

				if (!frameCodec.connection().local().canOpenStream()) {
					if (!retried) {
						if (log.isDebugEnabled()) {
							log.debug(format(ch, "Immediately aborted pooled channel max active streams is reached," +
								"re-acquiring a new channel"));
						}
						pool.acquire(Duration.ofMillis(pendingAcquireTimeout))
						    .subscribe(new DisposableAcquire(this));
					}
					else {
						sink.error(new IOException("Error while acquiring from " + pool + ". Max active streams is reached."));
					}
				}
				else {
					ChannelOperations<?, ?> ops = ChannelOperations.get(ch);
					if (ops != null) {
						obs.onStateChange(ops, STREAM_CONFIGURED);
						sink.success(ops);
					}

					if (log.isDebugEnabled()) {
						log.debug(format(ch, "Stream opened, now {} active streams, {} max active streams."),
								frameCodec.connection().local().numActiveStreams(),
								frameCodec.connection().local().maxActiveStreams());
					}
				}
			}
			else {
				sink.error(future.cause());
			}

			release(this, channel);
		}

		boolean isH2cUpgrade() {
			Channel channel = pooledRef.poolable().channel();
			if (channel.pipeline().get(NettyPipeline.H2CUpgradeHandler) != null &&
						channel.pipeline().get(NettyPipeline.H2MultiplexHandler) == null) {
				ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
				if (ops != null) {
					sink.success(ops);
					return true;
				}
			}
			return false;
		}

		boolean notHttp2() {
			Channel channel = pooledRef.poolable().channel();
			SslHandler handler = channel.pipeline().get(SslHandler.class);
			if (handler != null) {
				String protocol = handler.applicationProtocol() != null ? handler.applicationProtocol() : ApplicationProtocolNames.HTTP_1_1;
				if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
					// No information for the negotiated application-level protocol
					// or it is HTTP/1.1, continue as an HTTP/1.1 request
					// and remove the connection from this pool.
					ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
					if (ops != null) {
						sink.success(ops);
						invalidate(this, channel);
						return true;
					}
				}
				else if (!ApplicationProtocolNames.HTTP_2.equals(handler.applicationProtocol())) {
					channel.attr(OWNER).set(null);
					invalidate(this, channel);
					sink.error(new IOException("Unknown protocol [" + protocol + "]."));
					return true;
				}
			}
			return false;
		}

		static void release(Channel channel) {
			release(channel.attr(OWNER).get(), channel);
		}

		static void release(@Nullable ConnectionObserver owner, Channel channel) {
			if (owner instanceof DisposableAcquire) {
				DisposableAcquire da = ((DisposableAcquire) owner);
				da.pooledRef
				  .release()
				  .subscribe(null, null, () -> {
				      if (log.isDebugEnabled()) {
				          log.debug(format(channel, "Channel deactivated, now {} active connections and {} inactive connections"),
				                  da.pool.metrics().acquiredSize(),
				                  da.pool.metrics().idleSize());
				      }
				  });
			}
		}
	}

	static final class PendingConnectionObserver implements ConnectionObserver {
		final Queue<PendingConnectionObserver.Pending> pendingQueue = Queues.<PendingConnectionObserver.Pending>unbounded(4).get();

		@Override
		public void onStateChange(Connection connection, State newState) {
			pendingQueue.add(new PendingConnectionObserver.Pending(connection, null, newState));
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			pendingQueue.add(new PendingConnectionObserver.Pending(connection, error, null));
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

	static final class PooledConnectionAllocator {
		final ConnectionProvider parent;
		final SizeBasedAllocationStrategy allocationStrategy;
		final HttpClientConfig config;
		final int initialMaxConnection;
		final InstrumentedPool<Connection> pool;
		final Supplier<SocketAddress> remoteAddress;
		final AddressResolverGroup<?> resolver;

		PooledConnectionAllocator(
				ConnectionProvider parent,
				TransportConfig config,
				int maxConnections,
				PoolFactory<Connection> poolFactory,
				Supplier<SocketAddress> remoteAddress,
				AddressResolverGroup<?> resolver) {
			this.parent = parent;
			this.config = (HttpClientConfig) config;
			this.initialMaxConnection = maxConnections;
			this.remoteAddress = remoteAddress;
			this.resolver = resolver;
			this.allocationStrategy = new SizeBasedAllocationStrategy(0, maxConnections);
			this.pool = poolFactory.newPool(connectChannel(), new SizeBasedAllocationStrategy(0, maxConnections),
					DEFAULT_DESTROY_HANDLER, DEFAULT_EVICTION_PREDICATE);
		}

		Publisher<Connection> connectChannel() {
			return parent.acquire(config, new DelegatingConnectionObserver(), remoteAddress, resolver)
				         .map(conn -> {
				             if (log.isDebugEnabled()) {
				                 log.debug(format(conn.channel(), "Channel acquired from the parent pool, " +
				                                 "now {} active connections and {} inactive connections"),
				                         pool.metrics().acquiredSize(),
				                         pool.metrics().idleSize());
				             }

				             SslHandler handler = conn.channel().pipeline().get(SslHandler.class);
				             if (handler != null) {
				                 String protocol = handler.applicationProtocol() != null ? handler.applicationProtocol() : ApplicationProtocolNames.HTTP_1_1;
				                 if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
				                     if (allocationStrategy.compareAndSet(initialMaxConnection, Integer.MAX_VALUE)) {
				                         if (log.isDebugEnabled()) {
				                             log.debug(format(conn.channel(), "Negotiated protocol HTTP/1.1, " +
				                                     "upgrade the max connections to Integer.MAX_VALUE"));
				                         }
				                     }
				                 }
				             }

				             return conn;
				         });
		}

		static final BiPredicate<Connection, PooledRefMetadata> DEFAULT_EVICTION_PREDICATE =
				(connection, metadata) -> !connection.channel().isActive() || !connection.isPersistent();

		static final Function<Connection, Publisher<Void>> DEFAULT_DESTROY_HANDLER =
				connection -> {
					Channel channel = connection.channel();
					if (channel.isActive()) {
						Http2FrameCodec frameCodec = channel.pipeline().get(Http2FrameCodec.class);
						if (frameCodec != null && frameCodec.connection().local().numActiveStreams() == 0) {
							ChannelOperations<?, ?> ops = connection.as(ChannelOperations.class);
							if (ops != null) {
								ops.listener().onStateChange(ops, ConnectionObserver.State.DISCONNECTING);
							}
							else if (connection instanceof ConnectionObserver) {
								((ConnectionObserver) connection).onStateChange(connection, ConnectionObserver.State.DISCONNECTING);
							}
							else {
								connection.dispose();
							}
						}
					}
					return Mono.empty();
				};
	}

	static final class SizeBasedAllocationStrategy extends AtomicInteger implements AllocationStrategy {

		final int min;

		volatile int permits;
		static final AtomicIntegerFieldUpdater<SizeBasedAllocationStrategy> PERMITS =
				AtomicIntegerFieldUpdater.newUpdater(SizeBasedAllocationStrategy.class, "permits");

		SizeBasedAllocationStrategy(int min, int max) {
			super(max);
			if (min < 0) throw new IllegalArgumentException("min must be positive or zero");
			if (max < 1) throw new IllegalArgumentException("max must be strictly positive");
			if (min > max) throw new IllegalArgumentException("min must be less than or equal to max");
			this.min = min;
			PERMITS.lazySet(this, max);
		}

		@Override
		public int getPermits(int desired) {
			if (desired < 0) return 0;

			//impl note: this should be more efficient compared to the previous approach for desired == 1
			// (incrementAndGet + decrementAndGet compensation both induce a CAS loop, vs single loop here)
			for (;;) {
				int target;
				int p = permits;
				int granted = get() - p;

				if (desired >= p) {
					target = p;
				}
				else if (granted < min) {
					target = Math.max(desired, min - granted);
				}
				else {
					target = desired;
				}

				if (PERMITS.compareAndSet(this, p, p - target)) {
					return target;
				}
			}
		}

		@Override
		public int estimatePermitCount() {
			return PERMITS.get(this);
		}

		@Override
		public int permitMinimum() {
			return min;
		}

		@Override
		public int permitMaximum() {
			return get();
		}

		@Override
		public int permitGranted() {
			return get() - PERMITS.get(this);
		}

		@Override
		public void returnPermits(int returned) {
			for(;;) {
				int p = PERMITS.get(this);
				if (p + returned > get()) {
					throw new IllegalArgumentException("Too many permits returned: returned=" + returned + ", would bring to " + (p + returned) + "/" + get());
				}
				if (PERMITS.compareAndSet(this, p, p + returned)) {
					return;
				}
			}
		}
	}
}
