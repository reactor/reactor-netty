/*
 * Copyright (c) 2017-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.tcp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelPipeline;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.logging.LoggingHandler;
import io.netty5.handler.ssl.SslContext;
import io.netty5.handler.ssl.SslContextBuilder;
import io.netty5.handler.ssl.SslHandler;
import io.netty5.handler.ssl.SslHandshakeCompletionEvent;
import io.netty5.util.AsyncMapping;
import reactor.core.Exceptions;
import reactor.netty5.NettyPipeline;
import reactor.netty5.ReactorNetty;
import reactor.netty5.transport.logging.AdvancedBufferFormat;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static reactor.netty5.ReactorNetty.format;

/**
 * SSL Provider
 *
 * @author Violeta Georgieva
 */
public final class SslProvider {

	/**
	 * Creates a builder for {@link SslProvider SslProvider}
	 *
	 * @return a new SslProvider builder
	 */
	public static SslProvider.SslContextSpec builder() {
		return new SslProvider.Build();
	}

	/**
	 * Creates a new {@link SslProvider SslProvider} with a prepending handler
	 * configurator callback to inject default settings to an existing provider
	 * configuration.
	 *
	 * @return a new SslProvider
	 */
	public static SslProvider addHandlerConfigurator(
			SslProvider provider, Consumer<? super SslHandler> handlerConfigurator) {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(handlerConfigurator, "handlerConfigurator");
		return new SslProvider(provider, handlerConfigurator);
	}

	/**
	 * Return the default client ssl provider
	 *
	 * @return default client ssl provider
	 */
	public static SslProvider defaultClientProvider() {
		return TcpClientSecure.DEFAULT_SSL_PROVIDER;
	}

	public interface Builder {

		/**
		 * Set a configurator callback to mutate any property from the provided
		 * {@link SslHandler}
		 *
		 * @param handlerConfigurator A callback given the generated {@link SslHandler}
		 *
		 * @return {@literal this}
		 */
		Builder handlerConfigurator(Consumer<? super SslHandler> handlerConfigurator);

		/**
		 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
		 *
		 * @param handshakeTimeout The timeout {@link Duration}
		 *
		 * @return {@literal this}
		 */
		Builder handshakeTimeout(Duration handshakeTimeout);

		/**
		 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
		 *
		 * @param handshakeTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@literal this}
		 */
		Builder handshakeTimeoutMillis(long handshakeTimeoutMillis);

		/**
		 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
		 *
		 * @param closeNotifyFlushTimeout The timeout {@link Duration}
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyFlushTimeout(Duration closeNotifyFlushTimeout);

		/**
		 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
		 *
		 * @param closeNotifyFlushTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyFlushTimeoutMillis(long closeNotifyFlushTimeoutMillis);

		/**
		 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
		 *
		 * @param closeNotifyReadTimeout The timeout {@link Duration}
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyReadTimeout(Duration closeNotifyReadTimeout);

		/**
		 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
		 *
		 * @param closeNotifyReadTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@literal this}
		 */
		Builder closeNotifyReadTimeoutMillis(long closeNotifyReadTimeoutMillis);

		/**
		 * Adds a mapping for the given domain name to an {@link SslProvider} builder.
		 * If a mapping already exists, it will be overridden.
		 * <p><strong>Note:</strong> This method is a sync alternative of {@link #setSniAsyncMappings(AsyncMapping)},
		 * which removes the async mappings.
		 * <p><strong>Note:</strong> This configuration is applicable only when configuring the server.
		 *
		 * @param domainName the domain name, it may contain wildcard
		 * @param sslProviderBuilder an {@link SslProvider} builder for building the {@link SslProvider}
		 * @return {@literal this}
		 */
		Builder addSniMapping(String domainName, Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder);

		/**
		 * Adds the provided mappings of domain names to {@link SslProvider} builders to the existing mappings.
		 * If a mapping already exists, it will be overridden.
		 * <p><strong>Note:</strong> This method is a sync alternative of {@link #setSniAsyncMappings(AsyncMapping)},
		 * which removes the async mappings.
		 * <p><strong>Note:</strong> This configuration is applicable only when configuring the server.
		 *
		 * @param confPerDomainName mappings of domain names to {@link SslProvider} builders
		 * @return {@literal this}
		 */
		Builder addSniMappings(Map<String, Consumer<? super SslContextSpec>> confPerDomainName);

