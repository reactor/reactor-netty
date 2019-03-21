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

import java.util.function.Consumer;
import javax.annotation.Nullable;

import io.netty.bootstrap.ServerBootstrap;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.DisposableServer;
import reactor.netty.channel.BootstrapHandlers;

/**
 * @author Stephane Maldini
 */
final class TcpServerDoOn extends TcpServerOperator implements ConnectionObserver {

	final Consumer<? super ServerBootstrap>  onBind;
	final Consumer<? super DisposableServer> onBound;
	final Consumer<? super DisposableServer> onUnbound;

	TcpServerDoOn(TcpServer server,
			@Nullable Consumer<? super ServerBootstrap> onBind,
			@Nullable Consumer<? super DisposableServer> onBound,
			@Nullable Consumer<? super DisposableServer> onUnbound) {
		super(server);
		this.onBind = onBind;
		this.onBound = onBound;
		this.onUnbound = onUnbound;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onStateChange(Connection connection, State newState) {
		if (newState == State.CONNECTED) {
			if (onBound != null) {
				onBound.accept((DisposableServer)connection);
			}
			if (onUnbound != null) {
				connection.channel()
				          .closeFuture()
				          .addListener(f -> onUnbound.accept((DisposableServer)connection));
			}
			return;
		}
	}

	@Override
	public Mono<? extends DisposableServer> bind(ServerBootstrap b) {
		if (onBind != null) {
			return source.bind(b)
			             .doOnSubscribe(s -> onBind.accept(b));
		}
		return source.bind(b);
	}

	@Override
	public ServerBootstrap configure() {
		ServerBootstrap b = source.configure();
		ConnectionObserver observer = BootstrapHandlers.connectionObserver(b);
		BootstrapHandlers.connectionObserver(b, observer.then(this));
		return b;
	}
}
