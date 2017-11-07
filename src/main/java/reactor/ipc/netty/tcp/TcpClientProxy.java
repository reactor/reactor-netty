/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

import io.netty.bootstrap.Bootstrap;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * @author Stephane Maldini
 */
final class TcpClientProxy extends TcpClientOperator {

	final ProxyProvider proxyProvider;

	TcpClientProxy(TcpClient client,
			Consumer<? super ProxyProvider.TypeSpec> proxyOptions) {
		super(client);
		Objects.requireNonNull(proxyOptions, "proxyOptions");

		ProxyProvider.Build builder =
				(ProxyProvider.Build) ProxyProvider.builder();

		proxyOptions.accept(builder);

		this.proxyProvider = builder.build();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Bootstrap configure() {
		return TcpUtils.updateProxySupport(source.configure(), proxyProvider);
	}

	@Override
	public ProxyProvider proxyProvider() {
		return this.proxyProvider;
	}
}
