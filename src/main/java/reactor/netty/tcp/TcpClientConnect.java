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

import io.netty.bootstrap.Bootstrap;
import io.netty.util.AttributeKey;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

/**
 * @author Stephane Maldini
 */
final class TcpClientConnect extends TcpClient {

	static final TcpClientConnect INSTANCE = new TcpClientConnect(ConnectionProvider.newConnection());

	static final AttributeKey<Integer> MAX_CONNECTIONS = AttributeKey.newInstance("maxConnections");

	final ConnectionProvider provider;
	final int                maxConnections;

	TcpClientConnect(ConnectionProvider provider) {
		this.provider = Objects.requireNonNull(provider, "connectionProvider");
		maxConnections = provider.maxConnections();
	}

	@Override
	public Bootstrap configure() {
		Bootstrap b = super.configure();
		if (maxConnections != -1) {
			b.attr(MAX_CONNECTIONS, maxConnections);
		}
		return b;
	}

	@Override
	public Mono<? extends Connection> connect(Bootstrap b) {

		if (b.config()
		     .group() == null) {

			TcpClientRunOn.configure(b,
					LoopResources.DEFAULT_NATIVE,
					TcpResources.get(),
					maxConnections != -1);
		}

		return provider.acquire(b);

	}
}
