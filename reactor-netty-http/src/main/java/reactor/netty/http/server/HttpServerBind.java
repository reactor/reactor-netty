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
import io.netty.util.AttributeKey;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.tcp.SslProvider;
import reactor.netty.tcp.TcpServerConfig;

import java.net.InetSocketAddress;
import java.util.Arrays;
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
				HttpServer dup = duplicate();
				HttpServerConfig _config = dup.configuration();
				if ((_config._protocols & HttpServerConfig.h2) == HttpServerConfig.h2) {
					_config.sslProvider = SslProvider.updateDefaultConfiguration(_config.sslProvider,
							SslProvider.DefaultConfigurationType.H2);
				}
				else {
					_config.sslProvider = SslProvider.updateDefaultConfiguration(_config.sslProvider,
							SslProvider.DefaultConfigurationType.TCP);
				}
				return dup.bind();
			}
			if ((config._protocols & HttpServerConfig.h2c) == HttpServerConfig.h2c) {
				return Mono.error(new IllegalArgumentException(
						"Configured H2 Clear-Text protocol with TLS. " +
								"Use the non Clear-Text H2 protocol via " +
								"HttpServer#protocol or disable TLS via HttpServer#noSSL())"));
			}
		}
		else {
			if ((config._protocols & HttpServerConfig.h2) == HttpServerConfig.h2) {
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
	public String toString() {
		return "HttpServer{" +
				"protocols=" + Arrays.asList(configuration().protocols) +
				", secure=" + configuration().isSecure() +
				'}';
	}

	@Override
	protected HttpServer duplicate() {
		return new HttpServerBind(new HttpServerConfig(config));
	}

	@SuppressWarnings("unchecked")
	static HttpServer applyTcpServerConfig(TcpServerConfig config) {
		HttpServer httpServer =
				create().childObserve(config.childObserver())
				        .doOnChannelInit(config.doOnChannelInit())
				        .observe(config.connectionObserver())
				        .runOn(config.loopResources(), config.isPreferNative());

		for (Map.Entry<AttributeKey<?>, ?> entry : config.attributes().entrySet()) {
			httpServer = httpServer.attr((AttributeKey<Object>) entry.getKey(), entry.getValue());
		}

		if (config.bindAddress() != null) {
			httpServer = httpServer.bindAddress(config.bindAddress());
		}

		if (config.channelGroup() != null) {
			httpServer = httpServer.channelGroup(config.channelGroup());
		}

		for (Map.Entry<AttributeKey<?>, ?> entry : config.childAttributes().entrySet()) {
			httpServer = httpServer.childAttr((AttributeKey<Object>) entry.getKey(), entry.getValue());
		}

		for (Map.Entry<ChannelOption<?>, ?> entry : config.childOptions().entrySet()) {
			httpServer = httpServer.childOption((ChannelOption<Object>) entry.getKey(), entry.getValue());
		}

		if (config.doOnBound() != null) {
			httpServer = httpServer.doOnBound(config.doOnBound());
		}

		if (config.doOnConnection() != null) {
			httpServer = httpServer.doOnConnection(config.doOnConnection());
		}

		if (config.doOnUnbound() != null) {
			httpServer = httpServer.doOnUnbound(config.doOnUnbound());
		}

		if (config.loggingHandler() != null) {
			httpServer.configuration().loggingHandler(config.loggingHandler());
		}

		if (config.metricsRecorder() != null) {
			httpServer = httpServer.metrics(true, config.metricsRecorder());
		}

		for (Map.Entry<ChannelOption<?>, ?> entry : config.options().entrySet()) {
			httpServer = httpServer.option((ChannelOption<Object>) entry.getKey(), entry.getValue());
		}

		if (config.sslProvider() != null) {
			httpServer.secure(config.sslProvider());
		}

		return httpServer;
	}

	static final int DEFAULT_PORT = 0;
}
