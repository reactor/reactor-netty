/*
 * Copyright (c) 2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
import io.netty.channel.ChannelOption;
import io.netty.handler.logging.LoggingHandler;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3ClientConnectionHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicChannelBootstrap;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamChannelBootstrap;
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
import reactor.netty.channel.ChannelMetricsRecorder;
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
import java.util.Map;
import java.util.Queue;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.getChannelContext;
import static reactor.netty.ReactorNetty.setChannelContext;
import static reactor.netty.http.client.HttpClientState.STREAM_CONFIGURED;

/**
 * An HTTP/3 implementation for pooled {@link ConnectionProvider}.
 *
 * @author Violeta Georgieva
 * @since 1.2.0
 */
final class Http3ConnectionProvider extends PooledConnectionProvider<Connection> {
	final ConnectionProvider parent;

	Http3ConnectionProvider(ConnectionProvider parent) {
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
		ChannelMetricsRecorder metricsRecorder = config.metricsRecorder() != null ? config.metricsRecorder().get() : null;
		boolean acceptGzip = false;
		Function<String, String> uriTagValue = null;
		boolean validate = true;
		if (config instanceof HttpClientConfig) {
			acceptGzip = ((HttpClientConfig) config).acceptGzip;
			uriTagValue = ((HttpClientConfig) config).uriTagValue;
			validate = ((HttpClientConfig) config).decoder.validateHeaders();
		}
		return new DisposableAcquire(acceptGzip, config.attributes(), currentContext, config.loggingHandler(),
				metricsRecorder, pendingAcquireTimeout, pool, connectionObserver, config.channelOperationsProvider(),
				config.options(), remoteAddress, sink, uriTagValue, validate);
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

	static void registerClose(Channel channel, ConnectionObserver owner) {
		channel.closeFuture().addListener(f -> invalidate(owner));
	}

	static final String CONNECTION_PROVIDER_NAME = "http3";
	static final String NAME_SEPARATOR = ".";

	static final Logger log = Loggers.getLogger(Http3ConnectionProvider.class);

	static final AttributeKey<ConnectionObserver> OWNER = AttributeKey.valueOf("http3ConnectionOwner");

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
				obs = channel.attr(OWNER).get();
				if (obs == null) {
					obs = new PendingConnectionObserver();
				}
				else {
					return obs;
				}
				if (channel.attr(OWNER).compareAndSet(null, obs)) {
					return obs;
				}
			}
		}
	}

	static final class DisposableAcquire
			implements CoreSubscriber<PooledRef<Connection>>, ConnectionObserver, Disposable, GenericFutureListener<Future<QuicStreamChannel>> {
		final boolean acceptGzip;
		final Map<AttributeKey<?>, ?> attributes;
		final Disposable.Composite cancellations;
		final Context currentContext;
		final LoggingHandler loggingHandler;
		final ChannelMetricsRecorder metricsRecorder;
		final long pendingAcquireTimeout;
		final InstrumentedPool<Connection> pool;
		final ConnectionObserver obs;
		final ChannelOperations.OnSetup opsFactory;
		final Map<ChannelOption<?>, ?> options;
		final boolean retried;
		final MonoSink<Connection> sink;
		final Function<String, String> uriTagValue;
		final boolean validate;

		PooledRef<Connection> pooledRef;
		SocketAddress remoteAddress;
		Subscription subscription;

		DisposableAcquire(
				boolean acceptGzip,
				Map<AttributeKey<?>, ?> attributes,
				Context currentContext,
				@Nullable LoggingHandler loggingHandler,
				@Nullable ChannelMetricsRecorder metricsRecorder,
				long pendingAcquireTimeout,
				InstrumentedPool<Connection> pool,
				ConnectionObserver obs,
				ChannelOperations.OnSetup opsFactory,
				Map<ChannelOption<?>, ?> options,
				@Nullable SocketAddress remoteAddress,
				MonoSink<Connection> sink,
				@Nullable Function<String, String> uriTagValue,
				boolean validate) {
			this.acceptGzip = acceptGzip;
			this.attributes = attributes;
			this.cancellations = Disposables.composite();
			this.currentContext = currentContext;
			this.loggingHandler = loggingHandler;
			this.metricsRecorder = metricsRecorder;
			this.pendingAcquireTimeout = pendingAcquireTimeout;
			this.pool = pool;
			this.obs = obs;
			this.opsFactory = opsFactory;
			this.options = options;
			this.remoteAddress = remoteAddress;
			this.retried = false;
			this.sink = sink;
			this.uriTagValue = uriTagValue;
			this.validate = validate;
		}

		DisposableAcquire(DisposableAcquire parent) {
			this.acceptGzip = parent.acceptGzip;
			this.attributes = parent.attributes;
			this.cancellations = parent.cancellations;
			this.currentContext = parent.currentContext;
			this.loggingHandler = parent.loggingHandler;
			this.metricsRecorder = parent.metricsRecorder;
			this.pendingAcquireTimeout = parent.pendingAcquireTimeout;
			this.pool = parent.pool;
			this.obs = parent.obs;
			this.opsFactory = parent.opsFactory;
			this.options = parent.options;
			this.remoteAddress = parent.remoteAddress;
			this.retried = true;
			this.sink = parent.sink;
			this.uriTagValue = parent.uriTagValue;
			this.validate = parent.validate;
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

			ConnectionObserver current = channel.attr(OWNER).getAndSet(this);

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

			if (getChannelContext(channel) != null) {
				setChannelContext(channel, null);
			}

			QuicStreamChannelBootstrap bootstrap =
					Http3.newRequestStreamBootstrap((QuicChannel) channel,
							new Http3Codec(obs, opsFactory, acceptGzip, loggingHandler, metricsRecorder, remoteAddress, uriTagValue, validate));
			attributes(bootstrap, attributes);
			channelOptions(bootstrap, options);
			bootstrap.create().addListener(this);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
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
		public void operationComplete(Future<QuicStreamChannel> future) {
			if (future.isSuccess()) {
				Channel channel = pooledRef.poolable().channel();
				Http2Pool.Http2PooledRef http2PooledRef = http2PooledRef(pooledRef);
				ChannelHandlerContext http3ClientConnectionHandlerCtx =
						((Http3Pool.Slot) http2PooledRef.slot).http3ClientConnectionHandlerCtx();
				QuicStreamChannel ch = future.getNow();

				if (!channel.isActive() || http3ClientConnectionHandlerCtx == null ||
						((Http3ClientConnectionHandler) http3ClientConnectionHandlerCtx.handler()).isGoAwayReceived()) {
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
					registerClose(ch, this);
					if (!currentContext().isEmpty()) {
						setChannelContext(ch, currentContext());
					}

					ChannelOperations<?, ?> ops = ChannelOperations.get(ch);
					if (ops != null) {
						obs.onStateChange(ops, STREAM_CONFIGURED);
						sink.success(ops);
					}
				}
			}
			else {
				invalidate(this);
				sink.error(future.cause());
			}
		}

		@SuppressWarnings("unchecked")
		static void attributes(QuicStreamChannelBootstrap bootstrap, Map<AttributeKey<?>, ?> attrs) {
			for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
				bootstrap.attr((AttributeKey<Object>) e.getKey(), e.getValue());
			}
		}
		@SuppressWarnings("unchecked")
		static void channelOptions(QuicStreamChannelBootstrap bootstrap, Map<ChannelOption<?>, ?> options) {
			for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
				bootstrap.option((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}

		static Http2Pool.Http2PooledRef http2PooledRef(PooledRef<Connection> pooledRef) {
			return pooledRef instanceof Http2Pool.Http2PooledRef ?
					(Http2Pool.Http2PooledRef) pooledRef :
					(Http2Pool.Http2PooledRef) pooledRef.metadata();
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
							poolConfig -> new Http3Pool(poolConfig, poolFactory.allocationStrategy())) :
					poolFactory.newPool(connectChannel(), DEFAULT_DESTROY_HANDLER, DEFAULT_EVICTION_PREDICATE,
							new MicrometerPoolMetricsRecorder(id, name, remoteAddress),
							poolConfig -> new Http3Pool(poolConfig, poolFactory.allocationStrategy()));
		}

		Publisher<Connection> connectChannel() {
			return parent.acquire(config, new DelegatingConnectionObserver(), () -> remoteAddress, resolver)
					.flatMap(conn ->
						Mono.create(sink -> {
							Channel channel = conn.channel();

							channel.pipeline().remove(NettyPipeline.ReactiveBridge);

							Http3ChannelInitializer.HttpTrafficHandler initializer =
									channel.pipeline().remove(Http3ChannelInitializer.HttpTrafficHandler.class);

							QuicChannelBootstrap bootstrap =
									QuicChannel.newBootstrap(channel)
									           .handler(initializer.quicChannelInitializer)
									           .remoteAddress(remoteAddress);
							attributes(bootstrap, config.attributes());
							channelOptions(bootstrap, config.options());
							bootstrap.option(ChannelOption.AUTO_READ, true);
							bootstrap.connect()
							         .addListener(f -> {
							             if (!f.isSuccess()) {
							                 sink.error(f.cause());
							             }
							             else {
							                 sink.success(Connection.from((Channel) f.get()));
							             }
							         });
						}));
		}

		@SuppressWarnings("unchecked")
		static void attributes(QuicChannelBootstrap bootstrap, Map<AttributeKey<?>, ?> attrs) {
			for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
				bootstrap.attr((AttributeKey<Object>) e.getKey(), e.getValue());
			}
		}

		@SuppressWarnings("unchecked")
		static void channelOptions(QuicChannelBootstrap bootstrap, Map<ChannelOption<?>, ?> options) {
			for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
				bootstrap.option((ChannelOption<Object>) e.getKey(), e.getValue());
			}
		}

		static final BiPredicate<Connection, PooledRefMetadata> DEFAULT_EVICTION_PREDICATE =
				(connection, metadata) -> false;

		static final Function<Connection, Publisher<Void>> DEFAULT_DESTROY_HANDLER = connection -> Mono.empty();
	}
}
