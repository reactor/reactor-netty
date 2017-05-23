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

package reactor.ipc.netty.http.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.resources.PoolResources;

/**
 * An http client connector builder with low-level connection options including
 * connection pooling and proxy.
 *
 * @author Stephane Maldini
 */
public final class HttpClientOptions extends ClientOptions {

	boolean acceptGzip;

	/**
	 * Create new {@link HttpClientOptions}.
	 *
	 * @return a new option builder
	 */
	public static HttpClientOptions create() {
		return new HttpClientOptions();
	}

	HttpClientOptions() {
	}

	HttpClientOptions(HttpClientOptions options) {
		super(options);
		this.acceptGzip = options.acceptGzip;
	}

	@Override
	public HttpClientOptions afterChannelInit(Consumer<? super Channel> afterChannelInit) {
		super.afterChannelInit(afterChannelInit);
		return this;
	}

	@Override
	public <T> HttpClientOptions attr(AttributeKey<T> key, T value) {
		super.attr(key, value);
		return this;
	}

	@Override
	public HttpClientOptions channelGroup(ChannelGroup channelGroup) {
		super.channelGroup(channelGroup);
		return this;
	}

	@Override
	public HttpClientOptions loopResources(LoopResources eventLoopSelector) {
		super.loopResources(eventLoopSelector);
		return this;
	}

	@Override
	public HttpClientOptions connect(@Nonnull String host, int port) {
		return connect(InetSocketAddress.createUnresolved(host, port));
	}

	@Override
	public HttpClientOptions connect(@Nonnull InetSocketAddress connectAddress) {
		super.connect(connectAddress);
		return this;
	}

	@Override
	public HttpClientOptions connect(@Nonnull Supplier<? extends InetSocketAddress> connectAddress) {
		super.connect(connectAddress);
		return this;
	}

	@Override
	public HttpClientOptions disablePool() {
		super.disablePool();
		return this;
	}

	@Override
	public HttpClientOptions duplicate() {
		return new HttpClientOptions(this);
	}

	@Override
	public HttpClientOptions eventLoopGroup(EventLoopGroup eventLoopGroup) {
		super.eventLoopGroup(eventLoopGroup);
		return this;
	}

	/**
	 * Enable GZip accept-encoding header and support for compressed response
	 *
	 * @param enabled true whether gzip support is enabled
	 *
	 * @return this builder
	 */
	public HttpClientOptions compression(boolean enabled) {
		this.acceptGzip = enabled;
		return this;
	}

	/**
	 * Return a new {@link InetSocketAddress} from the URI.
	 * <p>
	 * If the port is undefined (-1), a default port is used (80 or 443 depending on
	 * whether the URI is secure or not). If {@link #useProxy() a proxy} is used, the
	 * returned address is provided unresolved.
	 *
	 * @param uri {@link URI} to extract host and port information from
	 *
	 * @return a new eventual {@link InetSocketAddress}
	 */
	public final InetSocketAddress getRemoteAddress(URI uri) {
		Objects.requireNonNull(uri, "uri");
		boolean secure = isSecure(uri);
		int port = uri.getPort() != -1 ? uri.getPort() : (secure ? 443 : 80);
		return useProxy() ? InetSocketAddress.createUnresolved(uri.getHost(), port) :
				new InetSocketAddress(uri.getHost(), port);
	}

	@Override
	public HttpClientOptions onChannelInit(Predicate<? super Channel> onChannelInit) {
		super.onChannelInit(onChannelInit);
		return this;
	}

	@Override
	public <T> HttpClientOptions option(ChannelOption<T> key, T value) {
		super.option(key, value);
		return this;
	}

	@Override
	public HttpClientOptions preferNative(boolean preferNative) {
		super.preferNative(preferNative);
		return this;
	}

	@Override
	public HttpClientOptions poolResources(PoolResources poolResources) {
		super.poolResources(poolResources);
		return this;
	}

	@Override
	public HttpClientOptions protocolFamily(InternetProtocolFamily protocolFamily) {
		super.protocolFamily(protocolFamily);
		return this;
	}

	/**
	 * Let this client connect through an HTTP proxy by providing a host and port.
	 *
	 * @param host The proxy host to connect to.
	 * @param port The proxy port to connect to.
	 *
	 * @return {@literal this}
	 */
	public HttpClientOptions proxy(@Nonnull String host, int port) {
		return proxy(InetSocketAddress.createUnresolved(host, port));
	}

