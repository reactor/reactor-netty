/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * A common connector builder with low-level connection options including ssl, tcp
 * configuration, channel init handlers.
 *
 * @param <SO> A NettyOptions subclass
 * @author Stephane Maldini
 */
@SuppressWarnings("unchecked")
public abstract class NettyOptions<SO extends NettyOptions<? super SO>> {

	/**
	 *
	 */
	public static final boolean DEFAULT_MANAGED_PEER = Boolean.parseBoolean(System.getProperty("reactor.ipc.netty" +
			".managed.default",
			"false"));
	/**
	 *
	 */
	public static final int     DEFAULT_PORT         =
			System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) :
					12012;

	long              timeout                   = 30000L;
	long              sslHandshakeTimeoutMillis = 10000L;
	boolean           keepAlive                 = true;
	int               linger                    = 0;
	boolean           tcpNoDelay                = true;
	int               rcvbuf                    = 1024 * 1024;
	int               sndbuf                    = 1024 * 1024;
	boolean           managed                   = DEFAULT_MANAGED_PEER;
	boolean           daemon                    = true;
	SslContextBuilder sslOptions                = null;
	boolean preferNative;

	Consumer<? super Channel>             afterChannelInit  = null;
	Predicate<? super Channel>            onChannelInit     = null;
	Supplier<? extends EventLoopSelector> eventLoopSelector = null;

	NettyOptions() {
		preferNative =
				Boolean.parseBoolean(System.getProperty("reactor.ipc.epoll", "true"));
	}

	NettyOptions(NettyOptions options) {
		this.timeout = options.timeout;
		this.sslHandshakeTimeoutMillis = options.sslHandshakeTimeoutMillis;
		this.keepAlive = options.keepAlive;
		this.linger = options.linger;
		this.tcpNoDelay = options.tcpNoDelay;
		this.rcvbuf = options.rcvbuf;
		this.sndbuf = options.sndbuf;
		this.managed = options.managed;
		this.daemon = options.daemon;
		this.sslOptions = options.sslOptions;

		this.afterChannelInit = options.afterChannelInit;
		this.onChannelInit = options.onChannelInit;
		this.eventLoopSelector = options.eventLoopSelector;
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
	public Consumer<? super Channel> afterChannelInit() {
		return afterChannelInit;
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
		this.afterChannelInit = afterChannelInit;
		return (SO) this;
	}

	/**
	 * Returns a boolean indicating whether or not runtime threads will run daemon.
	 * @return a boolean indicating whether or not runtime threads will run daemon.
	 */
	public boolean daemon() {
		return daemon;
	}

	/**
	 * Will run runtime threads in daemon mode or not. Explicit shutdowns will be
	 * required if not daemon.
	 * @param daemon {@code true} to run in daemon threads.
	 * @return {@code this}
	 */
	public SO daemon(boolean daemon) {
		this.daemon = daemon;
		return (SO) this;
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
	 * @param eventLoopGroup an eventLoopGroup to share
	 * @return an {@link EventLoopGroup} provider given the native runtime expectation
	 */
	public SO eventLoopGroup(EventLoopGroup eventLoopGroup) {
		Objects.requireNonNull(eventLoopGroup, "eventLoopGroup");
		this.eventLoopSelector = () -> preferNative -> eventLoopGroup;
		return (SO)this;
	}

	/**
	 * Return the configured {@link EventLoopGroup} selector for each Connector handler.
	 * @return an {@link EventLoopGroup} selector for each Connector handler.
	 */
	public Supplier<? extends EventLoopSelector> eventLoopSelector() {
		return eventLoopSelector;
	}

	/**
	 * Provide an {@link EventLoopGroup} selector for each Connector handler.
	 * Note that server might call it twice for both their selection and io loops.
	 * @param eventLoopSelector a selector accepting native runtime expectation and
	 * returning an eventLoopGroup
	 *
	 * @return this builder
	 */
	public SO eventLoopSelector(Supplier<? extends EventLoopSelector> eventLoopSelector) {
		Objects.requireNonNull(eventLoopSelector, "eventLoopSelector");
		this.eventLoopSelector = eventLoopSelector;
		return (SO) this;
	}

	/**
	 * Returns a boolean indicating whether or not {@code SO_KEEPALIVE} is enabled
	 * @return {@code true} if keep alive is enabled, {@code false} otherwise
	 */
	public boolean keepAlive() {
		return keepAlive;
	}

	/**
	 * Enables or disables {@code SO_KEEPALIVE}.
	 * @param keepAlive {@code true} to enable keepalive, {@code false} to disable
	 * keepalive
	 * @return {@code this}
	 */
	public SO keepAlive(boolean keepAlive) {
		this.keepAlive = keepAlive;
		return (SO) this;
	}

	/**
	 * Returns the configuration of {@code SO_LINGER}.
	 * @return the value of {@code SO_LINGER} in seconds
	 */
	public int linger() {
		return linger;
	}

	/**
	 * Configures {@code SO_LINGER}
	 * @param linger The linger period in seconds
	 * @return {@code this}
	 */
	public SO linger(int linger) {
		this.linger = linger;
		return (SO) this;
	}

	/**
	 * @return false if not managed
	 */
	public boolean managed() {
		return managed;
	}

	/**
	 * Set the is managed value. Will retain connections in a
	 * connector global scoped {@link io.netty.channel.group.ChannelGroup}
	 * @param managed Should the connector be traced
	 * @return {@code this}
	 */
	public SO managed(boolean managed) {
		this.managed = managed;
		return (SO) this;
	}

	/**
	 * Returns the callback for each {@link Channel} initialization and before
	 * reactor-netty
	 * pipeline handlers have been registered.
	 *
	 * @return The pre channel pipeline setup handler
	 * @see #afterChannelInit()
	 */
	public Predicate<? super Channel> onChannelInit() {
		return onChannelInit;
	}

	/**
	 * A callback for each {@link Channel} initialization and before reactor-netty
	 * pipeline handlers have been registered.
	 *
	 * @param onChannelInit pre channel pipeline setup handler
	 *
	 * @return {@code this}
	 * @see #afterChannelInit(Consumer)
	 */
	public SO onChannelInit(Predicate<? super Channel> onChannelInit) {
		this.onChannelInit = Objects.requireNonNull(onChannelInit, "onChannelInit");
		return (SO) this;
	}

	/**
	 * Set the preferred native option. Determine if epoll should be used if available.
	 * @param preferNative Should the connector prefer native (epoll) if available
	 * @return {@code this}
	 */
	public SO preferNative(boolean preferNative) {
		this.preferNative = preferNative;
		return (SO) this;
	}

	/**
	 * Return true if epoll should be used if available.
	 *
	 * @return true if epoll should be used if available.
	 */
	public boolean preferNative() {
		return preferNative;
	}


	/**
	 * Gets the configured {@code SO_RCVBUF} (receive buffer) size
	 * @return The configured receive buffer size
	 */
	public int rcvbuf() {
		return rcvbuf;
	}

	/**
	 * Sets the {@code SO_RCVBUF} (receive buffer) size
	 * @param rcvbuf The size of the receive buffer
	 * @return {@code this}
	 */
	public SO rcvbuf(int rcvbuf) {
		this.rcvbuf = rcvbuf;
		return (SO) this;
	}

	/**
	 * Return eventual {@link SslContextBuilder}
	 * @return optional {@link SslContextBuilder}
	 */
	public SslContextBuilder ssl() {
		return sslOptions;
	}

	/**
	 * Set the options to use for configuring SSL. Setting this to {@code null} means
	 * don't use SSL at all (the default).
	 *
	 * @param sslOptions The options to set when configuring SSL
	 *
	 * @return {@literal this}
	 */
	public SO ssl(SslContextBuilder sslOptions) {
		this.sslOptions = sslOptions;
		return (SO) this;
	}

	/**
	 * Return eventual {@link SslContextBuilder}
	 * @param sslConfigurer the callback accepting the current {@link SslContextBuilder}
	 * @return {@literal this}
	 */
	public SO sslConfigurer(Consumer<? super SslContextBuilder> sslConfigurer) {
		Objects.requireNonNull(sslConfigurer, "sslConfigurer");
		sslConfigurer.accept(sslOptions);
		return (SO) this;
	}

	/**
	 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
	 *
	 * @param sslHandshakeTimeout The timeout {@link Duration}
	 * @return {@literal this}
	 */
	public final SO sslHandshakeTimeout(Duration sslHandshakeTimeout) {
		Objects.requireNonNull(sslHandshakeTimeout, "sslHandshakeTimeout");
		return sslHandshakeTimeoutMillis(sslHandshakeTimeout.toMillis());
	}

	/**
	 * Set the options to use for configuring SSL handshake timeout. Default to 10000 ms.
	 *
	 * @param sslHandshakeTimeoutMillis The timeout in milliseconds
	 * @return {@literal this}
	 */
	public SO sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
		this.sslHandshakeTimeoutMillis = sslHandshakeTimeoutMillis;
		return (SO) this;
	}

	/**
	 * Return SSL handshake timeout value in milliseconds
	 *
	 * @return the SSL handshake timeout
	 */
	public long sslHandshakeTimeoutMillis() {
		return sslHandshakeTimeoutMillis;
	}

	/**
	 * Gets the configured {@code SO_SNDBUF} (send buffer) size
	 * @return The configured send buffer size
	 */
	public int sndbuf() {
		return sndbuf;
	}

	/**
	 * Sets the {@code SO_SNDBUF} (send buffer) size
	 * @param sndbuf The size of the send buffer
	 * @return {@code this}
	 */
	public SO sndbuf(int sndbuf) {
		this.sndbuf = sndbuf;
		return (SO) this;
	}

	/**
	 * Returns a boolean indicating whether or not {@code TCP_NODELAY} is enabled
	 * @return {@code true} if {@code TCP_NODELAY} is enabled, {@code false} if it is not
	 */
	public boolean tcpNoDelay() {
		return tcpNoDelay;
	}

	/**
	 * Enables or disables {@code TCP_NODELAY}
	 * @param tcpNoDelay {@code true} to enable {@code TCP_NODELAY}, {@code false} to
	 * disable it
	 * @return {@code this}
	 */
	public SO tcpNoDelay(boolean tcpNoDelay) {
		this.tcpNoDelay = tcpNoDelay;
		return (SO) this;
	}

	/**
	 * Gets the {@code SO_TIMEOUT} value
	 * @return the timeout value
	 */
	public long timeoutMillis() {
		return timeout;
	}

	/**
	 * Set the {@code SO_TIMEOUT} value in millis.
	 * @param timeout The {@code SO_TIMEOUT} value.
	 * @return {@code this}
	 */
	public SO timeoutMillis(long timeout) {
		this.timeout = timeout;
		return (SO) this;
	}

	/**
	 * Set the {@code SO_TIMEOUT} value.
	 * @param timeout The {@code SO_TIMEOUT} value.
	 * @return {@code this}
	 */
	public final SO timeout(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		return timeoutMillis(timeout.toMillis());
	}
}
