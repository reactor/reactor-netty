/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.group.ChannelGroup;
import io.netty.util.AttributeKey;
import reactor.netty.Connection;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.BootstrapHandlers;

import java.util.Objects;

/**
 * @author Violeta Georgieva
 * @since 0.9.6
 */
final class TcpServerChannelGroup extends TcpServerOperator implements ConnectionObserver {

	static final AttributeKey<ChannelGroup> CHANNEL_GROUP = AttributeKey.newInstance("channelGroup");

	final ChannelGroup channelGroup;

	TcpServerChannelGroup(TcpServer source, ChannelGroup channelGroup) {
		super(source);
		this.channelGroup = Objects.requireNonNull(channelGroup, "channelGroup");
	}

	@Override
	public ServerBootstrap configure() {
		ServerBootstrap b = source.configure();
		b.attr(CHANNEL_GROUP, channelGroup);
		ConnectionObserver observer = BootstrapHandlers.childConnectionObserver(b);
		BootstrapHandlers.childConnectionObserver(b, observer.then(this));
		return b;
	}

	@Override
	public void onStateChange(Connection connection, State newState) {
		if (newState == State.CONNECTED) {
			channelGroup.add(connection.channel());
		}
	}
}
