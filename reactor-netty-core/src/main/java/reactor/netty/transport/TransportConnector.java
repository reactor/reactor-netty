/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.transport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;
import reactor.util.context.ContextView;
import reactor.util.retry.Retry;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static reactor.netty.ReactorNetty.format;
import static reactor.netty.ReactorNetty.setChannelContext;

/**
 * {@link TransportConnector} is a helper class that creates, initializes and registers the channel.
 * It performs the actual connect operation to the remote peer or binds the channel.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 * @since 1.0.0
 */
public final class TransportConnector {

	TransportConnector() {}

	/**
	 * Binds a {@link Channel}.
	 *
	 * @param config the transport configuration
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @param bindAddress the local address
	 * @param isDomainSocket true if {@link io.netty.channel.unix.DomainSocketChannel} or
	 * {@link io.netty.channel.unix.ServerDomainSocketChannel} is needed, false otherwise
	 * @return a {@link Mono} of {@link Channel}
	 */
	@SuppressWarnings("FutureReturnValueIgnored")
	public static Mono<Channel> bind(TransportConfig config, ChannelInitializer<Channel> channelInitializer,
			SocketAddress bindAddress, boolean isDomainSocket) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(bindAddress, "bindAddress");
		Objects.requireNonNull(channelInitializer, "channelInitializer");

