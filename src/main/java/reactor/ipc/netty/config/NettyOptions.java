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

package reactor.ipc.netty.config;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * Encapsulates common socket options.
 * @param <SO> A NettyOptions subclass
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
@SuppressWarnings("unchecked")
public abstract class NettyOptions<SO extends NettyOptions<? super SO>> {

	public static final boolean DEFAULT_MANAGED_PEER = Boolean.parseBoolean(System.getProperty("reactor.ipc.netty" +
			".managed.default",
			"false"));

	long                      timeout                   = 30000L;
	long                      sslHandshakeTimeoutMillis = 10000L;
	boolean                   keepAlive                 = true;
	int                       linger                    = 0;
	boolean                   tcpNoDelay                = true;
	int                       rcvbuf                    = 1024 * 1024;
	int                       sndbuf                    = 1024 * 1024;
	boolean                   managed                   = DEFAULT_MANAGED_PEER;
	Consumer<ChannelPipeline> pipelineConfigurer        = null;
	EventLoopGroup            eventLoopGroup            = null;
	boolean                   daemon                    = true;
	SslContextBuilder         sslOptions                = null;
	Consumer<? super Channel> onStart                   = null;



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
	 *
	 * @return
	 */
	public EventLoopGroup eventLoopGroup() {
		return eventLoopGroup;
	}

	/**
	 *
	 * @param eventLoopGroup
	 * @return
	 */
	public SO eventLoopGroup(EventLoopGroup eventLoopGroup) {
		this.eventLoopGroup = eventLoopGroup;
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
	 * Set the is managed value.
	 * @param managed Should the peer be traced
	 * @return {@code this}
	 */
	public SO managed(boolean managed) {
		this.managed = managed;
		return (SO) this;
	}

	/**
	 * Returns the configured bound address listener
	 *
	 * @return The configured bound address listener
	 */
	public Consumer<? super Channel> onStart() {
		return onStart;
	}

	/**
	 * Configures a bound address listener, useful for randomly assigned port
	 *
	 * @param onBind the bound address listener
	 *
	 * @return {@code this}
	 */
	public SO onStart(Consumer<? super Channel> onBind) {
		this.onStart = Objects.requireNonNull(onBind, "onStart");
		return (SO) this;
	}

	/**
	 *
	 * @return
	 */
	public Consumer<ChannelPipeline> pipelineConfigurer() {
		return pipelineConfigurer;
	}

	/**
	 *
	 * @param pipelineConfigurer
	 * @return
	 */
	public SO pipelineConfigurer(Consumer<ChannelPipeline> pipelineConfigurer) {
		this.pipelineConfigurer = pipelineConfigurer;
		return (SO) this;
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
		return timeoutMillis(timeout.toMillis());
	}
}
