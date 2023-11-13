/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.AbstractSniHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsyncMapping;
import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;
import reactor.netty.NettyPipeline;

import java.util.Map;

/**
 * An {@link SniProvider} to configure the channel pipeline in order to support server SNI.
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

	final long handshakeTimeoutMillis;
	final AsyncMapping<String, SslProvider> mappings;

	SniProvider(AsyncMapping<String, SslProvider> mappings, long handshakeTimeoutMillis) {
		this.mappings = mappings;
		this.handshakeTimeoutMillis = handshakeTimeoutMillis;
	}

	SniProvider(Map<String, SslProvider> confPerDomainName, SslProvider defaultSslProvider) {
		DomainWildcardMappingBuilder<SslProvider> mappingsSslProviderBuilder =
				new DomainWildcardMappingBuilder<>(defaultSslProvider);
		confPerDomainName.forEach(mappingsSslProviderBuilder::add);
		this.mappings = new AsyncMappingAdapter(mappingsSslProviderBuilder.build());
		this.handshakeTimeoutMillis = defaultSslProvider.handshakeTimeoutMillis;
	}

	SniHandler newSniHandler() {
		return new SniHandler(mappings, handshakeTimeoutMillis);
	}

	static final class AsyncMappingAdapter implements AsyncMapping<String, SslProvider> {

		final Mapping<String, SslProvider> mapping;

		AsyncMappingAdapter(Mapping<String, SslProvider> mapping) {
			this.mapping = mapping;
		}

		@Override
		public Future<SslProvider> map(String input, Promise<SslProvider> promise) {
			try {
				return promise.setSuccess(mapping.map(input));
			}
			catch (Throwable cause) {
				return promise.setFailure(cause);
			}
		}
	}

	static final class SniHandler extends AbstractSniHandler<SslProvider> {

		final AsyncMapping<String, SslProvider> mappings;

		SniHandler(AsyncMapping<String, SslProvider> mappings, long handshakeTimeoutMillis) {
			super(handshakeTimeoutMillis);
			this.mappings = mappings;
		}

		@Override
		protected Future<SslProvider> lookup(ChannelHandlerContext ctx, String hostname) {
			return mappings.map(hostname, ctx.executor().newPromise());
		}

		@Override
		protected void onLookupComplete(ChannelHandlerContext ctx, String hostname, Future<SslProvider> future) {
			if (!future.isSuccess()) {
				final Throwable cause = future.cause();
				if (cause instanceof Error) {
					throw (Error) cause;
				}
				throw new DecoderException("failed to get the SslContext for " + hostname, cause);
			}

			SslProvider sslProvider = future.getNow();
			SslHandler sslHandler = null;
			try {
				sslHandler = sslProvider.getSslContext().newHandler(ctx.alloc());
				sslProvider.configure(sslHandler);
				ctx.pipeline().replace(this, SslHandler.class.getName(), sslHandler);
				sslHandler = null;
			}
			catch (Throwable cause) {
				PlatformDependent.throwException(cause);
			}
			finally {
				if (sslHandler != null) {
					ReferenceCountUtil.safeRelease(sslHandler.engine());
				}
			}
		}
	}
}
