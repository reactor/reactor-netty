/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
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
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import reactor.core.Exceptions;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;
import reactor.util.Logger;
import reactor.util.Loggers;

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
		return DEFAULT_CLIENT_PROVIDER;
	}

	/**
	 * Add Ssl support on the given client bootstrap
	 * @param b a given bootstrap to enrich
	 *
	 * @return an enriched bootstrap
	 */
	public static Bootstrap updateSslSupport(Bootstrap b, SslProvider sslProvider) {
		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.SslHandler,
				new DeferredSslSupport(sslProvider));

		return b;
	}

	/**
	 * Find Ssl support in the given client bootstrap
	 *
	 * @param b a bootstrap to search
	 *
	 * @return any {@link SslProvider} found or null
	 */
	@Nullable
	public static SslProvider findSslSupport(Bootstrap b) {
		SslSupportConsumer ssl = BootstrapHandlers.findConfiguration(SslSupportConsumer.class, b.config().handler());

		if (ssl == null) {
			return null;
		}
		return ssl.sslProvider;
	}

	/**
	 * Find Ssl support in the given server bootstrap
	 *
	 * @param b a bootstrap to search
	 *
	 * @return any {@link SslProvider} found or null
	 */
	@Nullable
	public static SslProvider findSslSupport(ServerBootstrap b) {
		SslSupportConsumer ssl = BootstrapHandlers.findConfiguration(SslSupportConsumer.class, b.config().childHandler());

		if (ssl == null) {
			return null;
		}
		return ssl.sslProvider;
	}

	final SslContext                   sslContext;
	final long                         handshakeTimeoutMillis;
	final long                         closeNotifyFlushTimeoutMillis;
	final long                         closeNotifyReadTimeoutMillis;
	final Consumer<? super SslHandler> handlerConfigurator;

	SslProvider(SslProvider.Build builder) {
		this.sslContext = builder.sslContext;
		this.handlerConfigurator = builder.handlerConfigurator;
		this.handshakeTimeoutMillis = builder.handshakeTimeoutMillis;
		this.closeNotifyFlushTimeoutMillis = builder.closeNotifyFlushTimeoutMillis;
		this.closeNotifyReadTimeoutMillis = builder.closeNotifyReadTimeoutMillis;
	}

	SslProvider(SslProvider from, Consumer<? super SslHandler> handlerConfigurator) {
		this.sslContext = from.sslContext;
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
		sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
		sslHandler.setCloseNotifyFlushTimeoutMillis(closeNotifyFlushTimeoutMillis);
		sslHandler.setCloseNotifyReadTimeoutMillis(closeNotifyReadTimeoutMillis);
		if (handlerConfigurator != null) {
			handlerConfigurator.accept(sslHandler);
		}
	}


	public String asSimpleString() {
		return toString();
	}

	public String asDetailedString() {
		return "handshakeTimeoutMillis=" + this.handshakeTimeoutMillis +
				", closeNotifyFlushTimeoutMillis=" + this.closeNotifyFlushTimeoutMillis +
				", closeNotifyReadTimeoutMillis=" + this.closeNotifyReadTimeoutMillis;
	}

	@Override
	public String toString() {
		return "SslProvider{" + asDetailedString() + "}";
	}

	
	static final class Build implements SslContextSpec, Builder {

		static final long DEFAULT_SSL_HANDSHAKE_TIMEOUT =
				Long.parseLong(System.getProperty(
						"reactor..netty.sslHandshakeTimeout",
						"10000"));

		static final SelfSignedCertificate DEFAULT_SSL_CONTEXT_SELF;

		static {
			SelfSignedCertificate cert;
			try {
				cert = new SelfSignedCertificate();
			}
			catch (Exception e) {
				cert = null;
			}
			DEFAULT_SSL_CONTEXT_SELF = cert;
		}

		SslContextBuilder sslCtxBuilder;
		SslContext sslContext;
		Consumer<? super SslHandler> handlerConfigurator;
		long handshakeTimeoutMillis = DEFAULT_SSL_HANDSHAKE_TIMEOUT;
		long closeNotifyFlushTimeoutMillis = 3000L;
		long closeNotifyReadTimeoutMillis;

		@Override
		public final Builder forClient() {
			this.sslCtxBuilder = SslContextBuilder.forClient();
			return this;
		}

		@Override
		public final Builder forServer() {
			this.sslCtxBuilder = SslContextBuilder.forServer(
					DEFAULT_SSL_CONTEXT_SELF.certificate(),
					DEFAULT_SSL_CONTEXT_SELF.privateKey());
			return this;
		}

		@Override
		public final Builder sslContext(Consumer<? super SslContextBuilder> sslContextBuilder) {
			Objects.requireNonNull(sslContextBuilder, "sslContextBuilder");
			SslContext sslContext;
			try {
				sslContextBuilder.accept(this.sslCtxBuilder);
				sslContext = this.sslCtxBuilder.build();
			}
			catch (Exception sslException) {
				throw Exceptions.propagate(sslException);
			}
			return sslContext(sslContext);
		}

		@Override
		public final Builder sslContext(SslContext sslContext){
			this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
			return this;
		}

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
		public SslProvider build() {
			return new SslProvider(this);
		}
	}

	public interface Builder {

		/**
		 * Set a builder callback for further customization of SslContext
		 *
		 * @param sslContextBuilder builder callback for further customization of SslContext
		 *
		 * @return {@literal this}
		 */
		Builder sslContext(Consumer<? super SslContextBuilder> sslContextBuilder);

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
		 * Builds new SslProvider
		 *
		 * @return builds new SslProvider
		 */
		SslProvider build();
	}

	public interface SslContextSpec {

		/**
		 * The context to set when configuring SSL
		 * 
		 * @param sslContext The context to set when configuring SSL
		 * 
		 * @return {@literal this}
		 */
		Builder sslContext(SslContext sslContext);

		/**
		 * Creates SslContextBuilder for new client-side {@link SslContext}.
		 * 
		 * @return {@literal this}
		 */
		Builder forClient();

		/**
		 * Creates SslContextBuilder for new server-side {@link SslContext}.
		 * 
		 * @return {@literal this}
		 */
		Builder forServer();

	}

	@Nullable
	static SslContext findSslContext(Bootstrap b) {
		SslSupportConsumer c =
				BootstrapHandlers.findConfiguration(SslSupportConsumer.class,
						b.config().handler());

		return c != null ? c.sslProvider.getSslContext() : null;
	}

	@Nullable
	static SslContext findSslContext(ServerBootstrap b) {
		SslSupportConsumer c =
				BootstrapHandlers.findConfiguration(SslSupportConsumer.class,
						b.config().childHandler());

		return c != null ? c.sslProvider.getSslContext() : null;
	}

	static Bootstrap removeSslSupport(Bootstrap b) {
		BootstrapHandlers.removeConfiguration(b, NettyPipeline.SslHandler);
		return b;
	}

	static ServerBootstrap removeSslSupport(ServerBootstrap b) {
		BootstrapHandlers.removeConfiguration(b, NettyPipeline.SslHandler);
		return b;
	}

	static ServerBootstrap updateSslSupport(ServerBootstrap b, SslProvider sslProvider) {

		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.SslHandler,
				new SslSupportConsumer(sslProvider, null));

		return b;
	}

	static final class DeferredSslSupport implements Function<Bootstrap, BiConsumer<ConnectionObserver, Channel>>
	{
		final SslProvider sslProvider;

		DeferredSslSupport(SslProvider sslProvider) {
			this.sslProvider = sslProvider;
		}

		@Override
		public BiConsumer<ConnectionObserver, Channel> apply(Bootstrap bootstrap) {
			return new SslSupportConsumer(sslProvider, bootstrap.config().remoteAddress());
		}
	}

	static final class SslSupportConsumer
			implements BiConsumer<ConnectionObserver, Channel> {
		final SslProvider sslProvider;
		final InetSocketAddress sniInfo;

		SslSupportConsumer(SslProvider sslProvider, @Nullable SocketAddress sniInfo) {
			this.sslProvider = sslProvider;
			if (sniInfo instanceof InetSocketAddress) {
				this.sniInfo = (InetSocketAddress) sniInfo;
			}
			else {
				this.sniInfo = null;
			}
		}

		@Override
		public void accept(ConnectionObserver listener, Channel channel) {
			SslHandler sslHandler;

			if (sniInfo != null) {
				sslHandler = sslProvider.getSslContext()
				                        .newHandler(channel.alloc(),
						                        sniInfo.getHostString(),
						                        sniInfo.getPort());

				if (log.isDebugEnabled()) {
					log.debug("SSL enabled using engine {} and SNI {}",
							sslHandler.engine().getClass().getSimpleName(),
							sniInfo);
				}
			}
			else {
				sslHandler = sslProvider.getSslContext().newHandler(channel.alloc());

				if (log.isDebugEnabled()) {
					log.debug("SSL enabled using engine {}",
							sslHandler.engine().getClass().getSimpleName());
				}
			}

			sslProvider.configure(sslHandler);

			if (channel.pipeline()
			           .get(NettyPipeline.ProxyHandler) != null) {
				channel.pipeline()
				       .addAfter(NettyPipeline.ProxyHandler,
						       NettyPipeline.SslHandler,
						       sslHandler);
			}
			else {
				channel.pipeline()
				       .addFirst(NettyPipeline.SslHandler, sslHandler);
			}

			if (channel.pipeline()
			           .get(NettyPipeline.LoggingHandler) != null) {
				channel.pipeline()
				       .addAfter(NettyPipeline.LoggingHandler,
						       NettyPipeline.SslReader,
						       new SslReadHandler());
			}
			else {
				channel.pipeline()
				       .addAfter(NettyPipeline.SslHandler,
						       NettyPipeline.SslReader,
						       new SslReadHandler());
			}
		}

	}

	static final class SslReadHandler extends ChannelInboundHandlerAdapter {

		boolean handshakeDone;

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			ctx.read(); //consume handshake
		}

		@Override
		public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
			if (!handshakeDone) {
				ctx.read(); /* continue consuming. */
			}
			super.channelReadComplete(ctx);
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
				throws Exception {
			if (evt instanceof SslHandshakeCompletionEvent) {
				handshakeDone = true;
				if (ctx.pipeline()
				       .context(this) != null) {
					ctx.pipeline()
					   .remove(this);
				}
				SslHandshakeCompletionEvent handshake = (SslHandshakeCompletionEvent) evt;
				if (handshake.isSuccess()) {
					ctx.fireChannelActive();
				}
				else {
					ctx.fireExceptionCaught(handshake.cause());
				}
			}
			super.userEventTriggered(ctx, evt);
		}

	}

	static final Logger log = Loggers.getLogger(SslProvider.class);

	static final SslContext DEFAULT_CLIENT_CONTEXT;
	static final SslContext DEFAULT_SERVER_CONTEXT;

	static final SslProvider DEFAULT_CLIENT_PROVIDER;

	static {
		SslContext sslContext;
		try {
			io.netty.handler.ssl.SslProvider provider =
					OpenSsl.isAlpnSupported() ? io.netty.handler.ssl.SslProvider.OPENSSL :
							io.netty.handler.ssl.SslProvider.JDK;
			sslContext =
					SslContextBuilder.forClient()
					                 .sslProvider(provider)
					                 .trustManager(InsecureTrustManagerFactory.INSTANCE)
					                 .build();
		}
		catch (Exception e) {
			sslContext = null;
		}
		DEFAULT_CLIENT_CONTEXT = sslContext;

		SslProvider.Build builder = (SslProvider.Build) SslProvider.builder();
		DEFAULT_CLIENT_PROVIDER = builder.sslContext(DEFAULT_CLIENT_CONTEXT)
		                                 .build();

		SelfSignedCertificate cert;
		try {
			cert = new SelfSignedCertificate();
			io.netty.handler.ssl.SslProvider provider =
					OpenSsl.isAlpnSupported() ? io.netty.handler.ssl.SslProvider.OPENSSL :
							io.netty.handler.ssl.SslProvider.JDK;
			sslContext =
					SslContextBuilder.forServer(cert.certificate(), cert.privateKey())
					                 .sslProvider(provider)
					                 .trustManager(InsecureTrustManagerFactory.INSTANCE)
					                 .build();
		}
		catch (Exception e) {
			sslContext = null;
		}
		DEFAULT_SERVER_CONTEXT = sslContext;
	}


	static final Consumer<SslContextSpec> DEFAULT_SERVER_SPEC =
			sslProviderBuilder -> sslProviderBuilder.sslContext(DEFAULT_SERVER_CONTEXT);

}