		/**
		 * Sets the provided mappings of domain names to {@link SslProvider} builders.
		 * The existing mappings will be removed.
		 * <p><strong>Note:</strong> This method is a sync alternative of {@link #setSniAsyncMappings(AsyncMapping)},
		 * which removes the async mappings.
		 * <p><strong>Note:</strong> This configuration is applicable only when configuring the server.
		 *
		 * @param confPerDomainName mappings of domain names to {@link SslProvider} builders
		 * @return {@literal this}
		 */
		Builder setSniMappings(Map<String, Consumer<? super SslProvider.SslContextSpec>> confPerDomainName);

		/**
		 * Sets the provided mappings of domain names to {@link SslProvider}.
		 * <p><strong>Note:</strong> This method is an alternative of {@link #addSniMapping(String, Consumer)},
		 * {@link #addSniMappings(Map)} and {@link #setSniMappings(Map)}.
		 * <p><strong>Note:</strong> This configuration is applicable only when configuring the server.
		 *
		 * @param mappings mappings of domain names to {@link SslProvider}
		 * @return {@literal this}
		 * @since 1.0.19
		 */
		Builder setSniAsyncMappings(AsyncMapping<String, SslProvider> mappings);

		/**
		 * Sets the desired {@link SNIServerName}s.
		 * Note: This configuration is applicable only when configuring the client.
		 *
		 * @param serverNames the desired {@link SNIServerName}s
		 * @return {@literal this}
		 */
		Builder serverNames(SNIServerName... serverNames);

		/**
		 * Builds new SslProvider
		 *
		 * @return builds new SslProvider
		 */
		SslProvider build();
	}

	public interface SslContextSpec {

		/**
		 * SslContext builder that provides, specific for the protocol, default configuration
		 * e.g. {@link DefaultSslContextSpec}, {@link TcpSslContextSpec} etc.
		 * The default configuration is applied before any other custom configuration.
		 *
		 * @param spec SslContext builder that provides, specific for the protocol, default configuration
		 * @return {@literal this}
		 * @since 1.0.6
		 */
		Builder sslContext(ProtocolSslContextSpec spec);

		/**
		 * The SslContext to set when configuring SSL
		 *
		 * @param sslContext The context to set when configuring SSL
		 *
		 * @return {@literal this}
		 */
		Builder sslContext(SslContext sslContext);
	}

	/**
	 * SslContext builder that provides, specific for the protocol, default configuration.
	 * The default configuration is applied prior any other custom configuration.
	 *
	 * @since 1.0.6
	 */
	public interface ProtocolSslContextSpec {

		/**
		 * Configures the underlying {@link SslContextBuilder}.
		 *
		 * @param sslCtxBuilder a callback for configuring the underlying {@link SslContextBuilder}
		 * @return {@code this}
		 */
		ProtocolSslContextSpec configure(Consumer<SslContextBuilder> sslCtxBuilder);

		/**
		 * Create a new {@link SslContext} instance with the configured settings.
		 *
		 * @return a new {@link SslContext} instance
		 * @throws SSLException thrown when {@link SslContext} instance cannot be created
		 */
		SslContext sslContext() throws SSLException;
	}

	final SslContext                   sslContext;
	final SslContextBuilder            sslContextBuilder;
	final long                         handshakeTimeoutMillis;
	final long                         closeNotifyFlushTimeoutMillis;
	final long                         closeNotifyReadTimeoutMillis;
	final Consumer<? super SslHandler> handlerConfigurator;
	final int                          builderHashCode;
	final SniProvider                  sniProvider;
	final Map<String, SslProvider>     confPerDomainName;
	final AsyncMapping<String, SslProvider> sniMappings;

