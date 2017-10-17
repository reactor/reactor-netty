/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.options;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.resources.LoopResources;
import reactor.util.function.Tuple2;

/**
 * A common connector builder with low-level connection options including sslContext, tcp
 * configuration, channel init handlers.
 *
 * @param <BOOTSTRAP> A Netty {@link Bootstrap} type
 * @param <SO> A NettyOptions subclass
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public abstract class NettyOptions<BOOTSTRAP extends AbstractBootstrap<BOOTSTRAP, ?>, SO extends NettyOptions<BOOTSTRAP, SO>>
		implements Supplier<BOOTSTRAP> {

	/**
	 * The default port for reactor-netty servers. Defaults to 12012 but can be tuned via
	 * the {@code PORT} <b>environment variable</b>.
	 */
	public static final int     DEFAULT_PORT         =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) :
					12012;

	private final BOOTSTRAP                        bootstrapTemplate;
	private final boolean                        preferNative;
	private final LoopResources                  loopResources;
	private final SslContext                     sslContext;
	private final long                           sslHandshakeTimeoutMillis;
	private final long                           sslCloseNotifyFlushTimeoutMillis;
	private final long                           sslCloseNotifyReadTimeoutMillis;
	protected final Consumer<? super Channel>    afterChannelInit;
	protected final Consumer<? super Connection> afterNettyContextInit;
	private final Predicate<? super Channel>     onChannelInit;

	protected NettyOptions(NettyOptions.Builder<BOOTSTRAP, SO, ?> builder) {
		this.bootstrapTemplate = builder.bootstrapTemplate;
		this.preferNative = builder.preferNative;
		this.loopResources = builder.loopResources;
		this.sslContext = builder.sslContext;
		this.sslHandshakeTimeoutMillis = builder.sslHandshakeTimeoutMillis;
		this.sslCloseNotifyFlushTimeoutMillis = builder.sslCloseNotifyFlushTimeoutMillis;
		this.sslCloseNotifyReadTimeoutMillis = builder.sslCloseNotifyReadTimeoutMillis;
		this.afterNettyContextInit = builder.afterNettyContextInit;
		this.onChannelInit = builder.onChannelInit;

		Consumer<? super Channel> afterChannel = builder.afterChannelInit;
		if (afterChannel != null && builder.channelGroup != null) {
			this.afterChannelInit = ((Consumer<Channel>) builder.channelGroup::add)
					.andThen(afterChannel);
		}
		else if (afterChannel != null) {
			this.afterChannelInit = afterChannel;
		}
		else if (builder.channelGroup != null) {
			this.afterChannelInit = builder.channelGroup::add;
		}
		else {
			this.afterChannelInit = null;
		}
	}

	/**
	 * Returns the callback for post {@link Channel} initialization and reactor-netty
	 * pipeline handlers registration.
	 *
	 * @return the post channel setup handler
	 * @see #onChannelInit()
	 * @see #afterNettyContextInit()
	 */
	public final Consumer<? super Channel> afterChannelInit() {
		return afterChannelInit;
	}

	/**
	 * Returns the callback for post {@link Channel} initialization, reactor-netty
	 * pipeline handlers registration and {@link Connection} initialisation.
	 *
	 * @return the post {@link Connection} setup handler
	 * @see #onChannelInit()
	 * @see #afterChannelInit()
	 */
	public final Consumer<? super Connection> afterNettyContextInit() {
		return this.afterNettyContextInit;
	}

	/**
	 * Checks if these options denotes secured communication, ie. a {@link javax.net.ssl.SSLContext}
	 * was set other than the default one.
	 *
	 * @return true if the options denote secured communication (SSL is active)
	 */
	public boolean isSecure() {
		return sslContext != null;
	}

	/**
	 * Return a copy of all options and references such as
	 * {@link NettyOptions.Builder#onChannelInit(Predicate)}. Further option uses on the returned builder will
	 * be fully isolated from this option builder.
	 *
	 * @return a new duplicated builder;
	 */
	public abstract SO duplicate();

	@Override
	public BOOTSTRAP get() {
		return bootstrapTemplate.clone();
	}

	/**
	 * Return a new eventual {@link SocketAddress}
	 *
	 * @return the supplied {@link SocketAddress} or null
	 */
	public abstract SocketAddress getAddress();

	/**
	 * Get the configured Loop Resources if any
	 *
	 * @return an eventual {@link LoopResources}
	 */
	public final LoopResources getLoopResources() {
		return loopResources;
	}

	/**
	 * Return a new eventual {@link SslHandler}, optionally with SNI activated
	 *
	 * @param allocator {@link ByteBufAllocator} to allocate for packet storage
	 * @param sniInfo {@link Tuple2} with hostname and port for SNI (any null will skip SNI).
	 * @return a new eventual {@link SslHandler} with SNI activated
	 */
	public final SslHandler getSslHandler(ByteBufAllocator allocator,
			Tuple2<String, Integer> sniInfo) {
		SslContext sslContext =
				this.sslContext == null ? defaultSslContext() : this.sslContext;

		if (sslContext == null) {
			return null;
		}

		Objects.requireNonNull(allocator, "allocator");
		SslHandler sslHandler;
		if (sniInfo != null && sniInfo.getT1() != null && sniInfo.getT2() != null) {
			sslHandler = sslContext.newHandler(allocator, sniInfo.getT1(), sniInfo.getT2());
		}
		else {
			sslHandler = sslContext.newHandler(allocator);
		}
		sslHandler.setHandshakeTimeoutMillis(sslHandshakeTimeoutMillis);
		sslHandler.setCloseNotifyFlushTimeoutMillis(sslCloseNotifyFlushTimeoutMillis);
		sslHandler.setCloseNotifyReadTimeoutMillis(sslCloseNotifyReadTimeoutMillis);
		return sslHandler;
	}

	/**
	 * Returns the predicate used to validate each {@link Channel} post initialization
	 * (but before reactor-netty pipeline handlers have been registered).
	 *
	 * @return The channel validator
	 * @see #afterChannelInit()
	 * @see #afterNettyContextInit()
	 */
	public final Predicate<? super Channel> onChannelInit() {
		return this.onChannelInit;
	}

	/**
	 * Is this option preferring native loops (epoll)
	 *
	 * @return true if this option is preferring native loops (epoll)
	 */
	public final boolean preferNative() {
		return this.preferNative;
	}

	/**
	 * Returns the SslContext
	 *
	 * @return the SslContext
	 */
	public final SslContext sslContext() {
		return this.sslContext;
	}

	/**
	 * Returns the SSL handshake timeout in millis
	 *
	 * @return the SSL handshake timeout in millis
	 */
	public final long sslHandshakeTimeoutMillis() {
		return this.sslHandshakeTimeoutMillis;
	}

	/**
	 * Returns the SSL close_notify flush timeout in millis
	 *
	 * @return the SSL close_notify flush timeout in millis
	 */
	public final long sslCloseNotifyFlushTimeoutMillis() {
		return this.sslCloseNotifyFlushTimeoutMillis;
	}

	/**
	 * Returns the SSL close_notify read timeout in millis
	 *
	 * @return the SSL close_notify read timeout in millis
	 */
	public final long sslCloseNotifyReadTimeoutMillis() {
		return this.sslCloseNotifyReadTimeoutMillis;
	}

	/**
	 * Default Ssl context if none configured or null;
	 *
	 * @return a default {@link SslContext}
	 */
	protected SslContext defaultSslContext() {
		return null;
	}

	public String asSimpleString() {
		return this.asDetailedString();
	}

	public String asDetailedString() {
		return "bootstrapTemplate=" + bootstrapTemplate +
				", sslHandshakeTimeoutMillis=" + sslHandshakeTimeoutMillis +
				", sslCloseNotifyFlushTimeoutMillis=" + sslCloseNotifyFlushTimeoutMillis +
				", sslCloseNotifyReadTimeoutMillis=" + sslCloseNotifyReadTimeoutMillis +
				", sslContext=" + sslContext +
				", preferNative=" + preferNative +
				", afterChannelInit=" + afterChannelInit +
				", onChannelInit=" + onChannelInit +
				", loopResources=" + loopResources;
	}

	@Override
	public String toString() {
		return "NettyOptions{" + asDetailedString() + "}";
	}

	public static abstract class Builder<BOOTSTRAP extends AbstractBootstrap<BOOTSTRAP, ?>,
			SO extends NettyOptions<BOOTSTRAP, SO>, BUILDER extends Builder<BOOTSTRAP, SO, BUILDER>>
			implements Supplier<BUILDER>{

		private static final boolean DEFAULT_NATIVE =
				Boolean.parseBoolean(System.getProperty("reactor.ipc.netty.epoll", "true"));

		protected BOOTSTRAP bootstrapTemplate;
		private boolean                        preferNative                     = DEFAULT_NATIVE;
		private LoopResources                  loopResources                    = null;
		private ChannelGroup                 channelGroup                     = null;
		private SslContext                   sslContext                       = null;
		private long                         sslHandshakeTimeoutMillis        = 10000L;
		private long                         sslCloseNotifyFlushTimeoutMillis = 3000L;
		private long                         sslCloseNotifyReadTimeoutMillis  = 0L;
		private Consumer<? super Channel>    afterChannelInit                 = null;
		private Consumer<? super Connection> afterNettyContextInit            = null;
		private Predicate<? super Channel>   onChannelInit                    = null;

		protected Builder(BOOTSTRAP bootstrapTemplate) {
			this.bootstrapTemplate = bootstrapTemplate;
			defaultNettyOptions(this.bootstrapTemplate);
		}

		private void defaultNettyOptions(AbstractBootstrap<?, ?> bootstrap) {
			bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
		}

		/**
		 * Attribute default attribute to the future {@link Channel} connection. They will
		 * be available via {@link Channel#attr(AttributeKey)}.
		 *
		 * @param key the attribute key
		 * @param value the attribute value
		 * @param <T> the attribute type
		 * @return {@code this}
		 * @see Bootstrap#attr(AttributeKey, Object)
		 */
		public <T> BUILDER attr(AttributeKey<T> key, T value) {
			this.bootstrapTemplate.attr(key, value);
			return get();
		}

		/**
		 * Set a {@link ChannelOption} value for low level connection settings like
		 * SO_TIMEOUT or SO_KEEPALIVE. This will apply to each new channel from remote
		 * peer.
		 *
		 * @param key the option key
		 * @param value the option value
		 * @param <T> the option type
		 * @return {@code this}
		 * @see Bootstrap#option(ChannelOption, Object)
		 */
		public <T> BUILDER option(ChannelOption<T> key, T value) {
			this.bootstrapTemplate.option(key, value);
			return get();
		}

		/**
		 * Set the preferred native option. Determine if epoll should be used if available.
		 *
		 * @param preferNative Should the connector prefer native (epoll) if available
		 * @return {@code this}
		 */
		public final BUILDER preferNative(boolean preferNative) {
			this.preferNative = preferNative;
			return get();
		}

		/**
		 * Provide an {@link EventLoopGroup} supplier.
		 * Note that server might call it twice for both their selection and io loops.
		 *
		 * @param channelResources a selector accepting native runtime expectation and
		 * returning an eventLoopGroup
		 * @return {@code this}
		 */
		public final BUILDER loopResources(LoopResources channelResources) {
			this.loopResources = Objects.requireNonNull(channelResources, "loopResources");
			return get();
		}

		public final boolean isLoopAvailable() {
			return this.loopResources != null;
		}

		/**
		 * Provide a shared {@link EventLoopGroup} each Connector handler.
		 *
		 * @param eventLoopGroup an eventLoopGroup to share
		 * @return {@code this}
		 */
		public final BUILDER eventLoopGroup(EventLoopGroup eventLoopGroup) {
			Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
			return loopResources(preferNative -> eventLoopGroup);
		}

		/**
		 * Provide a {@link ChannelGroup} for each active remote channel will be held in the
		 * provided group.
		 *
		 * @param channelGroup a {@link ChannelGroup} to monitor remote channel
		 * @return {@code this}
		 */
		public final BUILDER channelGroup(ChannelGroup channelGroup) {
			this.channelGroup = Objects.requireNonNull(channelGroup, "channelGroup");
			//the channelGroup being set, afterChannelInit will be augmented to add
			//each channel to the group, when actual Options are constructed
			return get();
		}

		/**
		 * Set the options to use for configuring SSL. Setting this to {@code null} means
		 * don't use SSL at all (the default).
		 *
		 * @param sslContext The context to set when configuring SSL
		 * @return {@code this}
		 */
		public final BUILDER sslContext(SslContext sslContext) {
			this.sslContext = sslContext;
			return get();
		}

		/**
		 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
		 *
		 * @param sslHandshakeTimeout The timeout {@link Duration}
		 * @return {@code this}
		 */
		public final BUILDER sslHandshakeTimeout(Duration sslHandshakeTimeout) {
			Objects.requireNonNull(sslHandshakeTimeout, "sslHandshakeTimeout");
			return sslHandshakeTimeoutMillis(sslHandshakeTimeout.toMillis());
		}

		/**
		 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
		 *
		 * @param sslHandshakeTimeoutMillis The timeout in milliseconds
		 * @return {@code this}
		 */
		public final BUILDER sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
			if(sslHandshakeTimeoutMillis < 0L){
				throw new IllegalArgumentException("ssl handshake timeout must be positive," +
						" was: "+sslHandshakeTimeoutMillis);
			}
			this.sslHandshakeTimeoutMillis = sslHandshakeTimeoutMillis;
			return get();
		}

		/**
		 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
		 *
		 * @param sslCloseNotifyFlushTimeout The timeout {@link Duration}
		 *
		 * @return {@code this}
		 */
		public final BUILDER sslCloseNotifyFlushTimeout(Duration sslCloseNotifyFlushTimeout) {
			Objects.requireNonNull(sslCloseNotifyFlushTimeout, "sslCloseNotifyFlushTimeout");
			return sslCloseNotifyFlushTimeoutMillis(sslCloseNotifyFlushTimeout.toMillis());
		}


		/**
		 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
		 *
		 * @param sslCloseNotifyFlushTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@code this}
		 */
		public final BUILDER sslCloseNotifyFlushTimeoutMillis(long sslCloseNotifyFlushTimeoutMillis) {
			if (sslCloseNotifyFlushTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl close_notify flush timeout must be positive," +
						" was: " + sslCloseNotifyFlushTimeoutMillis);
			}
			this.sslCloseNotifyFlushTimeoutMillis = sslCloseNotifyFlushTimeoutMillis;
			return get();
		}


		/**
		 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
		 *
		 * @param sslCloseNotifyReadTimeout The timeout {@link Duration}
		 *
		 * @return {@code this}
		 */
		public final BUILDER sslCloseNotifyReadTimeout(Duration sslCloseNotifyReadTimeout) {
			Objects.requireNonNull(sslCloseNotifyReadTimeout, "sslCloseNotifyReadTimeout");
			return sslCloseNotifyFlushTimeoutMillis(sslCloseNotifyReadTimeout.toMillis());
		}


		/**
		 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
		 *
		 * @param sslCloseNotifyReadTimeoutMillis The timeout in milliseconds
		 *
		 * @return {@code this}
		 */
		public final BUILDER sslCloseNotifyReadTimeoutMillis(long sslCloseNotifyReadTimeoutMillis) {
			if (sslCloseNotifyReadTimeoutMillis < 0L) {
				throw new IllegalArgumentException("ssl close_notify read timeout must be positive," +
						" was: " + sslCloseNotifyReadTimeoutMillis);
			}
			this.sslCloseNotifyReadTimeoutMillis = sslCloseNotifyReadTimeoutMillis;
			return get();
		}

		/**
		 * Setup a callback called after each {@link Channel} initialization, once
		 * reactor-netty pipeline handlers have been registered.
		 *
		 * @param afterChannelInit the post channel setup handler
		 * @return {@code this}
		 * @see #onChannelInit(Predicate)
		 * @see #afterNettyContextInit(Consumer)
		 */
		public final BUILDER afterChannelInit(Consumer<? super Channel> afterChannelInit) {
			this.afterChannelInit = Objects.requireNonNull(afterChannelInit, "afterChannelInit");
			return get();
		}

		/**
		 * Setup a {@link Predicate} for each {@link Channel} initialization that can be
		 * used to prevent the Channel's registration.
		 *
		 * @param onChannelInit predicate to accept or reject the newly created Channel
		 * @return {@code this}
		 * @see #afterChannelInit(Consumer)
		 * @see #afterNettyContextInit(Consumer)
		 */
		public final BUILDER onChannelInit(Predicate<? super Channel> onChannelInit) {
			this.onChannelInit = Objects.requireNonNull(onChannelInit, "onChannelInit");
			return get();
		}

		/**
		 * Setup a callback called after each {@link Channel} initialization, once the
		 * reactor-netty pipeline handlers have been registered and the {@link Connection}
		 * is available.
		 *
		 * @param afterNettyContextInit the post channel setup handler
		 * @return {@code this}
		 * @see #onChannelInit(Predicate)
		 * @see #afterChannelInit(Consumer)
		 */
		public final BUILDER afterNettyContextInit(Consumer<? super Connection> afterNettyContextInit) {
			this.afterNettyContextInit = Objects.requireNonNull(afterNettyContextInit, "afterNettyContextInit");
			return get();
		}

		/**
		 * Fill the builder with attribute values from the provided options.
		 *
		 * @param options The instance from which to copy values
		 * @return {@code this}
		 */
		public BUILDER from(SO options) {
			this.bootstrapTemplate = options.get();
			this.preferNative = options.preferNative();
			this.loopResources = options.getLoopResources();
			this.sslContext = options.sslContext();
			this.sslHandshakeTimeoutMillis = options.sslHandshakeTimeoutMillis();
			this.sslCloseNotifyFlushTimeoutMillis = options.sslCloseNotifyFlushTimeoutMillis();
			this.sslCloseNotifyReadTimeoutMillis = options.sslCloseNotifyReadTimeoutMillis();
			this.afterChannelInit = options.afterChannelInit();
			this.onChannelInit = options.onChannelInit();
			this.afterNettyContextInit = options.afterNettyContextInit();
			return get();
		}
	}
}
