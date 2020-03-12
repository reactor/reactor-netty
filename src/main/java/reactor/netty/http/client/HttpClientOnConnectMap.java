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

package reactor.netty.http.client;

import java.util.Objects;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.ProxyProvider;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientOnConnectMap extends HttpClientOperator {

	final BiFunction<? super Mono<? extends Connection>, ? super Bootstrap, ? extends Mono<? extends Connection>> connector;

	HttpClientOnConnectMap(HttpClient client,
			BiFunction<? super Mono<? extends Connection>, ? super Bootstrap, ? extends Mono<? extends Connection>> connector) {
		super(client);
		this.connector = Objects.requireNonNull(connector, "mapConnect");
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return new OnConnectMapTcpClient(source.tcpConfiguration(), connector);
	}

	static final class OnConnectMapTcpClient extends TcpClient {

		final TcpClient sourceTcp;
		final BiFunction<? super Mono<? extends Connection>, ? super Bootstrap, ? extends Mono<? extends Connection>>
		                connector;

		OnConnectMapTcpClient(TcpClient sourceTcp,
				BiFunction<? super Mono<? extends Connection>, ? super Bootstrap, ? extends Mono<? extends Connection>> connector) {
			this.connector = connector;
			this.sourceTcp = sourceTcp;
		}

		@Override
		public Bootstrap configure() {
			return sourceTcp.configure();
		}

		@Override
		public Mono<? extends Connection> connect(Bootstrap b) {
			return connector.apply(sourceTcp.connect(b), b);
		}

		@Nullable
		@Override
		public ProxyProvider proxyProvider() {
			return sourceTcp.proxyProvider();
		}

		@Nullable
		@Override
		public SslProvider sslProvider() {
			return sourceTcp.sslProvider();
		}
	}
}
