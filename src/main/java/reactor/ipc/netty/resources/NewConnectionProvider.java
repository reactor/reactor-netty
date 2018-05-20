/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.resources;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.Supplier;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.context.Context;

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

			BootstrapHandlers.finalizeHandler(bootstrap,
					factory,
					new NewConnectionObserver(sink, obs));

			ChannelFuture f;
			if (bootstrap.config()
			             .remoteAddress() != null) {
				convertLazyRemoteAddress(bootstrap);
				f = bootstrap.connect();
			}
			else {
				f = bootstrap.bind();
			}
			DisposableConnect disposableConnect = new DisposableConnect(sink, f);
			f.addListener(disposableConnect);
			sink.onCancel(disposableConnect);
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


	static final class DisposableConnect
			implements Disposable, ChannelFutureListener {

		final MonoSink<Connection> sink;
		final ChannelFuture f;


		DisposableConnect(MonoSink<Connection> sink, ChannelFuture f) {
			this.sink = sink;
			this.f = f;
		}

		@Override
		public final void dispose() {
			if (isDisposed()) {
				return;
			}

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
					log.debug("Cancelled {}", f.channel());
					return;
				}
				if (f.cause() != null) {
					sink.error(f.cause());
				}
				else {
					sink.error(new IOException("error while connecting to " + f.channel()));
				}
			}
			else {
				new NewConnection(f.channel()).bind();
				if (log.isDebugEnabled()) {
					log.debug("Connected new channel {}", f.channel());
				}
			}
		}
	}

	static final class NewConnection implements Connection {
		final Channel channel;

		NewConnection(Channel channel) {
			this.channel = channel;
		}

		@Override
		public Channel channel() {
			return channel;
		}

		@Override
		public boolean isPersistent() {
			return false;
		}

		@Override
		public String toString() {
			return "NewConnection{" + "channel=" + channel + '}';
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
		public void onStateChange(Connection connection, State newState) {
			log.debug("onStateChange({}, {})", newState, connection);
			if (newState == State.CONFIGURED) {
				sink.success(connection);
			}
			else if (newState == State.DISCONNECTING && connection.channel()
			                                                      .isActive()) {
				connection.channel()
				          .close();
			}
			obs.onStateChange(connection, newState);
		}

		@Override
		public void onUncaughtException(Connection c, Throwable error) {
			log.error("onUncaughtException(" + c + ")", error);
			sink.error(error);
			obs.onUncaughtException(c, error);
		}
	}

}

