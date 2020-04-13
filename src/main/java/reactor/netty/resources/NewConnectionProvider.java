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

package reactor.netty.resources;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
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
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.transport.AddressUtils;
import reactor.netty.transport.TransportConfig;
import reactor.netty.transport.TransportConnector;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

import javax.annotation.Nullable;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 */
final class NewConnectionProvider implements ConnectionProvider {

	final static Logger log = Loggers.getLogger(NewConnectionProvider.class);

	final static NewConnectionProvider INSTANCE = new NewConnectionProvider();

	@Override
	public Mono<? extends Connection> acquire(Bootstrap b) {
		return Mono.create(sink -> {
			Bootstrap bootstrap = b.clone();

			ChannelOperations.OnSetup factory =
					BootstrapHandlers.channelOperationFactory(bootstrap);

			ConnectionObserver obs = BootstrapHandlers.connectionObserver(bootstrap);

			if (bootstrap.config()
			             .remoteAddress() != null) {
				convertLazyRemoteAddress(bootstrap);
			}

			BootstrapHandlers.finalizeHandler(bootstrap,
					factory,
					new NewConnectionObserver(sink, obs));

			ChannelFuture f;
			if (bootstrap.config()
			             .remoteAddress() != null) {
				f = bootstrap.connect();
			}
			else {
				f = bootstrap.bind();
			}
			DisposableConnectChannelFuture disposableConnectChannelFuture = new DisposableConnectChannelFuture(sink, f, bootstrap);
			f.addListener(disposableConnectChannelFuture);
			sink.onCancel(disposableConnectChannelFuture);
		});
	}

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

			ConnectionObserver connectionObserver = new NewConnectionObserver(sink, observer);
			DisposableConnect disposableConnect = new DisposableConnect(sink, config.bindAddress());
			if (remote != null) {
				ChannelInitializer<Channel> channelInitializer = config.channelInitializer(connectionObserver, remote, false);
				TransportConnector.connect(config, remote, resolverGroup, channelInitializer)
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
				ChannelInitializer<Channel> channelInitializer = config.channelInitializer(connectionObserver, null, true);
				TransportConnector.bind(config, channelInitializer, local)
				                  .subscribe(disposableConnect);
			}
		});
	}

	@Override
	public boolean isDisposed() {
		return false;
	}

	@SuppressWarnings("unchecked")
	static void convertLazyRemoteAddress(Bootstrap b) {
		SocketAddress remote = b.config()
		                        .remoteAddress();

		Objects.requireNonNull(remote, "Remote Address not configured");

		if (remote instanceof Supplier) {
			Supplier<? extends SocketAddress> lazyRemote =
					(Supplier<? extends SocketAddress>) remote;

			b.remoteAddress(Objects.requireNonNull(lazyRemote.get(),
					"address supplier returned null"));
		}
	}


	static final class DisposableConnect implements CoreSubscriber<Channel>, Disposable {
		final MonoSink<Connection> sink;
		final Supplier<? extends SocketAddress> bindAddress;

		Subscription subscription;

		DisposableConnect(MonoSink<Connection> sink, @Nullable Supplier<? extends SocketAddress> bindAddress) {
			this.sink = sink;
			this.bindAddress = bindAddress;
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
		}

		@Override
		public void onError(Throwable t) {
			if (bindAddress != null && (t instanceof BindException ||
					// With epoll/kqueue transport it is
					// io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: Address already in use
					(t instanceof IOException && t.getMessage() != null &&
							t.getMessage().contains("Address already in use")))) {
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


	static final class DisposableConnectChannelFuture implements Disposable, ChannelFutureListener {

		final MonoSink<Connection> sink;
		final ChannelFuture f;
		final Bootstrap bootstrap;


		DisposableConnectChannelFuture(MonoSink<Connection> sink, ChannelFuture f, Bootstrap
				bootstrap) {
			this.sink = sink;
			this.f = f;
			this.bootstrap = bootstrap;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
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
		public boolean isDisposed() {
			return f.isCancelled() || f.isDone();
		}

		@Override
		public final void operationComplete(ChannelFuture f) {
			if (!f.isSuccess()) {
				if (f.isCancelled()) {
					if (log.isDebugEnabled()) {
						log.debug(format(f.channel(), "Channel cancelled"));
					}
					return;
				}
				Throwable cause = f.cause();
				if (cause != null) {
					if (cause instanceof BindException ||
							// With epoll/kqueue transport it is
							// io.netty.channel.unix.Errors$NativeIoException: bind(..) failed: Address already in use
							(cause instanceof IOException && cause.getMessage() != null &&
									cause.getMessage().contains("Address already in use"))) {
						sink.error(ChannelBindException.fail(bootstrap.config().localAddress(), null));
					}
					else {
						sink.error(cause);
					}
				}
				else {
					sink.error(new IOException("error while connecting to " + f.channel()));
				}
			}
			else {
//				new NewConnection(f.channel()).bind();
				if (log.isDebugEnabled()) {
					log.debug(format(f.channel(), "Connected new channel"));
				}
			}
		}
	}


	static final class NewConnectionObserver implements ConnectionObserver {

		final MonoSink<Connection> sink;
		final ConnectionObserver   obs;

		NewConnectionObserver(MonoSink<Connection> sink, ConnectionObserver obs) {
			this.sink = sink;
			this.obs = obs;
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
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

