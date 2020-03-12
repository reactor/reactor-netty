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
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * @author Stephane Maldini
 */
final class TcpClientSecure extends TcpClientOperator {

	final SslProvider sslProvider;

	static TcpClient secure(TcpClient client, Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");

		SslProvider.Build builder = (SslProvider.Build) SslProvider.builder();
		sslProviderBuilder.accept(builder);
		return new TcpClientSecure(client, builder.build());
	}

	TcpClientSecure(TcpClient client, @Nullable SslProvider provider) {
		super(client);
		if (provider == null) {
			this.sslProvider = DEFAULT_CLIENT_PROVIDER;
		}
		else {
			this.sslProvider = Objects.requireNonNull(provider, "provider");
		}
	}

	@Override
	public Bootstrap configure() {
		return SslProvider.setBootstrap(source.configure(), sslProvider);
	}

	@Override
	public SslProvider sslProvider(){
		return this.sslProvider;
	}


	static final SslProvider DEFAULT_CLIENT_PROVIDER;

	static {
		SslProvider sslProvider;
		try {
			sslProvider =
					SslProvider.builder()
					           .sslContext(SslContextBuilder.forClient())
					           .defaultConfiguration(SslProvider.DefaultConfigurationType.TCP)
					           .build();
		}
		catch (Exception e) {
			sslProvider = null;
		}
		DEFAULT_CLIENT_PROVIDER = sslProvider;
	}
}
