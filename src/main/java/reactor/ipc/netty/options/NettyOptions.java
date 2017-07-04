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

	private final BOOTSTRAP bootstrapTemplate;
	private final boolean preferNative;
	private final LoopResources loopResources;
	private final SslContext sslContext;
	private final long sslHandshakeTimeoutMillis;
	protected final Consumer<? super Channel> afterChannelInit;
	protected final Consumer<? super Channel> afterChannelInitUser;
	private final Predicate<? super Channel> onChannelInit;

	protected NettyOptions(NettyOptions.Builder<BOOTSTRAP, SO, ?> builder) {
		this.bootstrapTemplate = builder.bootstrapTemplate;
		this.preferNative = builder.preferNative;
		this.loopResources = builder.loopResources;
		this.sslContext = builder.sslContext;
		this.sslHandshakeTimeoutMillis = builder.sslHandshakeTimeoutMillis;
		this.afterChannelInit = builder.afterChannelInit;
		this.afterChannelInitUser = builder.afterChannelInitUser;
		this.onChannelInit = builder.onChannelInit;
	}

	/**
	 * Returns the callback after each {@link Channel} initialization and after
	 * reactor-netty pipeline handlers have been registered.
	 *
	 * @return the post channel setup handler
	 * @see #onChannelInit()
	 */
	public final Consumer<? super Channel> afterChannelInit() {
		return afterChannelInitUser;
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
		return sslHandler;
	}

	/**
	 * Returns the callback for each {@link Channel} initialization and before
	 * reactor-netty pipeline handlers have been registered.
	 *
	 * @return The pre channel pipeline setup handler
	 * @see #afterChannelInit()
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
		private boolean preferNative = DEFAULT_NATIVE;
		private LoopResources loopResources = null;
		private ChannelGroup channelGroup = null;
		private SslContext sslContext = null;
		private long sslHandshakeTimeoutMillis = 10000L;
		private Consumer<? super Channel> afterChannelInit = null;
		private Consumer<? super Channel> afterChannelInitUser = null;
		private Predicate<? super Channel> onChannelInit = null;

		protected Builder(BOOTSTRAP bootstrapTemplate) {
			this.bootstrapTemplate = bootstrapTemplate;
			defaultNettyOptions(this.bootstrapTemplate);
		}

		private void defaultNettyOptions(AbstractBootstrap<?, ?> bootstrap) {
			bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
		}

		/**
		 * Attribute default attribute to the future {@link Channel} connection. They will
		 * be available via {@link reactor.ipc.netty.NettyInbound#attr(AttributeKey)}.
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
			Consumer<? super Channel> c = this.afterChannelInitUser;
			if (Objects.nonNull(c)) {
				afterChannelInit(c);
			}
			else {
				this.afterChannelInit = channelGroup::add;
			}
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
		 * Setup the callback after each {@link Channel} initialization and after
		 * reactor-netty pipeline handlers have been registered.
		 *
		 * @param afterChannelInit the post channel setup handler
		 * @return {@code this}
		 * @see #onChannelInit(Predicate)
		 */
		public final BUILDER afterChannelInit(Consumer<? super Channel> afterChannelInit) {
			this.afterChannelInitUser = Objects.requireNonNull(afterChannelInit, "afterChannelInit");
			if (Objects.nonNull(channelGroup)) {
				this.afterChannelInit = c -> {
					afterChannelInit.accept(c);
					channelGroup.add(c);
				};
			}
			else {
				this.afterChannelInit = afterChannelInit;
			}
			return get();
		}

		/**
		 * A callback for each {@link Channel} initialization and before reactor-netty
		 * pipeline handlers have been registered.
		 *
		 * @param onChannelInit pre channel pipeline setup handler
		 * @return {@code this}
		 * @see #afterChannelInit(Consumer)
		 */
		public final BUILDER onChannelInit(Predicate<? super Channel> onChannelInit) {
			this.onChannelInit = Objects.requireNonNull(onChannelInit, "onChannelInit");
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
			this.afterChannelInit = options.afterChannelInit;
			this.afterChannelInitUser = options.afterChannelInitUser;
			this.onChannelInit = options.onChannelInit();
			return get();
		}
	}
}
