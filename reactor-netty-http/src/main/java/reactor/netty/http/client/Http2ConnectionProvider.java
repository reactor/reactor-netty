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
package reactor.netty.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2LocalFlowController;
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
import reactor.netty.internal.shaded.reactor.pool.InstrumentedPool;
import reactor.netty.internal.shaded.reactor.pool.PooledRef;
import reactor.netty.internal.shaded.reactor.pool.PooledRefMetadata;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Queue;
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

	Http2ConnectionProvider(ConnectionProvider parent, Builder builder) {
		super(builder);
		this.parent = parent;
	}

	@Override
	protected CoreSubscriber<PooledRef<Connection>> createDisposableAcquire(
			TransportConfig config,
			ConnectionObserver connectionObserver,
			long pendingAcquireTimeout,
			InstrumentedPool<Connection> pool,
			MonoSink<Connection> sink) {
		boolean acceptGzip = config instanceof HttpClientConfig && ((HttpClientConfig) config).acceptGzip;
		return new DisposableAcquire(connectionObserver, config.channelOperationsProvider(),
				acceptGzip, pendingAcquireTimeout, pool, sink);
	}

	@Override
	protected InstrumentedPool<Connection> createPool(
			TransportConfig config,
			PooledConnectionProvider.PoolFactory<Connection> poolFactory,
			SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup) {
		return new PooledConnectionAllocator(parent, config, poolFactory, () -> remoteAddress, resolverGroup).pool;
	}

	static void invalidate(@Nullable ConnectionObserver owner, Channel channel) {
		if (owner instanceof DisposableAcquire) {
			DisposableAcquire da = (DisposableAcquire) owner;
			da.pooledRef
			  .invalidate()
			  .subscribe(null, null, () -> {
			      if (log.isDebugEnabled()) {
			          logPoolState(channel, da.pool, "Channel removed from the http2 pool");
			      }
			  });
		}
	}

	static void logStreamsState(Channel channel, Http2Connection.Endpoint<Http2LocalFlowController> localEndpoint, String msg) {
		log.debug(format(channel, "{}, now: {} active streams and {} max active streams."),
				msg,
				localEndpoint.numActiveStreams(),
				localEndpoint.maxActiveStreams());
	}

	static void registerClose(Channel channel) {
		ConnectionObserver owner = channel.parent().attr(OWNER).get();
		channel.closeFuture()
		       .addListener(f -> {
		           Channel parent = channel.parent();
		           Http2FrameCodec frameCodec = parent.pipeline().get(Http2FrameCodec.class);
		           Http2Connection.Endpoint<Http2LocalFlowController> localEndpoint = frameCodec.connection().local();
		           if (log.isDebugEnabled()) {
		               logStreamsState(channel, localEndpoint, "Stream closed");
		           }

		           if (localEndpoint.numActiveStreams() == 0) {
		               channel.parent().attr(OWNER).set(null);
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

	static final class DisposableAcquire
			implements CoreSubscriber<PooledRef<Connection>>, ConnectionObserver, Disposable, GenericFutureListener<Future<Http2StreamChannel>> {
		final Disposable.Composite cancellations;
		final ConnectionObserver obs;
		final ChannelOperations.OnSetup opsFactory;
		final boolean acceptGzip;
		final long pendingAcquireTimeout;
		final InstrumentedPool<Connection> pool;
		final boolean retried;
		final MonoSink<Connection> sink;

		PooledRef<Connection> pooledRef;
		Subscription subscription;

		DisposableAcquire(
				ConnectionObserver obs,
				ChannelOperations.OnSetup opsFactory,
				boolean acceptGzip,
				long pendingAcquireTimeout,
				InstrumentedPool<Connection> pool,
				MonoSink<Connection> sink) {
			this.cancellations = Disposables.composite();
			this.obs = obs;
			this.opsFactory = opsFactory;
			this.acceptGzip = acceptGzip;
			this.pendingAcquireTimeout = pendingAcquireTimeout;
			this.pool = pool;
			this.retried = false;
			this.sink = sink;
		}

		DisposableAcquire(DisposableAcquire parent) {
			this.cancellations = parent.cancellations;
			this.obs = parent.obs;
			this.opsFactory = parent.opsFactory;
			this.acceptGzip = parent.acceptGzip;
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
				logPoolState(channel, pool, "Channel activated");
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

			HttpClientConfig.openStream(channel, obs, opsFactory, acceptGzip)
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
							log.debug(format(ch, "Immediately aborted pooled channel, max active streams is reached, " +
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

					Http2Connection.Endpoint<Http2LocalFlowController> localEndpoint = frameCodec.connection().local();
					if (log.isDebugEnabled()) {
						logStreamsState(ch, localEndpoint, "Stream opened");
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
			ChannelPipeline pipeline = channel.pipeline();
			SslHandler handler = pipeline.get(SslHandler.class);
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
			else if (pipeline.get(NettyPipeline.H2CUpgradeHandler) == null &&
					pipeline.get(NettyPipeline.H2MultiplexHandler) == null) {
				// It is not H2. There are no handlers for H2C upgrade/H2C prior-knowledge,
				// continue as an HTTP/1.1 request and remove the connection from this pool.
				ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
				if (ops != null) {
					sink.success(ops);
					invalidate(this, channel);
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
				DisposableAcquire da = (DisposableAcquire) owner;
				da.pooledRef
				  .release()
				  .subscribe(null, null, () -> {
				      if (log.isDebugEnabled()) {
				          logPoolState(channel, da.pool, "Channel deactivated");
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
		final HttpClientConfig config;
		final InstrumentedPool<Connection> pool;
		final Supplier<SocketAddress> remoteAddress;
		final AddressResolverGroup<?> resolver;

		PooledConnectionAllocator(
				ConnectionProvider parent,
				TransportConfig config,
				PoolFactory<Connection> poolFactory,
				Supplier<SocketAddress> remoteAddress,
				AddressResolverGroup<?> resolver) {
			this.parent = parent;
			this.config = (HttpClientConfig) config;
			this.remoteAddress = remoteAddress;
			this.resolver = resolver;
			this.pool = poolFactory.newPool(connectChannel(), null, DEFAULT_DESTROY_HANDLER, DEFAULT_EVICTION_PREDICATE);
		}

		Publisher<Connection> connectChannel() {
			return parent.acquire(config, new DelegatingConnectionObserver(), remoteAddress, resolver)
				         .map(conn -> {
				             if (log.isDebugEnabled()) {
				                 logPoolState(conn.channel(), pool, "Channel acquired from the parent pool");
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
}