		return doInitAndRegister(config, channelInitializer, isDomainSocket, config.eventLoopGroup().next())
				.flatMap(channel -> {
					MonoChannelPromise promise = new MonoChannelPromise(channel);
					// "FutureReturnValueIgnored" this is deliberate
					channel.eventLoop().execute(() -> channel.bind(bindAddress, promise.unvoid()));
					return promise;
				});
	}

	/**
	 * Connect a {@link Channel} to the remote peer.
	 *
	 * @param config the transport configuration
	 * @param remoteAddress the {@link SocketAddress} to connect to
	 * @param resolverGroup the resolver which will resolve the address of the unresolved named address
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @return a {@link Mono} of {@link Channel}
	 */
	public static Mono<Channel> connect(TransportConfig config, SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup, ChannelInitializer<Channel> channelInitializer) {
		return connect(config, remoteAddress, resolverGroup, channelInitializer, config.eventLoopGroup().next(), Context.empty());
	}

	/**
	 * Connect a {@link Channel} to the remote peer.
	 *
	 * @param config the transport configuration
	 * @param remoteAddress the {@link SocketAddress} to connect to
	 * @param resolverGroup the resolver which will resolve the address of the unresolved named address
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @param contextView the current {@link ContextView}
	 * @return a {@link Mono} of {@link Channel}
	 * @since 1.0.26
	 */
	public static Mono<Channel> connect(TransportConfig config, SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup, ChannelInitializer<Channel> channelInitializer, ContextView contextView) {
		return connect(config, remoteAddress, resolverGroup, channelInitializer, config.eventLoopGroup().next(), contextView);
	}

	/**
	 * Connect a {@link Channel} to the remote peer.
	 *
	 * @param config the transport configuration
	 * @param remoteAddress the {@link SocketAddress} to connect to
	 * @param resolverGroup the resolver which will resolve the address of the unresolved named address
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @param eventLoop the {@link EventLoop} to use for handling the channel.
	 * @return a {@link Mono} of {@link Channel}
	 */
	public static Mono<Channel> connect(TransportConfig config, SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup, ChannelInitializer<Channel> channelInitializer, EventLoop eventLoop) {
		return connect(config, remoteAddress, resolverGroup, channelInitializer, eventLoop, Context.empty());
	}

	/**
	 * Connect a {@link Channel} to the remote peer.
	 *
	 * @param config the transport configuration
	 * @param remoteAddress the {@link SocketAddress} to connect to
	 * @param resolverGroup the resolver which will resolve the address of the unresolved named address
	 * @param channelInitializer the {@link ChannelInitializer} that will be used for initializing the channel pipeline
	 * @param eventLoop the {@link EventLoop} to use for handling the channel.
	 * @param contextView the current {@link ContextView}
	 * @return a {@link Mono} of {@link Channel}
	 * @since 1.0.26
	 */
	public static Mono<Channel> connect(TransportConfig config, SocketAddress remoteAddress,
			AddressResolverGroup<?> resolverGroup, ChannelInitializer<Channel> channelInitializer, EventLoop eventLoop,
			ContextView contextView) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(remoteAddress, "remoteAddress");
		Objects.requireNonNull(resolverGroup, "resolverGroup");
		Objects.requireNonNull(channelInitializer, "channelInitializer");
		Objects.requireNonNull(eventLoop, "eventLoop");
		Objects.requireNonNull(contextView, "contextView");

		boolean isDomainAddress = remoteAddress instanceof DomainSocketAddress;
		return doInitAndRegister(config, channelInitializer, isDomainAddress, eventLoop)
				.flatMap(channel -> doResolveAndConnect(channel, config, remoteAddress, resolverGroup, contextView)
						.onErrorResume(RetryConnectException.class,
								t -> {
									AtomicInteger index = new AtomicInteger(1);
									return Mono.defer(() ->
											doInitAndRegister(config, channelInitializer, isDomainAddress, eventLoop)
													.flatMap(ch -> {
														MonoChannelPromise mono = new MonoChannelPromise(ch);
														doConnect(t.addresses, config.bindAddress(), mono, index.get());
														return mono;
													}))
											.retryWhen(Retry.max(t.addresses.size() - 1)
															.filter(RETRY_PREDICATE)
															.doBeforeRetry(sig -> index.incrementAndGet()));
								}));
	}

	/**
	 * Set the channel attributes.
	 *
	 * @param channel the channel
	 * @param attrs the attributes
	 */
	@SuppressWarnings("unchecked")
	static void setAttributes(Channel channel, Map<AttributeKey<?>, ?> attrs) {
		for (Map.Entry<AttributeKey<?>, ?> e : attrs.entrySet()) {
			channel.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
		}
	}

	/**
	 * Set the channel options.
	 *
	 * @param channel the channel
	 * @param options the options
	 */
	@SuppressWarnings("unchecked")
	static void setChannelOptions(Channel channel, Map<ChannelOption<?>, ?> options, boolean isDomainSocket) {
		for (Map.Entry<ChannelOption<?>, ?> e : options.entrySet()) {
			if (isDomainSocket &&
					(ChannelOption.SO_REUSEADDR.equals(e.getKey()) || ChannelOption.TCP_NODELAY.equals(e.getKey()))) {
				continue;
			}
			try {
				if (!channel.config().setOption((ChannelOption<Object>) e.getKey(), e.getValue())) {
					if (log.isWarnEnabled()) {
						log.warn(format(channel, "Unknown channel option '{}' for channel '{}'"), e.getKey(), channel);
					}
				}
			}
			catch (Throwable t) {
				if (log.isWarnEnabled()) {
					log.warn(format(channel, "Failed to set channel option '{}' with value '{}' for channel '{}'"),
							e.getKey(), e.getValue(), channel, t);
				}
			}
		}
	}

	static void doConnect(
			List<SocketAddress> addresses,
			@Nullable Supplier<? extends SocketAddress> bindAddress,
			MonoChannelPromise connectPromise,
			int index) {
		Channel channel = connectPromise.channel();
		channel.eventLoop().execute(() -> {
			SocketAddress remoteAddress = addresses.get(index);

			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Connecting to [" + remoteAddress + "]."));
			}

			ChannelFuture f;
			if (bindAddress == null) {
				f = channel.connect(remoteAddress);
			}
			else {
				SocketAddress local = Objects.requireNonNull(bindAddress.get(), "bindAddress");
				f = channel.connect(remoteAddress, local);
			}

			f.addListener(future -> {
				if (future.isSuccess()) {
					connectPromise.setSuccess();
				}
				else {
					Throwable cause = future.cause();
					if (log.isDebugEnabled()) {
						log.debug(format(channel, "Connect attempt to [" + remoteAddress + "] failed."), cause);
					}

					int next = index + 1;
					if (next < addresses.size()) {
						connectPromise.setFailure(new RetryConnectException(addresses));
					}
					else {
						connectPromise.setFailure(cause);
					}
				}
			});
		});
	}

	static Mono<Channel> doInitAndRegister(
			TransportConfig config,
			ChannelInitializer<Channel> channelInitializer,
			boolean isDomainSocket,
			EventLoop eventLoop) {
		ChannelFactory<? extends Channel> channelFactory = config.connectionFactory(config.eventLoopGroup(), isDomainSocket);

		Channel channel = null;
		try {
			channel = channelFactory.newChannel();
			if (channelInitializer instanceof ServerTransport.AcceptorInitializer) {
				((ServerTransport.AcceptorInitializer) channelInitializer).acceptor.enableAutoReadTask(channel);
			}
			channel.pipeline().addLast(channelInitializer);
			setChannelOptions(channel, config.options, isDomainSocket);
			setAttributes(channel, config.attrs);
		}
		catch (Throwable t) {
			if (channel != null) {
				channel.unsafe().closeForcibly();
			}
			return Mono.error(t);
		}

		MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
		channel.unsafe().register(eventLoop, monoChannelPromise);
		return monoChannelPromise;
	}

	@SuppressWarnings({"unchecked", "FutureReturnValueIgnored", "try"})
	static Mono<Channel> doResolveAndConnect(Channel channel, TransportConfig config,
			SocketAddress remoteAddress, AddressResolverGroup<?> resolverGroup, ContextView contextView) {
		try {
			AddressResolver<SocketAddress> resolver;
			try {
				resolver = (AddressResolver<SocketAddress>) resolverGroup.getResolver(channel.eventLoop());
			}
			catch (Throwable t) {
				// "FutureReturnValueIgnored" this is deliberate
				channel.close();
				return Mono.error(t);
			}

			if (!contextView.isEmpty()) {
				setChannelContext(channel, contextView);
			}

			Supplier<? extends SocketAddress> bindAddress = config.bindAddress();
			if (!resolver.isSupported(remoteAddress) || resolver.isResolved(remoteAddress)) {
				MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
				doConnect(Collections.singletonList(remoteAddress), bindAddress, monoChannelPromise, 0);
				return monoChannelPromise;
			}

			if (config instanceof ClientTransportConfig) {
				final ClientTransportConfig<?> clientTransportConfig = (ClientTransportConfig<?>) config;
				if (clientTransportConfig.doOnResolve != null) {
					clientTransportConfig.doOnResolve.accept(Connection.from(channel));
				}
			}

			Future<List<SocketAddress>> resolveFuture;
			if (resolver instanceof MicrometerAddressResolverGroupMetrics.MicrometerDelegatingAddressResolver) {
				resolveFuture = ((MicrometerAddressResolverGroupMetrics.MicrometerDelegatingAddressResolver<SocketAddress>) resolver)
						.resolveAll(remoteAddress, contextView);
			}
			else {
				resolveFuture = resolver.resolveAll(remoteAddress);
			}

			if (config instanceof ClientTransportConfig) {
				final ClientTransportConfig<?> clientTransportConfig = (ClientTransportConfig<?>) config;

				if (clientTransportConfig.doOnResolveError != null) {
					resolveFuture.addListener((FutureListener<List<SocketAddress>>) future -> {
						if (future.cause() != null) {
							clientTransportConfig.doOnResolveError.accept(Connection.from(channel), future.cause());
						}
					});
				}

				if (clientTransportConfig.doAfterResolve != null) {
					resolveFuture.addListener((FutureListener<List<SocketAddress>>) future -> {
						if (future.isSuccess()) {
							clientTransportConfig.doAfterResolve.accept(Connection.from(channel), future.getNow().get(0));
						}
					});
				}
			}

			if (resolveFuture.isDone()) {
				Throwable cause = resolveFuture.cause();
				if (cause != null) {
					// "FutureReturnValueIgnored" this is deliberate
					channel.close();
					return Mono.error(cause);
				}
				else {
					MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
					doConnect(resolveFuture.getNow(), bindAddress, monoChannelPromise, 0);
					return monoChannelPromise;
				}
			}

			MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
			resolveFuture.addListener((FutureListener<List<SocketAddress>>) future -> {
				if (future.cause() != null) {
					monoChannelPromise.tryFailure(future.cause());
				}
				else {
					doConnect(future.getNow(), bindAddress, monoChannelPromise, 0);
				}
			});
			return monoChannelPromise;
		}
		catch (Throwable t) {
			return Mono.error(t);
		}
	}

	static final class MonoChannelPromise extends Mono<Channel> implements ChannelPromise, Subscription {

		final Channel channel;

		CoreSubscriber<? super Channel> actual;

		MonoChannelPromise(Channel channel) {
			this.channel = channel;
		}

		@Override
		public ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
			throw new UnsupportedOperationException();
		}

		@Override
		@SuppressWarnings("unchecked")
		public ChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChannelPromise await() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean await(long timeoutMillis) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean await(long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChannelPromise awaitUninterruptibly() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean awaitUninterruptibly(long timeoutMillis) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void cancel() {
			// "FutureReturnValueIgnored" this is deliberate
			channel.close();
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public Throwable cause() {
			Object result = this.result;
			return result == SUCCESS ? null : (Throwable) result;
		}

		@Override
		public Channel channel() {
			return channel;
		}

		@Override
		public Void get() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Void get(long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Void getNow() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isCancellable() {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			Object result = this.result;
			return result != null;
		}

		@Override
		public boolean isSuccess() {
			Object result = this.result;
			return result == SUCCESS;
		}

		@Override
		public boolean isVoid() {
			return false;
		}

		@Override
		public ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
			return this;
		}

		@Override
		@SuppressWarnings("unchecked")
		public ChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
			return this;
		}

		@Override
		public void request(long n) {
			// noop
		}

		@Override
		public ChannelPromise setFailure(Throwable cause) {
			tryFailure(cause);
			return this;
		}

		@Override
		public ChannelPromise setSuccess() {
			trySuccess(null);
			return this;
		}

		@Override
		public ChannelPromise setSuccess(Void result) {
			trySuccess(null);
			return this;
		}

		@Override
		public boolean setUncancellable() {
			return true;
		}

		@Override
		public void subscribe(CoreSubscriber<? super Channel> actual) {
			EventLoop eventLoop = channel.eventLoop();
			if (eventLoop.inEventLoop()) {
				_subscribe(actual);
			}
			else {
				eventLoop.execute(() -> _subscribe(actual));
			}
		}

		@Override
		public ChannelPromise sync() {
			throw new UnsupportedOperationException();
		}

		@Override
		public ChannelPromise syncUninterruptibly() {
			throw new UnsupportedOperationException();
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public boolean tryFailure(Throwable cause) {
			if (RESULT_UPDATER.compareAndSet(this, null, cause)) {
				if (channel.isRegistered()) {
					// "FutureReturnValueIgnored" this is deliberate
					channel.close();
				}
				else {
					channel.unsafe().closeForcibly();
				}
				if (actual != null) {
					actual.onError(cause);
				}
				return true;
			}
			return false;
		}

		@Override
		public boolean trySuccess() {
			return trySuccess(null);
		}

		@Override
		public boolean trySuccess(Void result) {
			if (RESULT_UPDATER.compareAndSet(this, null, SUCCESS)) {
				if (actual != null) {
					actual.onNext(channel);
					actual.onComplete();
				}
				return true;
			}
			return false;
		}

		@Override
		public ChannelPromise unvoid() {
			return new DefaultChannelPromise(channel) {

				@Override
				public ChannelPromise setSuccess(Void result) {
					super.trySuccess(null);
					MonoChannelPromise.this.trySuccess(null);
					return this;
				}

				@Override
				public boolean trySuccess(Void result) {
					super.trySuccess(null);
					return MonoChannelPromise.this.trySuccess(null);
				}

				@Override
				public ChannelPromise setFailure(Throwable cause) {
					super.tryFailure(cause);
					MonoChannelPromise.this.tryFailure(cause);
					return this;
				}

				@Override
				public boolean tryFailure(Throwable cause) {
					super.tryFailure(cause);
					return MonoChannelPromise.this.tryFailure(cause);
				}
			};
		}

		void _subscribe(CoreSubscriber<? super Channel> actual) {
			this.actual = actual;
			actual.onSubscribe(this);

			if (isDone()) {
				if (isSuccess()) {
					actual.onNext(channel);
					actual.onComplete();
				}
				else {
					actual.onError(cause());
				}
			}
		}

		static final Object SUCCESS = new Object();
		static final AtomicReferenceFieldUpdater<MonoChannelPromise, Object> RESULT_UPDATER =
				AtomicReferenceFieldUpdater.newUpdater(MonoChannelPromise.class, Object.class, "result");
		volatile Object result;
	}

	static final class RetryConnectException extends RuntimeException {

		final List<SocketAddress> addresses;

		RetryConnectException(List<SocketAddress> addresses) {
			this.addresses = addresses;
		}

		@Override
		public synchronized Throwable fillInStackTrace() {
			// omit stacktrace for this exception
			return this;
		}

		private static final long serialVersionUID = -207274323623692199L;
	}

	static final Logger log = Loggers.getLogger(TransportConnector.class);

	static final Predicate<Throwable> RETRY_PREDICATE = t -> t instanceof RetryConnectException;
}
