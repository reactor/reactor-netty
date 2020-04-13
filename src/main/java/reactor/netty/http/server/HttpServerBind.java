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

import io.netty.channel.ChannelOption;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.tcp.SslProvider;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides the actual {@link HttpServer} instance.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class HttpServerBind extends HttpServer {

	static final HttpServerBind INSTANCE = new HttpServerBind();

	final HttpServerConfig config;

	HttpServerBind() {
		Map<ChannelOption<?>, Boolean> childOptions = new HashMap<>(2);
		childOptions.put(ChannelOption.AUTO_READ, false);
		childOptions.put(ChannelOption.TCP_NODELAY, true);
		this.config = new HttpServerConfig(
				Collections.singletonMap(ChannelOption.SO_REUSEADDR, true),
				childOptions,
				() -> new InetSocketAddress(DEFAULT_PORT));
	}

	HttpServerBind(HttpServerConfig config) {
		this.config = config;
	}

	@Override
	public Mono<? extends DisposableServer> bind() {
		if (config.sslProvider != null) {
			if (config.sslProvider.getDefaultConfigurationType() == null) {
				if ((config.protocols & HttpServerConfig.h2) == HttpServerConfig.h2) {
					config.sslProvider = SslProvider.updateDefaultConfiguration(config.sslProvider,
							SslProvider.DefaultConfigurationType.H2);
				}
				else {
					config.sslProvider = SslProvider.updateDefaultConfiguration(config.sslProvider,
							SslProvider.DefaultConfigurationType.TCP);
				}
			}
			if ((configuration().protocols & HttpServerConfig.h2c) == HttpServerConfig.h2c) {
				return Mono.error(new IllegalArgumentException(
						"Configured H2 Clear-Text protocol with TLS. " +
								"Use the non Clear-Text H2 protocol via " +
								"HttpServer#protocol or disable TLS via HttpServer#noSSL())"));
			}
		}
		else {
			if ((config.protocols & HttpServerConfig.h2) == HttpServerConfig.h2) {
				return Mono.error(new IllegalArgumentException(
						"Configured H2 protocol without TLS. " +
								"Use a Clear-Text H2 protocol via HttpServer#protocol or configure TLS " +
								"via HttpServer#secure"));
			}
		}
		return super.bind();
	}

	@Override
	public HttpServerConfig configuration() {
		return config;
	}

	@Override
	protected HttpServer duplicate() {
		return new HttpServerBind(new HttpServerConfig(config));
	}

	static final int DEFAULT_PORT = 0;
}
