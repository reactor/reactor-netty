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

import java.util.Objects;
import java.util.function.Consumer;

import io.netty.bootstrap.ServerBootstrap;

/**
 * @author Stephane Maldini
 */
final class TcpServerSecure extends TcpServerOperator {

	final SslProvider sslProvider;

	static TcpServerSecure secure(TcpServer server, Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");

		SslProvider.Build builder = (SslProvider.Build) SslProvider.builder();
		sslProviderBuilder.accept(builder);
		return new TcpServerSecure(server, builder.build());
	}

	TcpServerSecure(TcpServer server, SslProvider sslProvider) {
		super(server);
		this.sslProvider = Objects.requireNonNull(sslProvider, "sslProvider");
	}

	@Override
	public ServerBootstrap configure() {
		return SslProvider.setBootstrap(source.configure(), sslProvider);
	}

	@Override
	public SslProvider sslProvider() {
		return this.sslProvider;
	}
}
