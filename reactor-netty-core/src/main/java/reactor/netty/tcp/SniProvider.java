/*
 * Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;
import reactor.netty.NettyPipeline;

import java.util.Map;
import java.util.function.Function;

/**
 * An {@link SniProvider} to configure the channel pipeline in order to support server SNI
 *
 * @author Violeta Georgieva
 * @since 1.0.0
 */
final class SniProvider {

	/**
	 * Adds configured {@link SniHandler} to the channel pipeline.
	 *
	 * @param channel the channel
	 * @param sslDebug if true SSL debugging on the server side will be enabled
	 */
	void addSniHandler(Channel channel, boolean sslDebug) {
		ChannelPipeline pipeline = channel.pipeline();
		if (pipeline.get(NettyPipeline.NonSslRedirectDetector) != null) {
			pipeline.addAfter(NettyPipeline.NonSslRedirectDetector, NettyPipeline.SslHandler, newSniHandler());
		}
		else {
			pipeline.addFirst(NettyPipeline.SslHandler, newSniHandler());
		}
		SslProvider.addSslReadHandler(pipeline, sslDebug);
	}

	final Map<String, SslProvider> confPerDomainName;
	final SslProvider defaultSslProvider;
	final Function<String, SslProvider> sniFallback;

	SniProvider(Map<String, SslProvider> confPerDomainName, SslProvider defaultSslProvider, Function<String,
			SslProvider> sniFallback) {
		this.confPerDomainName = confPerDomainName;
		this.defaultSslProvider = defaultSslProvider;
		this.sniFallback = sniFallback;
	}

	SniHandler newSniHandler() {
		DomainWildcardMappingBuilder<SslProvider> mappingsSslProviderBuilder =
				new DomainWildcardMappingBuilder<>(defaultSslProvider);
		confPerDomainName.forEach(mappingsSslProviderBuilder::add);
		return new SniFallbackHandler(mappingsSslProviderBuilder.build(), defaultSslProvider, this.sniFallback);
	}

	static final class SniFallbackHandler extends SniHandler {
		private final Mapping<String, SslProvider> mapping;
		private final SslProvider defaultSslProvider;
		private final Function<String, SslProvider> sniFallback;

		public SniFallbackHandler(Mapping<String, SslProvider> mapping, SslProvider defaultSslProvider,
		                          Function<String, SslProvider> sniFallback) {
			super((host, promise) -> {
				try {
					SslProvider provider = getProvider(host, mapping, defaultSslProvider, sniFallback);
					return promise.setSuccess(provider.getSslContext());
				}
				catch (Throwable cause) {
					return promise.setFailure(cause);
				}
			});
			this.mapping = mapping;
			this.defaultSslProvider = defaultSslProvider;
			this.sniFallback = sniFallback;
		}

		private static SslProvider getProvider(String host, Mapping<String, SslProvider> mapping,
		                                       SslProvider defaultSslProvider,
		                                       Function<String, SslProvider> sniFallback) {
			if (host == null) {
				return defaultSslProvider;
			}
			else if (mapping.map(host) != null) {
				return mapping.map(host);
			}
			else {
				SslProvider apply = sniFallback.apply(host);
				if (apply != null) {
					return apply;
				}
				else {
					return defaultSslProvider;
				}
			}
		}

		@Override
		protected SslHandler newSslHandler(SslContext context, ByteBufAllocator allocator) {
			SslHandler sslHandler = super.newSslHandler(context, allocator);
			String hostname = hostname();
			getProvider(hostname, this.mapping, this.defaultSslProvider, this.sniFallback).configure(sslHandler);
			return sslHandler;
		}
	}
}
