/*
 * Copyright (c) 2020-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolNames;
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
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.PooledConnectionProvider;
import reactor.netty.transport.ClientTransportConfig;
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

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.getChannelContext;
import static reactor.netty.ReactorNetty.setChannelContext;
import static reactor.netty.http.client.HttpClientState.STREAM_CONFIGURED;
import static reactor.netty.http.client.HttpClientState.UPGRADE_REJECTED;

/**
 * An HTTP/2 implementation for pooled {@link ConnectionProvider}.
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class Http2ConnectionProvider extends PooledConnectionProvider<Connection> {
	final ConnectionProvider parent;

	Http2ConnectionProvider(ConnectionProvider parent) {
		super(initConfiguration(parent));
		this.parent = parent;
		if (parent instanceof PooledConnectionProvider) {
			((PooledConnectionProvider<?>) parent).onDispose(disposeLater());
		}
	}

	static Builder initConfiguration(ConnectionProvider parent) {
		String name = parent.name() == null ? CONNECTION_PROVIDER_NAME : CONNECTION_PROVIDER_NAME + NAME_SEPARATOR + parent.name();
		Builder builder = parent.mutate();
		if (builder != null) {
			return builder.name(name).pendingAcquireMaxCount(-1);
		}
		else {
			// this is the case when there is no pool
			// only one connection is created and used for all requests
			return ConnectionProvider.builder(name)
			                         .maxConnections(parent.maxConnections())
			                         .pendingAcquireMaxCount(-1);
		}
	}

	@Override
	protected CoreSubscriber<PooledRef<Connection>> createDisposableAcquire(
			TransportConfig config,
			ConnectionObserver connectionObserver,
			long pendingAcquireTimeout,
			InstrumentedPool<Connection> pool,
			MonoSink<Connection> sink,
			Context currentContext) {
		return createDisposableAcquire(config, connectionObserver, pendingAcquireTimeout, pool, null, sink, currentContext);
	}

	@Override
	protected CoreSubscriber<PooledRef<Connection>> createDisposableAcquire(
			TransportConfig config,
			ConnectionObserver connectionObserver,
			long pendingAcquireTimeout,
			InstrumentedPool<Connection> pool,
			SocketAddress remoteAddress,
			MonoSink<Connection> sink,
			Context currentContext) {
		boolean acceptGzip = false;
		boolean acceptBrotli = false;
		ChannelMetricsRecorder metricsRecorder = config.metricsRecorder() != null ? config.metricsRecorder().get() : null;
		SocketAddress proxyAddress = ((ClientTransportConfig<?>) config).proxyProvider() != null ?
				((ClientTransportConfig<?>) config).proxyProvider().getSocketAddress().get() : null;
		Function<String, String> uriTagValue = null;
		if (config instanceof HttpClientConfig) {
			acceptGzip = ((HttpClientConfig) config).acceptGzip;
			acceptBrotli = ((HttpClientConfig) config).acceptBrotli;
			uriTagValue = ((HttpClientConfig) config).uriTagValue;
		}
		return new DisposableAcquire(connectionObserver, config.channelOperationsProvider(),
				acceptGzip, acceptBrotli, metricsRecorder, pendingAcquireTimeout, pool, proxyAddress, remoteAddress, sink, currentContext, uriTagValue);
	}

	@Override
	protected InstrumentedPool<Connection> createPool(
			TransportConfig config,
			PooledConnectionProvider.PoolFactory<Connection> poolFactory,
			SocketAddress remoteAddress,
			@Nullable AddressResolverGroup<?> resolverGroup) {
		return new PooledConnectionAllocator(parent, config, poolFactory, remoteAddress, resolverGroup).pool;
	}

	@Override
	protected InstrumentedPool<Connection> createPool(
			String id,
			TransportConfig config,
			PooledConnectionProvider.PoolFactory<Connection> poolFactory,
			SocketAddress remoteAddress,
			@Nullable AddressResolverGroup<?> resolverGroup) {
		return new PooledConnectionAllocator(id, name(), parent, config, poolFactory, remoteAddress, resolverGroup).pool;
	}

	@Override
	protected void registerDefaultMetrics(String id, SocketAddress remoteAddress, InstrumentedPool.PoolMetrics metrics) {
		MicrometerHttp2ConnectionProviderMeterRegistrar.INSTANCE
				.registerMetrics(name(), id, remoteAddress, metrics);
	}

	@Override
	protected void deRegisterDefaultMetrics(String id, SocketAddress remoteAddress) {
		MicrometerHttp2ConnectionProviderMeterRegistrar.INSTANCE
				.deRegisterMetrics(name(), id, remoteAddress);
	}

	static void invalidate(@Nullable ConnectionObserver owner) {
		if (owner instanceof DisposableAcquire) {
			DisposableAcquire da = (DisposableAcquire) owner;
			da.pooledRef
			  .invalidate()
			  .subscribe();
		}
	}

	static void logStreamsState(Channel channel, Http2Connection.Endpoint<Http2LocalFlowController> localEndpoint, String msg) {
		log.debug(format(channel, "{}, now: {} active streams and {} max active streams."),
				msg,
				localEndpoint.numActiveStreams(),
				localEndpoint.maxActiveStreams());
	}

	static void registerClose(Channel channel, ConnectionObserver owner) {
		channel.closeFuture()
		       .addListener(f -> {
		           if (log.isDebugEnabled()) {
		               Http2FrameCodec frameCodec = channel.parent().pipeline().get(Http2FrameCodec.class);
		               if (frameCodec != null) {
		                   Http2Connection.Endpoint<Http2LocalFlowController> localEndpoint = frameCodec.connection().local();
		                   logStreamsState(channel, localEndpoint, "Stream closed");
		               }
		           }

		           invalidate(owner);
		       });
	}

	static final String CONNECTION_PROVIDER_NAME = "http2";
	static final String NAME_SEPARATOR = ".";

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
		final Context currentContext;
		final ConnectionObserver obs;
		final ChannelOperations.OnSetup opsFactory;
		final boolean acceptGzip;
		final boolean acceptBrotli;
		final ChannelMetricsRecorder metricsRecorder;
		final long pendingAcquireTimeout;
		final InstrumentedPool<Connection> pool;
		final SocketAddress proxyAddress;
		final boolean retried;
		final MonoSink<Connection> sink;
		final Function<String, String> uriTagValue;

		PooledRef<Connection> pooledRef;
		SocketAddress remoteAddress;
		Subscription subscription;

		DisposableAcquire(
				ConnectionObserver obs,
				ChannelOperations.OnSetup opsFactory,
				boolean acceptGzip,
				boolean acceptBrotli,
				@Nullable ChannelMetricsRecorder metricsRecorder,
				long pendingAcquireTimeout,
				InstrumentedPool<Connection> pool,
				@Nullable SocketAddress proxyAddress,
				@Nullable SocketAddress remoteAddress,
				MonoSink<Connection> sink,
				Context currentContext,
				@Nullable Function<String, String> uriTagValue) {
			this.cancellations = Disposables.composite();
			this.currentContext = currentContext;
			this.obs = obs;
			this.opsFactory = opsFactory;
			this.acceptGzip = acceptGzip;
			this.acceptBrotli = acceptBrotli;
			this.metricsRecorder = metricsRecorder;
			this.pendingAcquireTimeout = pendingAcquireTimeout;
			this.pool = pool;
			this.proxyAddress = proxyAddress;
			this.remoteAddress = remoteAddress;
			this.retried = false;
			this.sink = sink;
			this.uriTagValue = uriTagValue;
		}

		DisposableAcquire(DisposableAcquire parent) {
			this.cancellations = parent.cancellations;
			this.currentContext = parent.currentContext;
			this.obs = parent.obs;
			this.opsFactory = parent.opsFactory;
			this.acceptGzip = parent.acceptGzip;
			this.acceptBrotli = parent.acceptBrotli;
			this.metricsRecorder = parent.metricsRecorder;
			this.pendingAcquireTimeout = parent.pendingAcquireTimeout;
			this.pool = parent.pool;
			this.proxyAddress = parent.proxyAddress;
			this.remoteAddress = parent.remoteAddress;
			this.retried = true;
			this.sink = parent.sink;
			this.uriTagValue = parent.uriTagValue;
		}

		@Override
		public Context currentContext() {
			return currentContext;
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

			if (remoteAddress == null) {
				// This can happen only if there is a custom implementation of PooledConnectionProvider.createDisposableAcquire(...),
				// with the default implementation, remoteAddress is always initialized.
				remoteAddress = channel.remoteAddress();
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

			if (getChannelContext(channel) != null) {
				setChannelContext(channel, null);
			}
			http2StreamChannelBootstrap(channel).open().addListener(this);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == UPGRADE_REJECTED) {
				invalidate(connection.channel().attr(OWNER).get());
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
			if (future.isSuccess()) {
				Channel channel = pooledRef.poolable().channel();
				Http2Pool.Http2PooledRef http2PooledRef = http2PooledRef(pooledRef);
				ChannelHandlerContext frameCodec = http2PooledRef.slot.http2FrameCodecCtx();
				Http2StreamChannel ch = future.getNow();

				if (!channel.isActive() || frameCodec == null ||
						((Http2FrameCodec) frameCodec.handler()).connection().goAwayReceived() ||
						!((Http2FrameCodec) frameCodec.handler()).connection().local().canOpenStream()) {
					invalidate(this);
					if (!retried) {
						if (log.isDebugEnabled()) {
							log.debug(format(ch, "Immediately aborted pooled channel, max active streams is reached, " +
								"re-acquiring a new channel"));
						}
						pool.acquire(Duration.ofMillis(pendingAcquireTimeout))
						    .contextWrite(ctx -> ctx.put(CONTEXT_CALLER_EVENTLOOP, channel.eventLoop()))
						    .subscribe(new DisposableAcquire(this));
					}
					else {
						sink.error(new IOException("Error while acquiring from " + pool + ". Max active streams is reached."));
					}
				}
				else {
					Http2ConnectionProvider.registerClose(ch, this);
					if (!currentContext().isEmpty()) {
						setChannelContext(ch, currentContext());
					}
					HttpClientConfig.addStreamHandlers(ch, obs.then(new HttpClientConfig.StreamConnectionObserver(currentContext())),
							opsFactory, acceptGzip, acceptBrotli, metricsRecorder, proxyAddress, remoteAddress, -1, uriTagValue);

					ChannelOperations<?, ?> ops = ChannelOperations.get(ch);
					if (ops != null) {
						obs.onStateChange(ops, STREAM_CONFIGURED);
						sink.success(ops);
					}

					if (log.isDebugEnabled()) {
						Http2Connection.Endpoint<Http2LocalFlowController> localEndpoint = ((Http2FrameCodec) frameCodec.handler()).connection().local();
						logStreamsState(ch, localEndpoint, "Stream opened");
					}
				}
			}
			else {
				invalidate(this);
				sink.error(future.cause());
			}
		}

		boolean isH2cUpgrade() {
			Channel channel = pooledRef.poolable().channel();
			Http2Pool.Http2PooledRef http2PooledRef = http2PooledRef(pooledRef);
			if (http2PooledRef.slot.h2cUpgradeHandlerCtx() != null &&
					http2PooledRef.slot.http2MultiplexHandlerCtx() == null) {
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
			Http2Pool.Http2PooledRef http2PooledRef = http2PooledRef(pooledRef);
			String applicationProtocol = http2PooledRef.slot.applicationProtocol;
			if (applicationProtocol != null) {
				if (ApplicationProtocolNames.HTTP_1_1.equals(applicationProtocol)) {
					// No information for the negotiated application-level protocol,
					// or it is HTTP/1.1, continue as an HTTP/1.1 request
					// and remove the connection from this pool.
					ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
					if (ops != null) {
						sink.success(ops);
						invalidate(this);
						return true;
					}
				}
				else if (!ApplicationProtocolNames.HTTP_2.equals(applicationProtocol)) {
					channel.attr(OWNER).set(null);
					invalidate(this);
					sink.error(new IOException("Unknown protocol [" + applicationProtocol + "]."));
					return true;
				}
			}
			else if (http2PooledRef.slot.h2cUpgradeHandlerCtx() == null &&
					http2PooledRef.slot.http2MultiplexHandlerCtx() == null) {
				// It is not H2. There are no handlers for H2C upgrade/H2C prior-knowledge,
				// continue as an HTTP/1.1 request and remove the connection from this pool.
				ChannelOperations<?, ?> ops = ChannelOperations.get(channel);
				if (ops != null) {
					sink.success(ops);
					invalidate(this);
					return true;
				}
			}
			return false;
		}

		static Http2Pool.Http2PooledRef http2PooledRef(PooledRef<Connection> pooledRef) {
			return pooledRef instanceof Http2Pool.Http2PooledRef ?
					(Http2Pool.Http2PooledRef) pooledRef :
					(Http2Pool.Http2PooledRef) pooledRef.metadata();
		}

		static final AttributeKey<Http2StreamChannelBootstrap> HTTP2_STREAM_CHANNEL_BOOTSTRAP =
				AttributeKey.valueOf("http2StreamChannelBootstrap");

		static Http2StreamChannelBootstrap http2StreamChannelBootstrap(Channel channel) {
			Http2StreamChannelBootstrap http2StreamChannelBootstrap;

			for (;;) {
				http2StreamChannelBootstrap = channel.attr(HTTP2_STREAM_CHANNEL_BOOTSTRAP).get();
				if (http2StreamChannelBootstrap == null) {
					http2StreamChannelBootstrap = new Http2StreamChannelBootstrap(channel);
				}
				else {
					return http2StreamChannelBootstrap;
				}
				if (channel.attr(HTTP2_STREAM_CHANNEL_BOOTSTRAP)
						.compareAndSet(null, http2StreamChannelBootstrap)) {
					return http2StreamChannelBootstrap;
				}
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
		final SocketAddress remoteAddress;
		final AddressResolverGroup<?> resolver;

		PooledConnectionAllocator(
				ConnectionProvider parent,
				TransportConfig config,
				PoolFactory<Connection> poolFactory,
				SocketAddress remoteAddress,
				@Nullable AddressResolverGroup<?> resolver) {
			this(null, null, parent, config, poolFactory, remoteAddress, resolver);
		}

		PooledConnectionAllocator(
				@Nullable String id,
				@Nullable String name,
				ConnectionProvider parent,
				TransportConfig config,
				PoolFactory<Connection> poolFactory,
				SocketAddress remoteAddress,
				@Nullable AddressResolverGroup<?> resolver) {
			this.parent = parent;
			this.config = (HttpClientConfig) config;
			this.remoteAddress = remoteAddress;
			this.resolver = resolver;
			this.pool = id == null ?
					poolFactory.newPool(connectChannel(), null, DEFAULT_DESTROY_HANDLER, DEFAULT_EVICTION_PREDICATE,
							poolConfig -> new Http2Pool(poolConfig, poolFactory.allocationStrategy())) :
					poolFactory.newPool(connectChannel(), DEFAULT_DESTROY_HANDLER, DEFAULT_EVICTION_PREDICATE,
							new MicrometerPoolMetricsRecorder(id, name, remoteAddress),
							poolConfig -> new Http2Pool(poolConfig, poolFactory.allocationStrategy()));
		}

		Publisher<Connection> connectChannel() {
			return parent.acquire(config, new DelegatingConnectionObserver(), () -> remoteAddress, resolver)
				         .map(conn -> conn);
		}

		static final BiPredicate<Connection, PooledRefMetadata> DEFAULT_EVICTION_PREDICATE =
				(connection, metadata) -> false;

		static final Function<Connection, Publisher<Void>> DEFAULT_DESTROY_HANDLER = connection -> Mono.empty();
	}
}
