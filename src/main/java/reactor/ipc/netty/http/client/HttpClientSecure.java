/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
package reactor.ipc.netty.http.client;

import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import reactor.ipc.netty.tcp.SslProvider;
import reactor.ipc.netty.tcp.TcpClient;

/**
 * @author Stephane Maldini
 */
final class HttpClientSecure extends HttpClientOperator {

	static HttpClient secure(HttpClient client, Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");
		return new HttpClientSecure(client, sslProviderBuilder);
	}

	final Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder;

	HttpClientSecure(HttpClient client,
			@Nullable Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		super(client);
		this.sslProviderBuilder = sslProviderBuilder;
	}

	@Override
	protected TcpClient tcpConfiguration() {
		if (sslProviderBuilder == null) {
			return source.tcpConfiguration()
			             .secure();
		}
		return source.tcpConfiguration().secure(sslProviderBuilder);
	}
}
