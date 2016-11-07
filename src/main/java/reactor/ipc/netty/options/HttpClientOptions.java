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

import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * An http client connector builder with low-level connection options including
 * connection pooling and
 * proxy.
 *
 * @author Stephane Maldini
 */
public class HttpClientOptions extends ClientOptions {

	public static HttpClientOptions create(){
		return new HttpClientOptions();
	}

	public static HttpClientOptions to(String host){
		return to(host, NettyOptions.DEFAULT_PORT);
	}

	public static HttpClientOptions to(String host, int port){
		return create().connect(host, port);
	}

	HttpClientOptions(){

	}

	public HttpClientOptions(ClientOptions options) {
		super(options);
	}

	@Override
	public HttpClientOptions afterChannelInit(Consumer<? super Channel> afterChannelInit) {
		afterChannelInit(afterChannelInit);
		return this;
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

	@Override
	public HttpClientOptions daemon(boolean daemon) {
		super.daemon(daemon);
		return this;
	}

	@Override
	public HttpClientOptions duplicate() {
		return new HttpClientOptions(this);
	}

	@Override
	public HttpClientOptions eventLoopSelector(Supplier<? extends EventLoopSelector> eventLoopSelector) {
		super.eventLoopSelector(eventLoopSelector);
		return this;
	}

	@Override
	public HttpClientOptions keepAlive(boolean keepAlive) {
		super.keepAlive(keepAlive);
		return this;
	}

	@Override
	public HttpClientOptions linger(int linger) {
		linger(linger);
		return this;
	}

	@Override
	public HttpClientOptions managed(boolean managed) {
		super.managed(managed);
		return this;
	}

	@Override
	public HttpClientOptions onChannelInit(Predicate<? super Channel> onChannelInit) {
		onChannelInit(onChannelInit);
		return this;
	}

	@Override
	public HttpClientOptions preferNative(boolean preferNative) {
		super.preferNative(preferNative);
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
	public HttpClientOptions proxy(@Nonnull String host, int port) {
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
	public HttpClientOptions proxy(@Nonnull String host,
			int port,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		return proxy(InetSocketAddress.createUnresolved(host, port), username, password);
	}

	@Override
	public HttpClientOptions proxy(@Nonnull Proxy type,
			@Nonnull String host,
			int port,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		super.proxy(type, host, port, username, password);
		return this;
	}

	@Override
	public HttpClientOptions proxy(@Nonnull Proxy type, @Nonnull String host, int port) {
		super.proxy(type, host, port);
		return this;
	}

	@Override
	public HttpClientOptions proxy(@Nonnull Proxy type,
			@Nonnull InetSocketAddress connectAddress) {
		super.proxy(type, connectAddress);
		return this;
	}

	@Override
	public HttpClientOptions proxy(@Nonnull Proxy type,
			@Nonnull InetSocketAddress connectAddress,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		super.proxy(type, connectAddress, username, password);
		return this;
	}

	@Override
	public HttpClientOptions proxy(@Nonnull Proxy type,
			@Nonnull Supplier<? extends InetSocketAddress> connectAddress,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		super.proxy(type, connectAddress, username, password);
		return this;
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public HttpClientOptions proxy(@Nonnull InetSocketAddress connectAddress) {
		return proxy(new InetResolverProxySupplier(connectAddress), null, null);
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public HttpClientOptions proxy(@Nonnull InetSocketAddress connectAddress,
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
	public HttpClientOptions proxy(@Nonnull Proxy type,
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
	public HttpClientOptions proxy(@Nonnull Supplier<? extends InetSocketAddress> connectAddress,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		super.proxy(Proxy.HTTP, connectAddress, username, password);
		return this;
	}

	@Override
	public HttpClientOptions rcvbuf(int rcvbuf) {
		super.rcvbuf(rcvbuf);
		return this;
	}

	@Override
	public HttpClientOptions sndbuf(int sndbuf) {
		super.sndbuf(sndbuf);
		return this;
	}

	@Override
	public HttpClientOptions ssl(SslContextBuilder sslOptions) {
		super.ssl(sslOptions);
		return this;
	}

	@Override
	public HttpClientOptions sslConfigurer(Consumer<? super SslContextBuilder> sslConfigurer) {
		super.sslConfigurer(sslConfigurer);
		return this;
	}

	@Override
	public HttpClientOptions sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
		super.sslHandshakeTimeoutMillis(sslHandshakeTimeoutMillis);
		return this;
	}

	/**
	 *
	 * @return this {@link HttpClientOptions}
	 */
	@Override
	public HttpClientOptions sslSupport() {
		super.ssl(SslContextBuilder.forClient());
		return this;
	}

	@Override
	public HttpClientOptions tcpNoDelay(boolean tcpNoDelay) {
		super.tcpNoDelay(tcpNoDelay);
		return this;
	}

	@Override
	public HttpClientOptions timeoutMillis(long timeout) {
		super.timeoutMillis(timeout);
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

		ImmutableHttpClientOptions(HttpClientOptions options) {
			super(options);
		}

		@Override
		public HttpClientOptions afterChannelInit(Consumer<? super Channel> afterChannelInit) {
			throw new UnsupportedOperationException("Immutable Options");
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
		public HttpClientOptions connect(@Nonnull Supplier<? extends InetSocketAddress> connectAddress) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions daemon(boolean daemon) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions eventLoopSelector(Supplier<? extends EventLoopSelector> eventLoopGroup) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions keepAlive(boolean keepAlive) {
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
		public HttpClientOptions onChannelInit(Predicate<? super Channel> onChannelInit) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions preferNative(boolean preferNative) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull Proxy type,
				@Nonnull InetSocketAddress connectAddress,
				@Nullable String username,
				@Nullable Function<? super String, ? extends String> password) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull Proxy type,
				@Nonnull Supplier<? extends InetSocketAddress> connectAddress,
				@Nullable String username,
				@Nullable Function<? super String, ? extends String> password) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull String host, int port) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull String host,
				int port,
				@Nullable String username,
				@Nullable Function<? super String, ? extends String> password) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull InetSocketAddress connectAddress) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull InetSocketAddress connectAddress,
				@Nullable String username,
				@Nullable Function<? super String, ? extends String> password) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull Proxy type,
				@Nonnull Supplier<? extends InetSocketAddress> connectAddress) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull Supplier<? extends InetSocketAddress> connectAddress,
				@Nullable String username,
				@Nullable Function<? super String, ? extends String> password) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull Proxy type,
				@Nonnull String host,
				int port,
				@Nullable String username,
				@Nullable Function<? super String, ? extends String> password) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull Proxy type, @Nonnull String host, int port) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions proxy(@Nonnull Proxy type,
				@Nonnull InetSocketAddress connectAddress) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions rcvbuf(int rcvbuf) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions sndbuf(int sndbuf) {
			throw new UnsupportedOperationException("Immutable Options");
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
		public HttpClientOptions sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions sslSupport() {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions tcpNoDelay(boolean tcpNoDelay) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions timeoutMillis(long timeout) {
			throw new UnsupportedOperationException("Immutable Options");
		}

		@Override
		public HttpClientOptions toImmutable() {
			return this;
		}
	}

}
