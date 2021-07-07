/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.tcp;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.SSLException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * SslContext builder that provides, specific for protocol, default configuration.
 *
 * @author Violeta Georgieva
 * @since 1.0.6
 */
public abstract class AbstractProtocolSslContextSpec<T extends AbstractProtocolSslContextSpec<T>>
		implements SslProvider.ProtocolSslContextSpec, Supplier<T> {

	final SslContextBuilder sslContextBuilder;

	protected AbstractProtocolSslContextSpec(SslContextBuilder sslContextBuilder) {
		this.sslContextBuilder = sslContextBuilder;
		configure(defaultConfiguration());
	}

	protected abstract Consumer<SslContextBuilder> defaultConfiguration();

	@Override
	public T configure(Consumer<SslContextBuilder> sslCtxBuilder) {
		Objects.requireNonNull(sslCtxBuilder, "sslCtxBuilder");
		sslCtxBuilder.accept(sslContextBuilder);
		return get();
	}

	@Override
	public SslContext sslContext() throws SSLException {
		return sslContextBuilder.build();
	}
}
