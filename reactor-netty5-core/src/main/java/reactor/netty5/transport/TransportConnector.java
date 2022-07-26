/*
 * Copyright (c) 2020-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.transport;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelFactory;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.ServerChannelFactory;
import io.netty5.channel.socket.DomainSocketAddress;
import io.netty5.resolver.AddressResolver;
import io.netty5.resolver.AddressResolverGroup;
import io.netty5.util.AttributeKey;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.Promise;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.netty5.Connection;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static reactor.netty5.ReactorNetty.format;

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
	 * @param isDomainSocket true if domain socket is needed, false otherwise
	 * @return a {@link Mono} of {@link Channel}
	 */
	public static Mono<Channel> bind(TransportConfig config, ChannelInitializer<Channel> channelInitializer,
			SocketAddress bindAddress, boolean isDomainSocket) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(bindAddress, "bindAddress");
		Objects.requireNonNull(channelInitializer, "channelInitializer");

		return doInitAndRegister(config, channelInitializer, isDomainSocket, config.eventLoopGroup().next())
				.flatMap(channel -> {
					MonoChannelPromise promise = new MonoChannelPromise(channel);
					channel.executor().execute(() ->
							channel.bind(bindAddress)
									.addListener(f -> {
										if (f.isSuccess()) {
											promise.setSuccess();
										}
										else {
											channel.close();
											promise.setFailure(f.cause());
										}
									}));
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
	 * Set the channel attributes
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
	 * Set the channel options
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
				if (!channel.isOptionSupported(e.getKey())) {
					log.warn(format(channel, "Unknown channel option '{}' for channel '{}'"), e.getKey(), channel);
				}
				else {
					channel.setOption((ChannelOption<Object>) e.getKey(), e.getValue());
				}
			}
			catch (Throwable t) {
				log.warn(format(channel, "Failed to set channel option '{}' with value '{}' for channel '{}'"),
						e.getKey(), e.getValue(), channel, t);
			}
		}
	}

	static void doConnect(
			List<SocketAddress> addresses,
			@Nullable Supplier<? extends SocketAddress> bindAddress,
			MonoChannelPromise connectPromise,
			int index) {
		Channel channel = connectPromise.channel;
		channel.executor().execute(() -> {
			SocketAddress remoteAddress = addresses.get(index);

			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Connecting to [" + remoteAddress + "]."));
			}

			Future<Void> f;
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
					channel.close();

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
		boolean onServer = channelInitializer instanceof ServerTransport.AcceptorInitializer;
		Channel channel;
		try {
			if (onServer) {
				EventLoopGroup childEventLoopGroup = ((ServerTransportConfig<?>) config).childEventLoopGroup();
				ServerChannelFactory<? extends Channel> channelFactory = config.serverConnectionFactory(isDomainSocket);
				channel = channelFactory.newChannel(eventLoop, childEventLoopGroup);
				((ServerTransport.AcceptorInitializer) channelInitializer).acceptor.enableAutoReadTask(channel);
			}
			else {
				ChannelFactory<? extends Channel> channelFactory = config.connectionFactory(isDomainSocket);
				channel = channelFactory.newChannel(eventLoop);
			}
		}
		catch (Throwable t) {
			return Mono.error(t);
		}

		MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
		eventLoop.execute(() -> {
			// Init channel
			setChannelOptions(channel, config.options, isDomainSocket);
			setAttributes(channel, config.attrs);

			Future<Void> initFuture;
			if (onServer) {
				Promise<Void> promise = channel.newPromise();
				((ServerTransport.AcceptorInitializer) channelInitializer).initPromise = promise;
				channel.pipeline().addLast(channelInitializer);
				initFuture = promise.asFuture();
			}
			else {
				channel.pipeline().addLast(channelInitializer);
				initFuture = channel.newSucceededFuture();
			}

			initFuture.addListener(future -> {
				if (future.isSuccess()) {
					channel.register().addListener(f -> {
						if (f.isSuccess()) {
							monoChannelPromise.setSuccess();
						}
						else {
							if (channel.isRegistered()) {
								channel.close();
							}
							else {
								channel.close();
							}
							monoChannelPromise.setFailure(f.cause());
						}
					});
				}
				else {
					channel.close();
					monoChannelPromise.setFailure(future.cause());
				}
			});
		});

		return monoChannelPromise;
	}

	@SuppressWarnings({"unchecked", "try"})
	static Mono<Channel> doResolveAndConnect(Channel channel, TransportConfig config,
			SocketAddress remoteAddress, AddressResolverGroup<?> resolverGroup, ContextView contextView) {
		try {
			AddressResolver<SocketAddress> resolver =
					(AddressResolver<SocketAddress>) resolverGroup.getResolver(channel.executor());

			Supplier<? extends SocketAddress> bindAddress = config.bindAddress();
			if (!resolver.isSupported(remoteAddress) || resolver.isResolved(remoteAddress)) {
				MonoChannelPromise monoChannelPromise = new MonoChannelPromise(channel);
				doConnect(Collections.singletonList(remoteAddress), bindAddress, monoChannelPromise, 0);
				return monoChannelPromise;
			}

			if (config instanceof ClientTransportConfig<?> clientTransportConfig) {
				if (clientTransportConfig.doOnResolve != null) {
					clientTransportConfig.doOnResolve.accept(Connection.from(channel));
				}
			}

			Future<List<SocketAddress>> resolveFuture;
			if (resolver instanceof MicrometerAddressResolverGroupMetrics.MicrometerDelegatingAddressResolver) {
				channel.attr(CONTEXT_VIEW).compareAndSet(null, contextView);

				resolveFuture = ((MicrometerAddressResolverGroupMetrics.MicrometerDelegatingAddressResolver<SocketAddress>) resolver)
						.resolveAll(remoteAddress, contextView);
			}
			else {
				resolveFuture = resolver.resolveAll(remoteAddress);
			}

			if (config instanceof ClientTransportConfig<?> clientTransportConfig) {

				if (clientTransportConfig.doOnResolveError != null) {
					resolveFuture.addListener(future -> {
						if (future.cause() != null) {
							clientTransportConfig.doOnResolveError.accept(Connection.from(channel), future.cause());
						}
					});
				}

				if (clientTransportConfig.doAfterResolve != null) {
					resolveFuture.addListener(future -> {
						if (future.isSuccess()) {
							clientTransportConfig.doAfterResolve.accept(Connection.from(channel), future.getNow().get(0));
						}
					});
				}
			}

			if (resolveFuture.isDone()) {
				Throwable cause = resolveFuture.cause();
				if (cause != null) {
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
			resolveFuture.addListener(future -> {
				if (future.cause() != null) {
					channel.close();
					monoChannelPromise.setFailure(future.cause());
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

	static final class MonoChannelPromise extends Mono<Channel> implements Subscription {

		final Channel channel;

		CoreSubscriber<? super Channel> actual;

		MonoChannelPromise(Channel channel) {
			this.channel = channel;
		}

		@Override
		public void cancel() {
			channel.close();
		}

		@Override
		public void request(long n) {
			// noop
		}

		@Override
		public void subscribe(CoreSubscriber<? super Channel> actual) {
			EventLoop eventLoop = channel.executor();
			if (eventLoop.inEventLoop()) {
				_subscribe(actual);
			}
			else {
				eventLoop.execute(() -> _subscribe(actual));
			}
		}

		Throwable cause() {
			Object result = this.result;
			return result == SUCCESS ? null : (Throwable) result;
		}

		boolean isDone() {
			Object result = this.result;
			return result != null;
		}

		boolean isSuccess() {
			Object result = this.result;
			return result == SUCCESS;
		}

		void setFailure(Throwable cause) {
			if (RESULT_UPDATER.compareAndSet(this, null, cause)) {
				if (actual != null) {
					actual.onError(cause);
				}
			}
		}

		void setSuccess() {
			if (RESULT_UPDATER.compareAndSet(this, null, SUCCESS)) {
				if (actual != null) {
					actual.onNext(channel);
					actual.onComplete();
				}
			}
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

	static final AttributeKey<ContextView> CONTEXT_VIEW = AttributeKey.valueOf("$CONTEXT_VIEW");

	static final Predicate<Throwable> RETRY_PREDICATE = t -> t instanceof RetryConnectException;
}
