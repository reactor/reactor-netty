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
		pipeline.addFirst(NettyPipeline.SslHandler, newSniHandler());

		SslProvider.addSslReadHandler(pipeline, sslDebug);
	}

	final Map<String, SslProvider> confPerDomainName;
	final SslProvider defaultSslProvider;

	SniProvider(Map<String, SslProvider> confPerDomainName, SslProvider defaultSslProvider) {
		this.confPerDomainName = confPerDomainName;
		this.defaultSslProvider = defaultSslProvider;
	}

	SniHandler newSniHandler() {
		DomainWildcardMappingBuilder<SslContext> mappingsContextBuilder =
				new DomainWildcardMappingBuilder<>(defaultSslProvider.getSslContext());
		confPerDomainName.forEach((s, sslProvider) -> mappingsContextBuilder.add(s, sslProvider.getSslContext()));
		DomainWildcardMappingBuilder<SslProvider> mappingsSslProviderBuilder =
				new DomainWildcardMappingBuilder<>(defaultSslProvider);
		confPerDomainName.forEach(mappingsSslProviderBuilder::add);
		return new AdvancedSniHandler(mappingsSslProviderBuilder.build(), defaultSslProvider, mappingsContextBuilder.build());
	}

	static final class AdvancedSniHandler extends SniHandler {

		final Mapping<? super String, ? extends SslProvider> confPerDomainName;
		final SslProvider defaultSslProvider;

		AdvancedSniHandler(
				Mapping<? super String, ? extends SslProvider> confPerDomainName,
				SslProvider defaultSslProvider,
				Mapping<? super String, ? extends SslContext> mappings) {
			super(mappings);
			this.confPerDomainName = confPerDomainName;
			this.defaultSslProvider = defaultSslProvider;
		}

		@Override
		protected SslHandler newSslHandler(SslContext context, ByteBufAllocator allocator) {
			SslHandler sslHandler = super.newSslHandler(context, allocator);
			String hostName = hostname();
			if (hostName == null) {
				defaultSslProvider.configure(sslHandler);
			}
			else {
				confPerDomainName.map(hostname()).configure(sslHandler);
			}
			return sslHandler;
		}
	}
}
