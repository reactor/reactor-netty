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
 * @param <BOOSTRAP> A Netty {@link Bootstrap} type
 * @param <SO> A NettyOptions subclass
 *
 * @author Stephane Maldini
 */
@SuppressWarnings("unchecked")
public abstract class NettyOptions<BOOSTRAP extends AbstractBootstrap<BOOSTRAP, ?>, SO extends NettyOptions<BOOSTRAP, SO>>
		implements Supplier<BOOSTRAP> {

	/**
	 * The default port for reactor-netty servers. Defaults to 12012 but can be tuned via
	 * the {@code PORT} <b>environment variable</b>.
	 */
	public static final int     DEFAULT_PORT         =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) :
					12012;

	static void defaultNettyOptions(AbstractBootstrap<?, ?> bootstrap) {
		bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
	}

	final BOOSTRAP bootstrapTemplate;

	boolean                    preferNative                     = DEFAULT_NATIVE;
	LoopResources              loopResources                    = null;
	ChannelGroup               channelGroup                     = null;
	SslContext                 sslContext                       = null;
	long                       sslHandshakeTimeoutMillis        = 10000L;
	long                       sslCloseNotifyFlushTimeoutMillis = 3000L;
	long                       sslCloseNotifyReadTimeoutMillis  = 0L;
	Consumer<? super Channel>  afterChannelInit                 = null;
	Consumer<? super Channel>  afterChannelInitUser             = null;
	Predicate<? super Channel> onChannelInit                    = null;

	NettyOptions(BOOSTRAP bootstrapTemplate) {
		this.bootstrapTemplate = bootstrapTemplate;
		defaultNettyOptions(bootstrapTemplate);
	}

	NettyOptions(NettyOptions<BOOSTRAP, ?> options) {
		this.bootstrapTemplate = options.bootstrapTemplate.clone();
		this.sslHandshakeTimeoutMillis = options.sslHandshakeTimeoutMillis;
		this.sslCloseNotifyFlushTimeoutMillis = options.sslCloseNotifyFlushTimeoutMillis;
		this.sslCloseNotifyReadTimeoutMillis = options.sslCloseNotifyReadTimeoutMillis;
		this.sslContext = options.sslContext;

		this.afterChannelInit = options.afterChannelInit;
		this.afterChannelInitUser = options.afterChannelInitUser;
		this.onChannelInit = options.onChannelInit;
		this.channelGroup = options.channelGroup;
		this.loopResources = options.loopResources;
		this.preferNative = options.preferNative;
	}

	/**
	 * Returns the callback after each {@link Channel} initialization and after
	 * reactor-netty
	 * pipeline handlers have been registered.
	 *
	 * @return the post channel setup handler
	 *
	 * @see #onChannelInit()
	 */
	public final Consumer<? super Channel> afterChannelInit() {
		return afterChannelInitUser;
	}

	/**
	 * Setup the callback after each {@link Channel} initialization and after
	 * reactor-netty
	 * pipeline handlers have been registered.
	 *
	 * @param afterChannelInit the post channel setup handler
	 *
	 * @return {@code this}
	 *
	 * @see #onChannelInit(Predicate)
	 */
	public SO afterChannelInit(Consumer<? super Channel> afterChannelInit) {
		Objects.requireNonNull(afterChannelInit, "afterChannelInit");
		this.afterChannelInitUser = afterChannelInit;
		if (channelGroup != null) {
			this.afterChannelInit = c -> {
				afterChannelInit.accept(c);
				channelGroup.add(c);
			};
		}
		else {
			this.afterChannelInit = afterChannelInit;
		}
		return (SO) this;
	}

	/**
	 * Attribute default attribute to the future {@link Channel} connection. They will
	 * be available via {@link reactor.ipc.netty.NettyInbound#attr(AttributeKey)}.
	 *
	 * @param key the attribute key
	 * @param value the attribute value
	 * @param <T> the attribute type
	 * @return this builder
	 * @see Bootstrap#attr(AttributeKey, Object)
	 */
	public <T> SO attr(AttributeKey<T> key, T value) {
		bootstrapTemplate.attr(key, value);
		return (SO) this;
	}

	/**
	 * Provide a {@link ChannelGroup} for each active remote channel will be held in the
	 * provided group.
	 *
	 * @param channelGroup a {@link ChannelGroup} to monitor remote channel
	 *
	 * @return this builder
	 */
	public SO channelGroup(ChannelGroup channelGroup) {
		Objects.requireNonNull(channelGroup, "channelGroup");
		this.channelGroup = channelGroup;
		Consumer<? super Channel> c = this.afterChannelInitUser;
		if (c != null) {
			afterChannelInit(c);
		}
		else {
			this.afterChannelInit = channelGroup::add;
		}
		return (SO) this;
	}

	/**
	 * Provide an {@link EventLoopGroup} supplier.
	 * Note that server might call it twice for both their selection and io loops.
	 *
	 * @param channelResources a selector accepting native runtime expectation and
	 * returning an eventLoopGroup
	 *
	 * @return this builder
	 */
	public SO loopResources(LoopResources channelResources) {
		Objects.requireNonNull(channelResources, "loopResources");
		this.loopResources = channelResources;
		return (SO) this;
	}

	public final boolean isLoopAvailable() {
		return this.loopResources != null;
	}

	/**
	 * Return a copy of all options and references such as
	 * {@link #onChannelInit(Predicate)}. Further option uses on the returned builder will
	 * be fully isolated from this option builder.
	 *
	 * @return a new duplicated builder;
	 */
	public abstract SO duplicate();

	/**
	 * Provide a shared {@link EventLoopGroup} each Connector handler.
	 *
	 * @param eventLoopGroup an eventLoopGroup to share
	 *
	 * @return an {@link EventLoopGroup} provider given the native runtime expectation
	 */
	public SO eventLoopGroup(EventLoopGroup eventLoopGroup) {
		Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
		return loopResources(preferNative -> eventLoopGroup);
	}

	@Override
	public BOOSTRAP get() {
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
	 *
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
	 * Returns the callback for each {@link Channel} initialization and before
	 * reactor-netty
	 * pipeline handlers have been registered.
	 *
	 * @return The pre channel pipeline setup handler
	 *
	 * @see #afterChannelInit()
	 */
	public final Predicate<? super Channel> onChannelInit() {
		return onChannelInit;
	}

	/**
	 * A callback for each {@link Channel} initialization and before reactor-netty
	 * pipeline handlers have been registered.
	 *
	 * @param onChannelInit pre channel pipeline setup handler
	 *
	 * @return {@code this}
	 *
	 * @see #afterChannelInit(Consumer)
	 */
	public SO onChannelInit(Predicate<? super Channel> onChannelInit) {
		this.onChannelInit = Objects.requireNonNull(onChannelInit, "onChannelInit");
		return (SO) this;
	}

	/**
	 * Set a {@link ChannelOption} value for low level connection settings like
	 * SO_TIMEOUT or SO_KEEPALIVE. This will apply to each new channel from remote
	 * peer.
	 *
	 * @param key the option key
	 * @param <T> the option type
	 *
	 * @return {@code this}
	 * @see Bootstrap#option(ChannelOption, Object)
	 */
	public <T> SO option(ChannelOption<T> key, T value) {
		this.bootstrapTemplate.option(key, value);
		return (SO) this;
	}

	/**
	 * Set the preferred native option. Determine if epoll should be used if available.
	 *
	 * @param preferNative Should the connector prefer native (epoll) if available
	 *
	 * @return {@code this}
	 */
	public SO preferNative(boolean preferNative) {
		this.preferNative = preferNative;
		return (SO) this;
	}

	/**
	 * Is this option preferring native loops (epoll)
	 * @return true if this option is preferring native loops (epoll)
	 */
	public final boolean preferNative(){
		return preferNative;
	}

	/**
	 * Set the options to use for configuring SSL. Setting this to {@code null} means
	 * don't use SSL at all (the default).
	 *
	 * @param sslContext The context to set when configuring SSL
	 *
	 * @return {@literal this}
	 */
	public SO sslContext(SslContext sslContext) {
		this.sslContext = sslContext;
		return (SO) this;
	}

	/**
	 * Default Ssl context if none configured or null;
	 *
	 * @return a default {@link SslContext}
	 */
	protected SslContext defaultSslContext() {
		return null;
	}

	/**
	 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
	 *
	 * @param sslHandshakeTimeout The timeout {@link Duration}
	 *
	 * @return {@literal this}
	 */
	public SO sslHandshakeTimeout(Duration sslHandshakeTimeout) {
		Objects.requireNonNull(sslHandshakeTimeout, "sslHandshakeTimeout");
		return sslHandshakeTimeoutMillis(sslHandshakeTimeout.toMillis());
	}

	/**
	 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
	 *
	 * @param sslHandshakeTimeoutMillis The timeout in milliseconds
	 *
	 * @return {@literal this}
	 */
	public SO sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
		if(sslHandshakeTimeoutMillis < 0L){
			throw new IllegalArgumentException("ssl handshake timeout must be positive," +
					" was: "+sslHandshakeTimeoutMillis);
		}
		this.sslHandshakeTimeoutMillis = sslHandshakeTimeoutMillis;
		return (SO) this;
	}


	/**
	 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
	 *
	 * @param sslCloseNotifyFlushTimeout The timeout {@link Duration}
	 *
	 * @return {@literal this}
	 */
	public SO sslCloseNotifyFlushTimeout(Duration sslCloseNotifyFlushTimeout) {
		Objects.requireNonNull(sslCloseNotifyFlushTimeout, "sslCloseNotifyFlushTimeout");
		return sslCloseNotifyFlushTimeoutMillis(sslCloseNotifyFlushTimeout.toMillis());
	}


	/**
	 * Set the options to use for configuring SSL close_notify flush timeout. Default to 3000 ms.
	 *
	 * @param sslCloseNotifyFlushTimeoutMillis The timeout in milliseconds
	 *
	 * @return {@literal this}
	 */
	public SO sslCloseNotifyFlushTimeoutMillis(long sslCloseNotifyFlushTimeoutMillis) {
		if (sslCloseNotifyFlushTimeoutMillis < 0L) {
			throw new IllegalArgumentException("ssl close_notify flush timeout must be positive," +
					" was: " + sslCloseNotifyFlushTimeoutMillis);
		}
		this.sslCloseNotifyFlushTimeoutMillis = sslCloseNotifyFlushTimeoutMillis;
		return (SO) this;
	}


	/**
	 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
	 *
	 * @param sslCloseNotifyReadTimeout The timeout {@link Duration}
	 *
	 * @return {@literal this}
	 */
	public SO sslCloseNotifyReadTimeout(Duration sslCloseNotifyReadTimeout) {
		Objects.requireNonNull(sslCloseNotifyReadTimeout, "sslCloseNotifyReadTimeout");
		return sslCloseNotifyFlushTimeoutMillis(sslCloseNotifyReadTimeout.toMillis());
	}


	/**
	 * Set the options to use for configuring SSL close_notify read timeout. Default to 0 ms.
	 *
	 * @param sslCloseNotifyReadTimeoutMillis The timeout in milliseconds
	 *
	 * @return {@literal this}
	 */
	public SO sslCloseNotifyReadTimeoutMillis(long sslCloseNotifyReadTimeoutMillis) {
		if (sslCloseNotifyReadTimeoutMillis < 0L) {
			throw new IllegalArgumentException("ssl close_notify read timeout must be positive," +
					" was: " + sslCloseNotifyReadTimeoutMillis);
		}
		this.sslCloseNotifyReadTimeoutMillis = sslCloseNotifyReadTimeoutMillis;
		return (SO) this;
	}

	@Override
	public String toString() {
		return "NettyOptions{"
				+ "bootstrapTemplate=" + bootstrapTemplate
				+ ", sslHandshakeTimeoutMillis=" + sslHandshakeTimeoutMillis
				+ ", sslCloseNotifyFlushTimeoutMillis=" + sslCloseNotifyFlushTimeoutMillis
				+ ", sslCloseNotifyReadTimeoutMillis=" + sslCloseNotifyReadTimeoutMillis
				+ ", sslContext=" + sslContext
				+ ", preferNative=" + preferNative
				+ ", afterChannelInit=" + afterChannelInit
				+ ", onChannelInit=" + onChannelInit
				+ ", loopResources=" + loopResources
				+ '}';
	}

	static final boolean DEFAULT_NATIVE =
			Boolean.parseBoolean(System.getProperty("reactor.ipc.netty.epoll", "true"));
}
