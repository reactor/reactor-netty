/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.ChannelOption;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides the actual {@link TcpServer} instance.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class TcpServerBind extends TcpServer {

	static final TcpServerBind INSTANCE = new TcpServerBind();

	final TcpServerConfig config;

	TcpServerBind() {
		Map<ChannelOption<?>, Boolean> childOptions = new HashMap<>(2);
		childOptions.put(ChannelOption.AUTO_READ, false);
		childOptions.put(ChannelOption.TCP_NODELAY, true);
		this.config = new TcpServerConfig(
				Collections.singletonMap(ChannelOption.SO_REUSEADDR, true),
				childOptions,
				() -> new InetSocketAddress(DEFAULT_PORT));
	}

	TcpServerBind(TcpServerConfig config) {
		this.config = config;
	}

	@Override
	@SuppressWarnings("deprecation")
	public Mono<? extends DisposableServer> bind() {
		if (config.sslProvider != null && config.sslProvider.getDefaultConfigurationType() == null) {
			config.sslProvider = SslProvider.updateDefaultConfiguration(config.sslProvider, SslProvider.DefaultConfigurationType.TCP);
		}
		return super.bind();
	}

	@Override
	public TcpServerConfig configuration() {
		return config;
	}

	@Override
	protected TcpServer duplicate() {
		return new TcpServerBind(new TcpServerConfig(config));
	}

	static final int DEFAULT_PORT = 0;
}
