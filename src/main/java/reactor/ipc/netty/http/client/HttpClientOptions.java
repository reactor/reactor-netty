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

package reactor.ipc.netty.http.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.AttributeKey;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.resources.PoolResources;

/**
 * An http client connector builder with low-level connection options including
 * connection pooling and
 * proxy.
 *
 * @author Stephane Maldini
 */
public final class HttpClientOptions extends ClientOptions {

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

	HttpClientOptions(ClientOptions options) {
		super(options);
	}

	@Override
	public HttpClientOptions afterChannelInit(Consumer<? super Channel> afterChannelInit) {
		afterChannelInit(afterChannelInit);
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
	 * Return a new eventual {@link InetSocketAddress}
	 *
	 * @param uri {@link URI} to extract host and port informations from
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
		onChannelInit(onChannelInit);
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
		super.proxy(Proxy.HTTP, connectAddress, null, null);
		return this;
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
		super.proxy(Proxy.HTTP, connectAddress, username, password);
		return this;
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

	public HttpClientOptions proxy(@Nonnull Supplier<? extends InetSocketAddress> connectAddress,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password,
			@Nullable AddressResolverGroup<?> resolver) {
		super.proxy(Proxy.HTTP, connectAddress, username, password, resolver);
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

	final String formatSchemeAndHost(String url, boolean ws) {
		if (!url.startsWith(HttpClient.HTTP_SCHEME) && !url.startsWith(HttpClient.WS_SCHEME)) {
			final String parsedUrl =
					(ws ? HttpClient.WS_SCHEME : HttpClient.HTTP_SCHEME) + "://";
			if (url.startsWith("/")) {
				InetSocketAddress remote = getRemoteAddress();

				return parsedUrl + (remote != null && !useProxy() ?
						remote.getHostName() + ":" + remote.getPort() :
						"localhost") + url;
			}
			else {
				return parsedUrl + url;
			}
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
}
