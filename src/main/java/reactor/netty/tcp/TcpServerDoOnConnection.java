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

import java.util.Objects;
import java.util.function.Consumer;

import io.netty.bootstrap.ServerBootstrap;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;

import static reactor.netty.ReactorNetty.format;

/**
 * @author Stephane Maldini
 */
final class TcpServerDoOnConnection extends TcpServerOperator implements
                                                              ConnectionObserver {

	final Consumer<? super Connection> onConnection;

	TcpServerDoOnConnection(TcpServer server, Consumer<? super Connection> onConnection) {
		super(server);
		this.onConnection = Objects.requireNonNull(onConnection, "onConnection");
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onStateChange(Connection connection, State newState) {
		if (newState == State.CONFIGURED) {
			try {
				onConnection.accept(connection);
			}
			catch (Throwable t) {
				log.error(format(connection.channel(), ""), t);
				connection.channel()
				          .close();
			}
		}
	}

	@Override
	public ServerBootstrap configure() {
		ServerBootstrap b = source.configure();
		ConnectionObserver observer = BootstrapHandlers.childConnectionObserver(b);
		BootstrapHandlers.childConnectionObserver(b, observer.then(this));
		return b;
	}
}