	SslProvider(SslProvider.Build builder) {
		this.sslContextBuilder = builder.sslCtxBuilder;
		if (builder.sslContext == null) {
			if (sslContextBuilder != null) {
				try {
					this.sslContext = sslContextBuilder.build();
				}
				catch (SSLException e) {
					throw Exceptions.propagate(e);
				}
			}
			else if (builder.protocolSslContextSpec != null) {
				try {
					this.sslContext = builder.protocolSslContextSpec.sslContext();
				}
				catch (SSLException e) {
					throw Exceptions.propagate(e);
				}
			}
			else {
				throw new IllegalArgumentException("Neither SslContextBuilder nor SslContext is specified");
			}
		}
		else {
			this.sslContext = builder.sslContext;
		}
		if (builder.serverNames != null) {
			Consumer<SslHandler> configurator =
					h -> {
						SSLEngine engine = h.engine();
						SSLParameters sslParameters = engine.getSSLParameters();
						sslParameters.setServerNames(builder.serverNames);
						engine.setSSLParameters(sslParameters);
					};
			this.handlerConfigurator = builder.handlerConfigurator == null ? configurator :
					configurator.andThen(builder.handlerConfigurator);
		}
		else {
			this.handlerConfigurator = builder.handlerConfigurator;
		}
		this.handshakeTimeoutMillis = builder.handshakeTimeoutMillis;
		this.closeNotifyFlushTimeoutMillis = builder.closeNotifyFlushTimeoutMillis;
		this.closeNotifyReadTimeoutMillis = builder.closeNotifyReadTimeoutMillis;
		this.builderHashCode = builder.hashCode();
		this.confPerDomainName = builder.confPerDomainName;
		this.sniMappings = builder.sniMappings;
		if (!confPerDomainName.isEmpty()) {
			this.sniProvider = new SniProvider(confPerDomainName, this);
		}
		else if (sniMappings != null) {
			this.sniProvider = new SniProvider(sniMappings);
		}
		else {
			this.sniProvider = null;
		}
	}

	SslProvider(SslProvider from, Consumer<? super SslHandler> handlerConfigurator) {
		this.sslContext = from.sslContext;
		this.sslContextBuilder = from.sslContextBuilder;
		if (from.handlerConfigurator == null) {
			this.handlerConfigurator = handlerConfigurator;
		}
		else {
			this.handlerConfigurator = h -> {
				handlerConfigurator.accept(h);
				from.handlerConfigurator.accept(h);
			};
		}
		this.handshakeTimeoutMillis = from.handshakeTimeoutMillis;
		this.closeNotifyFlushTimeoutMillis = from.closeNotifyFlushTimeoutMillis;
		this.closeNotifyReadTimeoutMillis = from.closeNotifyReadTimeoutMillis;
		this.builderHashCode = from.builderHashCode;
		this.confPerDomainName = from.confPerDomainName;
		this.sniMappings = from.sniMappings;
		this.sniProvider = from.sniProvider;
	}

	/**
	 * Returns {@code SslContext} instance with configured settings.
	 *
	 * @return {@code SslContext} instance with configured settings.
	 */
	public SslContext getSslContext() {
		return this.sslContext;
	}

	public void configure(SslHandler sslHandler) {
		Objects.requireNonNull(sslHandler, "sslHandler");
		sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
		sslHandler.setCloseNotifyFlushTimeoutMillis(closeNotifyFlushTimeoutMillis);
		sslHandler.setCloseNotifyReadTimeoutMillis(closeNotifyReadTimeoutMillis);
		if (handlerConfigurator != null) {
			handlerConfigurator.accept(sslHandler);
		}
	}

	public void addSslHandler(Channel channel, @Nullable SocketAddress remoteAddress, boolean sslDebug) {
		Objects.requireNonNull(channel, "channel");
		if (sniProvider != null) {
			sniProvider.addSniHandler(channel, sslDebug);
			return;
		}

		SslHandler sslHandler;

		if (remoteAddress instanceof InetSocketAddress sniInfo) {
			sslHandler = getSslContext()
					.newHandler(channel.bufferAllocator(), sniInfo.getHostString(), sniInfo.getPort());

			if (log.isDebugEnabled()) {
				log.debug(format(channel, "SSL enabled using engine {} and SNI {}"), sslHandler.engine(), sniInfo);
			}
		}
		else {
			sslHandler = getSslContext().newHandler(channel.bufferAllocator());

			if (log.isDebugEnabled()) {
				log.debug(format(channel, "SSL enabled using engine {}"), sslHandler.engine());
			}
		}

		configure(sslHandler);

		ChannelPipeline pipeline = channel.pipeline();
		if (pipeline.get(NettyPipeline.ProxyHandler) != null) {
			pipeline.addAfter(NettyPipeline.ProxyHandler, NettyPipeline.SslHandler, sslHandler);
		}
		else if (pipeline.get(NettyPipeline.NonSslRedirectDetector) != null) {
			pipeline.addAfter(NettyPipeline.NonSslRedirectDetector, NettyPipeline.SslHandler, sslHandler);
		}
		else {
			pipeline.addFirst(NettyPipeline.SslHandler, sslHandler);
		}

		addSslReadHandler(pipeline, sslDebug);
	}

