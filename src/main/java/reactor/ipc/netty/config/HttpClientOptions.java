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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContextBuilder;
import reactor.ipc.netty.common.DuplexSocket;

/**
 * @author Stephane Maldini
 */
public class HttpClientOptions extends ClientOptions {

	public static HttpClientOptions create(){
		return new HttpClientOptions();
	}

	public static HttpClientOptions to(String host){
		return to(host, DuplexSocket.DEFAULT_PORT);
	}

	public static HttpClientOptions to(String host, int port){
		return create().connect(host, port);
	}

	HttpClientOptions(){

	}


	/**
	 * The host and port to which this client should connect.
	 *
	 * @param host The host to connect to.
	 * @param port The port to connect to.
	 * @return {@literal this}
	 */
	@Override
	public HttpClientOptions connect(@Nonnull String host, int port) {
		return connect(InetSocketAddress.createUnresolved(host, port));
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 * @return {@literal this}
	 */
	@Override
	public HttpClientOptions connect(@Nonnull InetSocketAddress connectAddress) {
		return connect(new InetResolverSupplier(connectAddress, this));
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 * @return {@literal this}
	 */
	@Override
	public HttpClientOptions connect(@Nonnull Supplier<? extends InetSocketAddress> connectAddress) {
		super.connect(connectAddress);
		return this;
	}

	/**
	 * The host and port to which this client should connect.
	 *
	 * @param host The host to connect to.
	 * @param port The port to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull String host, int port) {
		return proxy(InetSocketAddress.createUnresolved(host, port));
	}

	/**
	 * The host and port to which this client should connect.
	 *
	 * @param host The host to connect to.
	 * @param port The port to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull String host,
			int port,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		return proxy(InetSocketAddress.createUnresolved(host, port), username, password);
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull InetSocketAddress connectAddress) {
		return proxy(new InetResolverProxySupplier(connectAddress), null, null);
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull InetSocketAddress connectAddress,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		return proxy(new InetResolverProxySupplier(connectAddress), username, password);
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull Proxy type,
			@Nonnull Supplier<? extends InetSocketAddress> connectAddress) {
		return proxy(connectAddress, null, null);
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull Supplier<? extends InetSocketAddress> connectAddress,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		proxy(Proxy.HTTP, connectAddress, username, password);
		return this;
	}

	/**
	 *
	 * @return this {@link HttpClientOptions}
	 */
	@Override
	public HttpClientOptions sslSupport() {
		ssl(SslContextBuilder.forClient());
		return this;
	}

	/**
	 *
	 * @return immutable {@link HttpClientOptions}
	 */
	public HttpClientOptions toImmutable() {
		return new ImmutableHttpClientOptions(this);
	}

	final static class ImmutableHttpClientOptions extends HttpClientOptions {

		final HttpClientOptions options;

		ImmutableHttpClientOptions(HttpClientOptions options) {
			this.options = options;
			if(options.ssl() != null){
					super.ssl(options.ssl());
			}
		}

		@Override
		public HttpClientOptions toImmutable() {
			return this;
		}

		@Override
		public String proxyUsername() {
			return options.proxyUsername();
		}

		@Override
		public Function<? super String, ? extends String> proxyPassword() {
			return options.proxyPassword();
		}

		@Override
		public Supplier<? extends InetSocketAddress> proxyAddress() {
			return options.proxyAddress();
		}

		@Override
		public Proxy proxyType() {
			return options.proxyType();
		}

		@Override
		public InetSocketAddress remoteAddress() {
			return options.remoteAddress();
		}

		@Override
		public EventLoopGroup eventLoopGroup() {
			return options.eventLoopGroup();
		}

		@Override
		public boolean managed() {
			return options.managed();
		}

		@Override
		public boolean keepAlive() {
			return options.keepAlive();
		}

		@Override
		public int linger() {
			return options.linger();
		}

		@Override
		public Consumer<ChannelPipeline> pipelineConfigurer() {
			return options.pipelineConfigurer();
		}

		@Override
		public int rcvbuf() {
			return options.rcvbuf();
		}

		@Override
		public int sndbuf() {
			return options.sndbuf();
		}

		@Override
		public boolean tcpNoDelay() {
			return options.tcpNoDelay();
		}

		@Override
		public long timeoutMillis() {
			return options.timeoutMillis();
		}

		@Override
		public HttpClientOptions sslSupport() {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public ClientOptions proxy(@Nonnull Proxy type,
				@Nonnull InetSocketAddress connectAddress,
				@Nullable String username,
				@Nullable Function<? super String, ? extends String> password) {
			return super.proxy(type, connectAddress, username, password);
		}

		@Override
		public ClientOptions timeoutMillis(long timeout) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public ClientOptions sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public long sslHandshakeTimeoutMillis() {
			return options.sslHandshakeTimeoutMillis();
		}

		@Override
		public HttpClientOptions connect(@Nonnull String host, int port) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions connect(@Nonnull InetSocketAddress connectAddress) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions eventLoopGroup(EventLoopGroup eventLoopGroup) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions keepAlive(boolean keepAlive) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public ClientOptions proxy(@Nonnull Proxy type,
				@Nonnull Supplier<? extends InetSocketAddress> connectAddress,
				@Nullable String username,
				@Nullable Function<? super String, ? extends String> password) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions linger(int linger) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions managed(boolean managed) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions pipelineConfigurer(Consumer<ChannelPipeline> pipelineConfigurer) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions rcvbuf(int rcvbuf) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions daemon(boolean daemon) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public boolean daemon() {
			return options.daemon();
		}

		@Override
		public HttpClientOptions ssl(SslContextBuilder sslOptions) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions sslConfigurer(Consumer<? super SslContextBuilder> consumer) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions sndbuf(int sndbuf) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions tcpNoDelay(boolean tcpNoDelay) {
			throw new UnsupportedOperationException("Immutable Options");
		}
	}

}
