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
package reactor.netty.http.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.group.ChannelGroup;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;
import reactor.netty.tcp.TcpServer;

import java.util.Objects;
import java.util.function.Function;

/**
 * @author Violeta Georgieva
 * @since 0.9.6
 */
final class HttpServerChannelGroup extends HttpServerOperator implements ConnectionObserver,
                                                                         Function<ServerBootstrap, ServerBootstrap> {

	final ChannelGroup channelGroup;

	HttpServerChannelGroup(HttpServer source, ChannelGroup channelGroup) {
		super(source);
		this.channelGroup = Objects.requireNonNull(channelGroup, "channelGroup");
	}

	@Override
	protected TcpServer tcpConfiguration() {
		return source.tcpConfiguration().bootstrap(this);
	}

	@Override
	public void onStateChange(Connection connection, State newState) {
		if (newState == State.CONNECTED) {
			channelGroup.add(connection.channel());
		}
	}

	@Override
	public ServerBootstrap apply(ServerBootstrap b) {
		HttpServerConfiguration.channelGroup(b, channelGroup);
		ConnectionObserver observer = BootstrapHandlers.childConnectionObserver(b);
		BootstrapHandlers.childConnectionObserver(b, observer.then(this));
		return b;
	}
}