	@Override
	public String toString() {
		return "SslProvider {" +
				", handshakeTimeoutMillis=" + handshakeTimeoutMillis +
				", closeNotifyFlushTimeoutMillis=" + closeNotifyFlushTimeoutMillis +
				", closeNotifyReadTimeoutMillis=" + closeNotifyReadTimeoutMillis +
				'}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		SslProvider that = (SslProvider) o;
		return builderHashCode == that.builderHashCode;
	}

	@Override
	public int hashCode() {
		return Objects.hash(builderHashCode);
	}

	static void addSslReadHandler(ChannelPipeline pipeline, boolean sslDebug) {
		if (pipeline.get(NettyPipeline.LoggingHandler) != null) {
			pipeline.addAfter(NettyPipeline.LoggingHandler, NettyPipeline.SslReader, new SslReadHandler());
			if (sslDebug) {
				pipeline.addBefore(NettyPipeline.SslHandler, NettyPipeline.SslLoggingHandler, LOGGING_HANDLER);
			}
		}
		else {
			pipeline.addAfter(NettyPipeline.SslHandler, NettyPipeline.SslReader, new SslReadHandler());
		}
	}

	static final class Build implements SslContextSpec, Builder {

		/**
		 * Default SSL handshake timeout (milliseconds), fallback to 10 seconds
		 */
		static final long DEFAULT_SSL_HANDSHAKE_TIMEOUT =
				Long.parseLong(System.getProperty(
						ReactorNetty.SSL_HANDSHAKE_TIMEOUT,
						"10000"));

		SslContextBuilder sslCtxBuilder;
		ProtocolSslContextSpec protocolSslContextSpec;
		SslContext sslContext;
		Consumer<? super SslHandler> handlerConfigurator;
		long handshakeTimeoutMillis = DEFAULT_SSL_HANDSHAKE_TIMEOUT;
		long closeNotifyFlushTimeoutMillis = 3000L;
		long closeNotifyReadTimeoutMillis;
		List<SNIServerName> serverNames;
		final Map<String, SslProvider> confPerDomainName = new HashMap<>();
		AsyncMapping<String, SslProvider> sniMappings;

		// SslContextSpec

		@Override
		public Builder sslContext(ProtocolSslContextSpec protocolSslContextSpec) {
			this.protocolSslContextSpec = protocolSslContextSpec;
			return this;
		}

		@Override
		public final Builder sslContext(SslContext sslContext) {
			this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
			return this;
		}

		// Builder

		@Override
		public final Builder handshakeTimeout(Duration handshakeTimeout) {
			Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
			return handshakeTimeoutMillis(handshakeTimeout.toMillis());
		}

		@Override
		public final Builder handlerConfigurator(Consumer<? super SslHandler> handlerConfigurator) {
			Objects.requireNonNull(handlerConfigurator, "handshakeTimeout");
			this.handlerConfigurator = handlerConfigurator;
			return this;
		}

		@Override
		public final Builder handshakeTimeoutMillis(long handshakeTimeoutMillis) {
			if (handshakeTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl handshake timeout must be positive"
						+ " was: " + handshakeTimeoutMillis);
			}
			this.handshakeTimeoutMillis = handshakeTimeoutMillis;
			return this;
		}

		@Override
		public final Builder closeNotifyFlushTimeout(Duration closeNotifyFlushTimeout) {
			Objects.requireNonNull(closeNotifyFlushTimeout, "closeNotifyFlushTimeout");
			return closeNotifyFlushTimeoutMillis(closeNotifyFlushTimeout.toMillis());
		}

