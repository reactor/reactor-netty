/*
 * Copyright (c) 2018-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.resolver.AddressResolverGroup;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.Operators;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.internal.util.Metrics;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.TransportConfig;
import reactor.netty.transport.TransportConnector;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;
import reactor.util.context.Context;

import static reactor.netty.ReactorNetty.format;

/**
 * {@link ConnectionProvider} that always establishes a new connection.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class NewConnectionProvider implements ConnectionProvider {

	static final Logger log = Loggers.getLogger(NewConnectionProvider.class);

	static final NewConnectionProvider INSTANCE = new NewConnectionProvider();

	@Override
	public Mono<? extends Connection> acquire(TransportConfig config,
			ConnectionObserver observer,
			@Nullable Supplier<? extends SocketAddress> remoteAddress,
			@Nullable AddressResolverGroup<?> resolverGroup) {
		return Mono.create(sink -> {
			SocketAddress remote = null;
			if (remoteAddress != null) {
				remote = Objects.requireNonNull(remoteAddress.get(), "Remote Address supplier returned null");
			}

			if (remote != null && resolverGroup != null) {
				Context currentContext = Context.of(sink.contextView());
				if (config.metricsRecorder() != null && Metrics.isMicrometerAvailable()) {
					Object currentObservation = reactor.netty.Metrics.currentObservation(currentContext);
					if (currentObservation != null) {
						currentContext = reactor.netty.Metrics.updateContext(currentContext, currentObservation);
					}
				}
				ConnectionObserver connectionObserver = new NewConnectionObserver(sink, currentContext, observer);
				ChannelInitializer<Channel> channelInitializer = config.channelInitializer(connectionObserver, remote, false);
				DisposableConnect disposableConnect = new DisposableConnect(sink, currentContext, config.bindAddress());
				TransportConnector.connect(config, remote, resolverGroup, channelInitializer, currentContext)
				                  .subscribe(disposableConnect);
			}
			else {
				Objects.requireNonNull(config.bindAddress(), "bindAddress");
				SocketAddress local = Objects.requireNonNull(config.bindAddress().get(), "Bind Address supplier returned null");
				if (local instanceof InetSocketAddress) {
					InetSocketAddress localInet = (InetSocketAddress) local;

					if (localInet.isUnresolved()) {
						local = AddressUtils.createResolved(localInet.getHostName(), localInet.getPort());
					}
				}
				ConnectionObserver connectionObserver = new NewConnectionObserver(sink, observer);
				ChannelInitializer<Channel> channelInitializer = config.channelInitializer(connectionObserver, null, true);
				DisposableConnect disposableConnect = new DisposableConnect(sink, config.bindAddress());
				TransportConnector.bind(config, channelInitializer, local, local instanceof DomainSocketAddress)
				                  .subscribe(disposableConnect);
			}
		});
	}

	@Override
	public boolean isDisposed() {
		return false;
	}

	@Override
	public int maxConnections() {
		return 1;
	}

	static final class DisposableConnect implements CoreSubscriber<Channel>, Disposable {
		final MonoSink<Connection> sink;
		final Context currentContext;
		final Supplier<? extends SocketAddress> bindAddress;

		Subscription subscription;

		DisposableConnect(MonoSink<Connection> sink, @Nullable Supplier<? extends SocketAddress> bindAddress) {
			this(sink, Context.of(sink.contextView()), bindAddress);
		}

		DisposableConnect(MonoSink<Connection> sink, Context currentContext, @Nullable Supplier<? extends SocketAddress> bindAddress) {
			this.sink = sink;
			this.currentContext = currentContext;
			this.bindAddress = bindAddress;
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
		}

		@Override
		public void onError(Throwable t) {
			if (bindAddress != null && (t instanceof BindException ||
					// With epoll/kqueue transport it is
					// io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: Address already in use
					(t instanceof IOException && t.getMessage() != null &&
							t.getMessage().contains("bind(..)")))) {
				sink.error(ChannelBindException.fail(bindAddress.get(), null));
			}
			else {
				sink.error(t);
			}
		}

		@Override
		public void onNext(Channel channel) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel, "Connected new channel"));
			}
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(subscription, s)) {
				this.subscription = s;
				sink.onCancel(this);
				s.request(Long.MAX_VALUE);
			}
		}
	}

	static final class NewConnectionObserver implements ConnectionObserver {

		final MonoSink<Connection> sink;
		final Context              currentContext;
		final ConnectionObserver   obs;

		NewConnectionObserver(MonoSink<Connection> sink, ConnectionObserver obs) {
			this(sink, Context.of(sink.contextView()), obs);
		}

		NewConnectionObserver(MonoSink<Connection> sink, Context currentContext, ConnectionObserver obs) {
			this.sink = sink;
			this.currentContext = currentContext;
			this.obs = obs;
		}

		@Override
		public Context currentContext() {
			return currentContext;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void onStateChange(Connection connection, State newState) {
			if (log.isDebugEnabled()) {
				log.debug(format(connection.channel(), "onStateChange({}, {})"), newState, connection);
			}
			if (newState == State.CONFIGURED) {
				sink.success(connection);
			}
			else if (newState == State.DISCONNECTING && connection.channel()
			                                                      .isActive()) {
				//"FutureReturnValueIgnored" this is deliberate
				connection.channel()
				          .close();
			}
			obs.onStateChange(connection, newState);
		}

		@Override
		public void onUncaughtException(Connection c, Throwable error) {
			sink.error(error);
			obs.onUncaughtException(c, error);
		}
	}
}

