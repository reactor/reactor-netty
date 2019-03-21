/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.tcp;

import java.util.Objects;
import java.util.function.BiFunction;

import io.netty.bootstrap.ServerBootstrap;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.channel.BootstrapHandlers;

/**
 * @author Stephane Maldini
 */
final class TcpServerHandle extends TcpServerOperator implements ConnectionObserver {

	final BiFunction<? super NettyInbound, ? super NettyOutbound, ? extends Publisher<Void>> handler;

	TcpServerHandle(TcpServer server, BiFunction<? super NettyInbound, ? super
			NettyOutbound, ? extends Publisher<Void>> handler) {
		super(server);
		this.handler = Objects.requireNonNull(handler, "handler");
	}

	@Override
	public ServerBootstrap configure() {
		ServerBootstrap b = source.configure();
		ConnectionObserver observer = BootstrapHandlers.childConnectionObserver(b);
		BootstrapHandlers.childConnectionObserver(b, observer.then(this));
		return b;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onStateChange(Connection connection, State newState) {
		if (newState == State.CONFIGURED) {
			try {
				if (log.isDebugEnabled()) {
					log.debug("{} handler is being applied: {}",
							connection.channel(),
							handler);
				}
				Mono.fromDirect(handler.apply(connection.inbound(), connection.outbound()))
				    .subscribe(connection.disposeSubscriber());
			}
			catch (Throwable t) {
				log.error("", t);
				connection.channel()
				          .close();
			}
		}
	}
}