		@Override
		public final Builder closeNotifyFlushTimeoutMillis(long closeNotifyFlushTimeoutMillis) {
			if (closeNotifyFlushTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl close_notify flush timeout must be positive,"
						+ " was: " + closeNotifyFlushTimeoutMillis);
			}
			this.closeNotifyFlushTimeoutMillis = closeNotifyFlushTimeoutMillis;
			return this;
		}

		@Override
		public final Builder closeNotifyReadTimeout(Duration closeNotifyReadTimeout) {
			Objects.requireNonNull(closeNotifyReadTimeout, "closeNotifyReadTimeout");
			return closeNotifyReadTimeoutMillis(closeNotifyReadTimeout.toMillis());
		}

		@Override
		public final Builder closeNotifyReadTimeoutMillis(long closeNotifyReadTimeoutMillis) {
			if (closeNotifyReadTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl close_notify read timeout must be positive,"
						+ " was: " + closeNotifyReadTimeoutMillis);
			}
			this.closeNotifyReadTimeoutMillis = closeNotifyReadTimeoutMillis;
			return this;
		}

		@Override
		public Builder addSniMapping(String domainName, Consumer<? super SslContextSpec> sslProviderBuilder) {
			addInternal(domainName, sslProviderBuilder);
			this.sniMappings = null;
			return this;
		}

		@Override
		public Builder addSniMappings(Map<String, Consumer<? super SslContextSpec>> confPerDomainName) {
			Objects.requireNonNull(confPerDomainName);
			confPerDomainName.forEach(this::addInternal);
			this.sniMappings = null;
			return this;
		}

		@Override
		public Builder setSniMappings(Map<String, Consumer<? super SslContextSpec>> confPerDomainName) {
			Objects.requireNonNull(confPerDomainName);
			this.confPerDomainName.clear();
			confPerDomainName.forEach(this::addInternal);
			this.sniMappings = null;
			return this;
		}

		@Override
		public Builder setSniAsyncMappings(AsyncMapping<String, SslProvider> mappings) {
			this.sniMappings = Objects.requireNonNull(mappings);
			this.confPerDomainName.clear();
			return this;
		}

		@Override
		public Builder serverNames(SNIServerName... serverNames) {
			Objects.requireNonNull(serverNames);
			this.serverNames = Arrays.asList(serverNames);
			return this;
		}

		@Override
		public SslProvider build() {
			return new SslProvider(this);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Build build)) {
				return false;
			}
			return handshakeTimeoutMillis == build.handshakeTimeoutMillis &&
					closeNotifyFlushTimeoutMillis == build.closeNotifyFlushTimeoutMillis &&
					closeNotifyReadTimeoutMillis == build.closeNotifyReadTimeoutMillis &&
					Objects.equals(sslCtxBuilder, build.sslCtxBuilder) &&
					Objects.equals(sslContext, build.sslContext) &&
					Objects.equals(handlerConfigurator, build.handlerConfigurator) &&
					Objects.equals(serverNames, build.serverNames) &&
					confPerDomainName.equals(build.confPerDomainName) &&
					Objects.equals(protocolSslContextSpec, build.protocolSslContextSpec);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sslCtxBuilder, sslContext, handlerConfigurator,
					handshakeTimeoutMillis, closeNotifyFlushTimeoutMillis, closeNotifyReadTimeoutMillis,
					serverNames, confPerDomainName, protocolSslContextSpec);
		}

		void addInternal(String domainName, Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
			Objects.requireNonNull(domainName, "domainName");
			Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");
			SslProvider.SslContextSpec builder = SslProvider.builder();
			sslProviderBuilder.accept(builder);
			confPerDomainName.put(domainName, ((SslProvider.Builder) builder).build());
		}
	}

	static final class SslReadHandler extends ChannelHandlerAdapter {
		boolean handshakeDone;

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			ctx.read(); //consume handshake
		}

		@Override
		public void channelReadComplete(ChannelHandlerContext ctx) {
			if (!handshakeDone) {
				ctx.read(); /* continue consuming. */
			}
			ctx.fireChannelReadComplete();
		}

		@Override
		public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) {
			if (evt instanceof SslHandshakeCompletionEvent handshake) {
				handshakeDone = true;
				if (handshake.isSuccess()) {
					ctx.fireChannelActive();
				}
				else {
					ctx.fireChannelExceptionCaught(handshake.cause());
				}
			}
			ctx.fireChannelInboundEvent(evt);
			if (handshakeDone && ctx.pipeline().context(this) != null) {
				ctx.pipeline().remove(this);
			}
		}
	}

	static final Logger log = Loggers.getLogger(SslProvider.class);

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedBufferFormat.HEX_DUMP
					.toLoggingHandler("reactor.netty5.tcp.ssl", LogLevel.DEBUG, Charset.defaultCharset());
}
