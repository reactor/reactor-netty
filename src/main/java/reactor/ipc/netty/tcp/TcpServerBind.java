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

package reactor.ipc.netty.tcp;

import io.netty.bootstrap.ServerBootstrap;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.resources.LoopResources;
import reactor.util.context.Context;

/**
 * @author Stephane Maldini
 */
final class TcpServerBind extends TcpServer {

	static final TcpServerBind INSTANCE = new TcpServerBind();

	@Override
	public Mono<? extends DisposableServer> bind(ServerBootstrap b) {

		if (b.config()
		     .group() == null) {

			TcpServerRunOn.configure(b,
					LoopResources.DEFAULT_NATIVE,
					TcpResources.get(),
					TcpUtils.findSslContext(b));
		}

		return Mono.create(sink -> {
			ServerBootstrap bootstrap = b.clone();

			ConnectionObserver obs = BootstrapHandlers.connectionObserver(bootstrap);
			ConnectionObserver childObs = BootstrapHandlers.childConnectionObserver(bootstrap);
			ChannelOperations.OnSetup ops = BootstrapHandlers.channelOperationFactory(bootstrap);

			TcpUtils.convertLazyLocalAddress(bootstrap);

			sink.onCancel(BootstrapHandlers.bind(
					bootstrap,
					ops,
					new SelectorObserver(sink, obs),
					new ChildObserver(childObs)
			));
		});
	}

	final static class ChildObserver implements ConnectionObserver {

		final ConnectionObserver childObs;

		ChildObserver(ConnectionObserver childObs) {
			this.childObs = childObs;
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			log.error("onUncaughtException("+connection+")", error);
			onStateChange(connection, State.DISCONNECTING);
		}

		@Override
		public void onStateChange(Connection connection, State newState) {
			if (newState == State.DISCONNECTING) {
				if (connection.channel()
				              .isActive() && !Connection.isPersistent(connection.channel())) {
					connection.dispose();
				}
			}

			childObs.onStateChange(connection, newState);
		}
	}

	final static class SelectorObserver implements ConnectionObserver {

		final MonoSink<DisposableServer> sink;
		final ConnectionObserver obs;

		SelectorObserver(MonoSink<DisposableServer> sink, ConnectionObserver obs) {
			this.sink = sink;
			this.obs = obs;
		}

		@Override
		public Context currentContext() {
			return sink.currentContext();
		}

		@Override
		public void onUncaughtException(Connection connection, Throwable error) {
			sink.error(error);
		}

		@Override
		@SuppressWarnings("unchecked")
		public void onStateChange(Connection connection, State newState) {
			if (newState == State.CONNECTED) {
				sink.success((DisposableServer)connection);
			}
			obs.onStateChange(connection, newState);

		}
	}
}
