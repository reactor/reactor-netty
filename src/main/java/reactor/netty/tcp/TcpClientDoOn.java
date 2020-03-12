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
package reactor.netty.tcp;

import java.util.function.Consumer;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;

/**
 * @author Stephane Maldini
 */
final class TcpClientDoOn extends TcpClientOperator implements ConnectionObserver {

	final Consumer<? super Bootstrap>  onConnect;
	final Consumer<? super Connection> onConnected;
	final Consumer<? super Connection> onDisconnected;

	TcpClientDoOn(TcpClient client,
			@Nullable Consumer<? super Bootstrap> onConnect,
			@Nullable Consumer<? super Connection> onConnected,
			@Nullable Consumer<? super Connection> onDisconnected) {
		super(client);
		this.onConnect = onConnect;
		this.onConnected = onConnected;
		this.onDisconnected = onDisconnected;
	}

	@Override
	public Bootstrap configure() {
		Bootstrap b = source.configure();
		ConnectionObserver observer = BootstrapHandlers.connectionObserver(b);
		BootstrapHandlers.connectionObserver(b, observer.then(this));
		return b;
	}

	@Override
	public Mono<? extends Connection> connect(Bootstrap b) {
		if (onConnect != null) {
			return source.connect(b)
			             .doOnSubscribe(s -> onConnect.accept(b));
		}
		return source.connect(b);
	}

	@Override
	public void onStateChange(Connection connection, State newState) {
		if (onConnected != null && newState == State.CONFIGURED) {
			onConnected.accept(connection);
			return;
		}
		if (onDisconnected != null) {
			if (newState == State.DISCONNECTING) {
				connection.onDispose(() -> onDisconnected.accept(connection));
			}
			else if (newState == State.RELEASED) {
				onDisconnected.accept(connection);
			}
		}
	}
}
