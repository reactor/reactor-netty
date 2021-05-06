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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import reactor.core.Exceptions;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.netty.channel.ChannelMetricsHandler;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import static reactor.netty.Metrics.ERROR;
import static reactor.netty.Metrics.SUCCESS;
import static reactor.netty.ReactorNetty.format;

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

	public static SslProvider updateDefaultConfiguration(SslProvider provider, DefaultConfigurationType type) {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(type, "type");
		return new SslProvider(provider, type);
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
		 * Note: This configuration is applicable only when configuring the server.
		 *
		 * @param domainName the domain name, it may contain wildcard
		 * @param sslProviderBuilder an {@link SslProvider} builder for building the {@link SslProvider}
		 * @return {@literal this}
		 */
		Builder addSniMapping(String domainName, Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder);

		/**
		 * Adds the provided mappings of domain names to {@link SslProvider} builders to the existing mappings.
		 * If a mapping already exists, it will be overridden.
		 * Note: This configuration is applicable only when configuring the server.
		 *
		 * @param confPerDomainName mappings of domain names to {@link SslProvider} builders
		 * @return {@literal this}
		 */
		Builder addSniMappings(Map<String, Consumer<? super SslContextSpec>> confPerDomainName);

		/**
		 * Sets the provided mappings of domain names to {@link SslProvider} builders.
		 * The existing mappings will be removed.
		 * Note: This configuration is applicable only when configuring the server.
		 *
		 * @param confPerDomainName mappings of domain names to {@link SslProvider} builders
		 * @return {@literal this}
		 */
		Builder setSniMappings(Map<String, Consumer<? super SslProvider.SslContextSpec>> confPerDomainName);

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
		 * As opposed to {@link #sslContext(SslContextBuilder)}, the default configuration is applied before
		 * any other custom configuration.
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

		/**
		 * The SslContextBuilder for building a new {@link SslContext}. The default configuration is applied after
		 * the custom configuration.
		 *
		 * @return {@literal this}
		 * @deprecated as of 1.0.6. Prefer {@link #sslContext(ProtocolSslContextSpec)}, where the default
		 * configuration is applied before any other custom configuration.
		 * This method will be removed in version 1.2.0.
		 */
		@Deprecated
		DefaultConfigurationSpec sslContext(SslContextBuilder sslCtxBuilder);
	}

	/**
	 * Default configuration that will be applied to the provided
	 * {@link SslContextBuilder}
	 * @deprecated as of 1.0.6. Prefer {@link SslProvider.SslContextSpec#sslContext(ProtocolSslContextSpec)},
	 * where the default configuration is applied before any other custom configuration.
	 * This will be removed in version 1.2.0.
	 */
	@Deprecated
	public enum DefaultConfigurationType {
		/**
		 * There will be no default configuration
		 */
		NONE,
		/**
		 * {@link io.netty.handler.ssl.SslProvider} will be set depending on
		 * <code>OpenSsl.isAvailable()</code>
		 */
		TCP,
		/**
		 * {@link io.netty.handler.ssl.SslProvider} will be set depending on
		 * <code>OpenSsl.isAlpnSupported()</code>,
		 * {@link #HTTP2_CIPHERS},
		 * ALPN support,
		 * HTTP/1.1 and HTTP/2 support
		 */
		H2
	}

	/**
	 * @deprecated as of 1.0.6. Prefer {@link SslProvider.SslContextSpec#sslContext(ProtocolSslContextSpec)},
	 * where the default configuration is applied before any other custom configuration.
	 * This will be removed in version 1.2.0.
	 */
	@Deprecated
	public interface DefaultConfigurationSpec {

		/**
		 * Default configuration type that will be applied to the provided
		 * {@link SslContextBuilder}
		 *
		 * @param type The default configuration type.
		 * @return {@code this}
		 */
		Builder defaultConfiguration(DefaultConfigurationType type);
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
	final DefaultConfigurationType     type;
	final long                         handshakeTimeoutMillis;
	final long                         closeNotifyFlushTimeoutMillis;
	final long                         closeNotifyReadTimeoutMillis;
	final Consumer<? super SslHandler> handlerConfigurator;
	final int                          builderHashCode;
	final SniProvider                  sniProvider;

	SslProvider(SslProvider.Build builder) {
		this.sslContextBuilder = builder.sslCtxBuilder;
		this.type = builder.type;
		if (builder.sslContext == null) {
			if (sslContextBuilder != null) {
				if (type != null) {
					updateDefaultConfiguration();
				}
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
		if (!builder.confPerDomainName.isEmpty()) {
			if (this.type != null) {
				this.sniProvider = updateAllSslProviderConfiguration(builder.confPerDomainName, this, type);
			}
			else {
				this.sniProvider = new SniProvider(builder.confPerDomainName, this);
			}
		}
		else {
			this.sniProvider = null;
		}
	}

	SslProvider(SslProvider from, Consumer<? super SslHandler> handlerConfigurator) {
		this.sslContext = from.sslContext;
		this.sslContextBuilder = from.sslContextBuilder;
		this.type = from.type;
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
		this.sniProvider = from.sniProvider;
	}

	SslProvider(SslProvider from, DefaultConfigurationType type) {
		this.sslContextBuilder = from.sslContextBuilder;
		this.type = type;
		if (this.sslContextBuilder != null) {
			updateDefaultConfiguration();
			try {
				this.sslContext = sslContextBuilder.build();
			}
			catch (SSLException e) {
				throw Exceptions.propagate(e);
			}
		}
		else {
			this.sslContext = from.sslContext;
		}
		this.handlerConfigurator = from.handlerConfigurator;
		this.handshakeTimeoutMillis = from.handshakeTimeoutMillis;
		this.closeNotifyFlushTimeoutMillis = from.closeNotifyFlushTimeoutMillis;
		this.closeNotifyReadTimeoutMillis = from.closeNotifyReadTimeoutMillis;
		this.builderHashCode = from.builderHashCode;
		if (from.sniProvider != null) {
			this.sniProvider = updateAllSslProviderConfiguration(from.sniProvider.confPerDomainName, this, type);
		}
		else {
			this.sniProvider = null;
		}
	}

	SniProvider updateAllSslProviderConfiguration(Map<String, SslProvider> confPerDomainName,
			SslProvider defaultSslProvider, SslProvider.DefaultConfigurationType type) {
		Map<String, SslProvider> config = new HashMap<>();
		confPerDomainName.forEach((s, sslProvider) ->
				config.put(s, SslProvider.updateDefaultConfiguration(sslProvider, type)));
		return new SniProvider(config, defaultSslProvider);
	}

	void updateDefaultConfiguration() {
		switch (type) {
			case H2:
				sslContextBuilder.sslProvider(
				                     io.netty.handler.ssl.SslProvider.isAlpnSupported(io.netty.handler.ssl.SslProvider.OPENSSL) ?
				                             io.netty.handler.ssl.SslProvider.OPENSSL :
				                             io.netty.handler.ssl.SslProvider.JDK)
				                 .ciphers(HTTP2_CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
				                 .applicationProtocolConfig(new ApplicationProtocolConfig(
				                     ApplicationProtocolConfig.Protocol.ALPN,
				                     ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
				                     ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
				                     ApplicationProtocolNames.HTTP_2,
				                     ApplicationProtocolNames.HTTP_1_1));
				break;
			case TCP:
				sslContextBuilder.sslProvider(
				                     OpenSsl.isAvailable() ?
				                             io.netty.handler.ssl.SslProvider.OPENSSL :
				                             io.netty.handler.ssl.SslProvider.JDK)
				                 .ciphers(null, IdentityCipherSuiteFilter.INSTANCE)
				                 .applicationProtocolConfig(null);
				break;
			case NONE:
				break; //no default configuration
		}
	}

	/**
	 * Returns {@code SslContext} instance with configured settings.
	 *
	 * @return {@code SslContext} instance with configured settings.
	 */
	public SslContext getSslContext() {
		return this.sslContext;
	}

	/**
	 * Returns the configured default configuration type.
	 *
	 * @return the configured default configuration type.
	 */
	@Nullable
	public DefaultConfigurationType getDefaultConfigurationType() {
		return this.type;
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

		if (remoteAddress instanceof InetSocketAddress) {
			InetSocketAddress sniInfo = (InetSocketAddress) remoteAddress;
			sslHandler = getSslContext()
					.newHandler(channel.alloc(), sniInfo.getHostString(), sniInfo.getPort());

			if (log.isDebugEnabled()) {
				log.debug(format(channel, "SSL enabled using engine {} and SNI {}"), sslHandler.engine(), sniInfo);
			}
		}
		else {
			sslHandler = getSslContext().newHandler(channel.alloc());

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
				"type=" + type +
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

	static final class Build implements SslContextSpec, DefaultConfigurationSpec, Builder {

		/**
		 * Default SSL handshake timeout (milliseconds), fallback to 10 seconds
		 */
		static final long DEFAULT_SSL_HANDSHAKE_TIMEOUT =
				Long.parseLong(System.getProperty(
						ReactorNetty.SSL_HANDSHAKE_TIMEOUT,
						"10000"));

		SslContextBuilder sslCtxBuilder;
		ProtocolSslContextSpec protocolSslContextSpec;
		DefaultConfigurationType type;
		SslContext sslContext;
		Consumer<? super SslHandler> handlerConfigurator;
		long handshakeTimeoutMillis = DEFAULT_SSL_HANDSHAKE_TIMEOUT;
		long closeNotifyFlushTimeoutMillis = 3000L;
		long closeNotifyReadTimeoutMillis;
		List<SNIServerName> serverNames;
		final Map<String, SslProvider> confPerDomainName = new HashMap<>();

		// SslContextSpec

		@Override
		public Builder sslContext(ProtocolSslContextSpec protocolSslContextSpec) {
			this.protocolSslContextSpec = protocolSslContextSpec;
			this.type = DefaultConfigurationType.NONE;
			return this;
		}

		@Override
		public final Builder sslContext(SslContext sslContext) {
			this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
			this.type = DefaultConfigurationType.NONE;
			return this;
		}

		@Override
		public final DefaultConfigurationSpec sslContext(SslContextBuilder sslCtxBuilder) {
			this.sslCtxBuilder = Objects.requireNonNull(sslCtxBuilder, "sslCtxBuilder");
			return this;
		}

		//DefaultConfigurationSpec

		@Override
		public final Builder defaultConfiguration(DefaultConfigurationType type) {
			this.type = Objects.requireNonNull(type, "type");
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
			return this;
		}

		@Override
		public Builder addSniMappings(Map<String, Consumer<? super SslContextSpec>> confPerDomainName) {
			Objects.requireNonNull(confPerDomainName);
			confPerDomainName.forEach(this::addInternal);
			return this;
		}

		@Override
		public Builder setSniMappings(Map<String, Consumer<? super SslContextSpec>> confPerDomainName) {
			Objects.requireNonNull(confPerDomainName);
			this.confPerDomainName.clear();
			confPerDomainName.forEach(this::addInternal);
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
			if (!(o instanceof Build)) {
				return false;
			}
			Build build = (Build) o;
			return handshakeTimeoutMillis == build.handshakeTimeoutMillis &&
					closeNotifyFlushTimeoutMillis == build.closeNotifyFlushTimeoutMillis &&
					closeNotifyReadTimeoutMillis == build.closeNotifyReadTimeoutMillis &&
					Objects.equals(sslCtxBuilder, build.sslCtxBuilder) &&
					type == build.type &&
					Objects.equals(sslContext, build.sslContext) &&
					Objects.equals(handlerConfigurator, build.handlerConfigurator) &&
					Objects.equals(serverNames, build.serverNames) &&
					confPerDomainName.equals(build.confPerDomainName) &&
					Objects.equals(protocolSslContextSpec, build.protocolSslContextSpec);
		}

		@Override
		public int hashCode() {
			return Objects.hash(sslCtxBuilder, type, sslContext, handlerConfigurator,
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

	static final class SslReadHandler extends ChannelInboundHandlerAdapter {

		boolean handshakeDone;

		ChannelMetricsRecorder recorder;

		long tlsHandshakeTimeStart;

		@Override
		public void channelRegistered(ChannelHandlerContext ctx) {
			ChannelHandler handler = ctx.pipeline().get(NettyPipeline.ChannelMetricsHandler);
			if (handler != null) {
				recorder = ((ChannelMetricsHandler) handler).recorder();
				tlsHandshakeTimeStart = System.nanoTime();
			}

			ctx.fireChannelRegistered();
		}

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
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
			if (evt instanceof SslHandshakeCompletionEvent) {
				handshakeDone = true;
				if (ctx.pipeline()
				       .context(this) != null) {
					ctx.pipeline()
					   .remove(this);
				}
				SslHandshakeCompletionEvent handshake = (SslHandshakeCompletionEvent) evt;
				if (handshake.isSuccess()) {
					if (recorder != null) {
						recorder.recordTlsHandshakeTime(
								ctx.channel().remoteAddress(),
								Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
								SUCCESS);
					}
					ctx.fireChannelActive();
				}
				else {
					if (recorder != null) {
						recorder.recordTlsHandshakeTime(
								ctx.channel().remoteAddress(),
								Duration.ofNanos(System.nanoTime() - tlsHandshakeTimeStart),
								ERROR);
					}
					ctx.fireExceptionCaught(handshake.cause());
				}
			}
			ctx.fireUserEventTriggered(evt);
		}

	}

	static final Logger log = Loggers.getLogger(SslProvider.class);

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedByteBufFormat.HEX_DUMP
					.toLoggingHandler("reactor.netty.tcp.ssl", LogLevel.DEBUG, Charset.defaultCharset());

	/**
	 * <a href="https://wiki.mozilla.org/Security/Server_Side_TLS#Modern_compatibility">Mozilla Modern Cipher
	 * Suites</a> minus the following cipher suites that are black listed by the
	 * <a href="https://tools.ietf.org/html/rfc7540#appendix-A">HTTP/2 RFC</a>.
	 * Copied from io.netty.handler.codec.http2.Http2SecurityUtil
	 */
	static final List<String> HTTP2_CIPHERS =
			Collections.unmodifiableList(Arrays.asList(
					/* openssl = ECDHE-ECDSA-AES128-GCM-SHA256 */
					"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",

					/* REQUIRED BY HTTP/2 SPEC */
					/* openssl = ECDHE-RSA-AES128-GCM-SHA256 */
					"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
					/* REQUIRED BY HTTP/2 SPEC */

					/* openssl = ECDHE-ECDSA-AES256-GCM-SHA384 */
					"TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
					/* openssl = ECDHE-RSA-AES256-GCM-SHA384 */
					"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
					/* openssl = ECDHE-ECDSA-CHACHA20-POLY1305 */
					"TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
					/* openssl = ECDHE-RSA-CHACHA20-POLY1305 */
					"TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",

					/* TLS 1.3 ciphers */
					"TLS_AES_128_GCM_SHA256",
					"TLS_AES_256_GCM_SHA384",
					"TLS_CHACHA20_POLY1305_SHA256"));
}