	/**
	 * Let this client connect through an HTTP proxy by providing a host, port and
	 * credentials.
	 *
	 * @param host The proxy host to connect to.
	 * @param port The proxy port to connect to.
	 * @param username The proxy username to use.
	 * @param password A password-providing function for the proxy.
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
	 * Let this client connect through an HTTP proxy by providing an address.
	 *
	 * @param connectAddress The proxy address to connect to.
	 *
	 * @return {@literal this}
	 */
	public HttpClientOptions proxy(@Nonnull InetSocketAddress connectAddress) {
		super.proxy(Proxy.HTTP, connectAddress, null, null);
		return this;
	}

	/**
	 * Let this client connect through an HTTP proxy by providing an address and
	 * credentials.
	 *
	 * @param connectAddress The address to connect to.
	 * @param username The proxy username to use.
	 * @param password A password-providing function for the proxy.
	 *
	 * @return {@literal this}
	 */
	public HttpClientOptions proxy(@Nonnull InetSocketAddress connectAddress,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		super.proxy(Proxy.HTTP, connectAddress, username, password);
		return this;
	}

	/**
	 * Let this client connect through a proxy by providing a proxy type and address.
	 *
	 * @param type the proxy {@link reactor.ipc.netty.options.ClientOptions.Proxy type}.
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public HttpClientOptions proxy(@Nonnull Proxy type,
			@Nonnull Supplier<? extends InetSocketAddress> connectAddress) {
		return proxy(connectAddress, null, null);
	}

	/**
	 * Let this client connect through an HTTP proxy by providing an address supplier
	 * and credentials.
	 *
	 * @param connectAddress A supplier of the address to connect to.
	 * @param username The proxy username to use.
	 * @param password A password-providing function for the proxy.
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
	public HttpClientOptions resolver(AddressResolverGroup<?> resolver) {
		super.resolver(resolver);
		return this;
	}

	@Override
	public HttpClientOptions sslContext(SslContext sslContext) {
		super.sslContext(sslContext);
		return this;
	}

	@Override
	public HttpClientOptions sslHandshakeTimeoutMillis(long sslHandshakeTimeoutMillis) {
		super.sslHandshakeTimeoutMillis(sslHandshakeTimeoutMillis);
		return this;
	}

	@Override
	public HttpClientOptions sslHandshakeTimeout(Duration sslHandshakeTimeout) {
		super.sslHandshakeTimeout(sslHandshakeTimeout);
		return this;
	}

	@Override
	public HttpClientOptions sslSupport() {
		super.sslSupport();
		return this;
	}

	@Override
	public HttpClientOptions sslSupport(Consumer<? super SslContextBuilder> configurator) {
		super.sslSupport(configurator);
		return this;
	}

	@Override
	protected SslContext defaultSslContext() {
		return DEFAULT_SSL_CONTEXT;
	}

	final String formatSchemeAndHost(String url, boolean ws) {
		if (!url.startsWith(HttpClient.HTTP_SCHEME) && !url.startsWith(HttpClient.WS_SCHEME)) {
			final String scheme =
					(ws ? HttpClient.WS_SCHEME : HttpClient.HTTP_SCHEME) + "://";
			if (url.startsWith("/")) {
				SocketAddress remote = getAddress();

				if (remote != null && !useProxy() && remote instanceof InetSocketAddress) {
					InetSocketAddress inet = (InetSocketAddress) remote;

					return scheme + inet.getHostName() + ":" + inet.getPort() + url;
				}
				return scheme + "localhost" + url;
			}
			return scheme + url;
		}
		else {
			return url;
		}
	}

	static boolean isSecure(URI uri) {
		return uri.getScheme() != null && (uri.getScheme()
		                                      .toLowerCase()
		                                      .equals(HttpClient.HTTPS_SCHEME) || uri.getScheme()
		                                                                             .toLowerCase()
		                                                                             .equals(HttpClient.WSS_SCHEME));
	}

	static final SslContext DEFAULT_SSL_CONTEXT;

	static {
		SslContext sslContext;
		try {
			sslContext = SslContextBuilder.forClient()
			                              .build();
		}
		catch (Exception e) {
			sslContext = null;
		}
		DEFAULT_SSL_CONTEXT = sslContext;
	}
}
