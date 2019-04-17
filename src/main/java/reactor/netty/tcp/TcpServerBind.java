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

package reactor.netty.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.Supplier;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.util.NetUtil;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.channel.AbortedException;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.LoopResources;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 */
final class TcpServerBind extends TcpServer {

	static final TcpServerBind INSTANCE = new TcpServerBind();
	final ServerBootstrap serverBootstrap;

	TcpServerBind() {
		this.serverBootstrap = createServerBootstrap();
		BootstrapHandlers.channelOperationFactory(this.serverBootstrap, TcpUtils.TCP_OPS);
	}

	@Override
	public Mono<? extends DisposableServer> bind(ServerBootstrap b) {
		SslProvider ssl = SslProvider.findSslSupport(b);
		if (ssl != null && ssl.getDefaultConfigurationType() == null) {
			ssl = SslProvider.updateDefaultConfiguration(ssl, SslProvider.DefaultConfigurationType.TCP);
			SslProvider.setBootstrap(b, ssl);
		}

		if (b.config()
		     .group() == null) {

			TcpServerRunOn.configure(b, LoopResources.DEFAULT_NATIVE, TcpResources.get());
		}

		return Mono.create(sink -> {
			ServerBootstrap bootstrap = b.clone();

			ConnectionObserver obs = BootstrapHandlers.connectionObserver(bootstrap);
			ConnectionObserver childObs =
					BootstrapHandlers.childConnectionObserver(bootstrap);
			ChannelOperations.OnSetup ops =
					BootstrapHandlers.channelOperationFactory(bootstrap);

			convertLazyLocalAddress(bootstrap);

			BootstrapHandlers.finalizeHandler(bootstrap, ops, new ChildObserver(childObs));

			ChannelFuture f = bootstrap.bind();

			DisposableBind disposableServer = new DisposableBind(sink, f, obs, bootstrap);
			f.addListener(disposableServer);
			sink.onCancel(disposableServer);
		});
	}

	@Override
	public ServerBootstrap configure() {
		return this.serverBootstrap.clone();
	}

	@SuppressWarnings("unchecked")
	static void convertLazyLocalAddress(ServerBootstrap b) {
		SocketAddress local = b.config()
		                       .localAddress();

		Objects.requireNonNull(local, "Remote Address not configured");

		if (local instanceof Supplier) {
			Supplier<? extends SocketAddress> lazyLocal =
					(Supplier<? extends SocketAddress>) local;

			b.localAddress(Objects.requireNonNull(lazyLocal.get(),
					"address supplier returned  null"));
		}

		if (local instanceof InetSocketAddress) {
			InetSocketAddress localInet = (InetSocketAddress) local;

			if (localInet.isUnresolved()) {
				b.localAddress(InetSocketAddressUtil.createResolved(localInet.getHostName(),
						localInet.getPort()));
			}

		}
	}

	ServerBootstrap createServerBootstrap() {
		return new ServerBootstrap()
				.option(ChannelOption.SO_REUSEADDR, true)
				.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
				.childOption(ChannelOption.AUTO_READ, false)
				.childOption(ChannelOption.TCP_NODELAY, true)
				.localAddress(new InetSocketAddress(DEFAULT_PORT));
	}

	static final class DisposableBind
			implements Disposable, ChannelFutureListener, DisposableServer, Connection {

		final MonoSink<DisposableServer> sink;
		final ChannelFuture              f;
		final ServerBootstrap            bootstrap;
		final ConnectionObserver         selectorObserver;

		DisposableBind(MonoSink<DisposableServer> sink, ChannelFuture f,
				ConnectionObserver selectorObserver,
				ServerBootstrap bootstrap) {
			this.sink = sink;
			this.bootstrap = bootstrap;
			this.f = f;
			this.selectorObserver = selectorObserver;
		}

		@Override
		public final void dispose() {
			// Returned value is deliberately ignored
			f.removeListener(this);

			if (f.channel()
			     .isActive()) {

				f.channel()
				 .close();

				HttpResources.get()
				             .disposeWhen(bootstrap.config()
				                                   .localAddress());
			}
			else if (!f.isDone()) {
				f.cancel(true);
			}
		}

		@Override
		public Channel channel() {
			return f.channel();
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
				sink.error(ChannelBindException.fail(bootstrap, f.cause()));
			}
			else {
				if (log.isDebugEnabled()) {
					log.debug(format(f.channel(), "Bound new server"));
				}
				sink.success(this);
				selectorObserver.onStateChange(this, ConnectionObserver.State.CONNECTED);
			}
		}
	}

	final static class ChildObserver implements ConnectionObserver {

		final ConnectionObserver childObs;

		ChildObserver(ConnectionObserver childObs) {
			this.childObs = childObs;
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			ChannelOperations ops = ChannelOperations.get(connection.channel());
			if (ops == null && (error instanceof IOException || AbortedException.isConnectionReset(error))) {
				if (log.isDebugEnabled()) {
					log.debug(format(connection.channel(), "onUncaughtException(" + connection + ")"), error);
				}
			}
			else {
				log.error(format(connection.channel(), "onUncaughtException(" + connection + ")"), error);
			}
			connection.dispose();
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == State.DISCONNECTING) {
				if (connection.channel()
				              .isActive() && !connection.isPersistent()) {
					connection.dispose();
				}
			}

			childObs.onStateChange(connection, newState);

		}
	}
}
