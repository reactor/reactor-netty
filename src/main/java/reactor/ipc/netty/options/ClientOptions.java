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
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.NetUtil;
import reactor.core.Exceptions;

/**
 * A client connector builder with low-level connection options including connection pooling and
 * proxy.
 *
 * @author Stephane Maldini
 */
public class ClientOptions extends NettyOptions<Bootstrap, ClientOptions> {

	/**
	 * Create a client builder.
	 *
	 * @return a new client options builder
	 */
	public static ClientOptions create() {
		return new ClientOptions();
	}

	static void defaultClientOptions(Bootstrap bootstrap) {
		bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
		         .option(ChannelOption.AUTO_READ, false)
		         .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
		         .option(ChannelOption.SO_SNDBUF, 1024 * 1024);
	}
	/**
	 * Client connection pool selector
	 */
	BiFunction<? super InetSocketAddress, Supplier<? extends Bootstrap>, ? extends ChannelPool>
			poolSelector;

	/**
	 * Proxy options
	 */
	String                                     proxyUsername;
	Function<? super String, ? extends String> proxyPassword;
	Supplier<? extends InetSocketAddress>      proxyAddress;
	Proxy                                      proxyType;

	InternetProtocolFamily                protocolFamily = null;
	Supplier<? extends InetSocketAddress> connectAddress = null;

	/**
	 * Build a new {@link Bootstrap}
	 */
	protected ClientOptions() {
		this(new Bootstrap());
	}

	/**
	 * Apply common option via super constructor then apply
	 * {@link #defaultClientOptions(Bootstrap)}
	 * to the passed bootstrap.
	 *
	 * @param bootstrap the bootstrap reference to use
	 */
	protected ClientOptions(Bootstrap bootstrap) {
		super(bootstrap);
		defaultClientOptions(bootstrap);
	}

	/**
	 * Deep-copy all references from the passed options into this new
	 * {@link NettyOptions} instance.
	 *
	 * @param options the source options to duplicate
	 */
	protected ClientOptions(ClientOptions options){
		super(options);
		this.proxyUsername = options.proxyUsername;
		this.proxyPassword = options.proxyPassword;
		this.proxyAddress = options.proxyAddress;
		this.proxyType = options.proxyType;
		this.connectAddress = options.connectAddress;
		this.protocolFamily = options.protocolFamily;
	}

	/**
	 * The localhost port to which this client should connect.
	 *
	 * @param port The port to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions connect(int port) {
		return connect(new InetSocketAddress(port));
	}

	/**
	 * The host and port to which this client should connect.
	 *
	 * @param host The host to connect to.
	 * @param port The port to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions connect(@Nonnull String host, int port) {
		return connect(InetSocketAddress.createUnresolved(host, port));
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions connect(@Nonnull InetSocketAddress connectAddress) {
		return connect(() -> connectAddress);
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions connect(@Nonnull Supplier<? extends InetSocketAddress> connectAddress) {
		this.connectAddress = Objects.requireNonNull(connectAddress, "connectAddress");
		return this;
	}

	/**
	 * Disable current {@link #poolSelector}
	 *
	 * @return {@code this}
	 */
	public ClientOptions disablePool() {
		this.poolSelector = null;
		return this;
	}

	@Override
	public ClientOptions duplicate() {
		return new ClientOptions(this);
	}

	@Override
	public Bootstrap get() {
		return get(null);
	}

	/**
	 * Return a new {@link Bootstrap} given an optional address or the current
	 * {@link #connect(Supplier)} resolved.
	 *
	 * @param address an optional address
	 *
	 * @return a new {@link Bootstrap}
	 */
	public final Bootstrap get(InetSocketAddress address) {
		Bootstrap b = super.get();
		InetSocketAddress adr;
		if (address != null) {
			adr = address;
		}
		else if (connectAddress != null) {
			adr = connectAddress.get();
		}
		else {
			adr = null;
		}

		if (adr != null) {
			if (useDatagramChannel()) {
				b.localAddress(adr);
			}
			else {
				b.remoteAddress(adr);
			}
		}
		groupAndChannel(b);
		return b;
	}

	/**
	 * Select a channel pool from the given address.
	 *
	 * @param address the optional address to use
	 *
	 * @return an eventual {@link ChannelPool}
	 */
	public final ChannelPool getPool(InetSocketAddress address) {
		if (poolSelector == null) {
			return null;
		}
		address = address == null && connectAddress != null ? connectAddress.get() :
				address;
		return poolSelector.apply(address, this);
	}

	/**
	 * Return a new eventual {@link ProxyHandler}
	 *
	 * @return a new eventual {@link ProxyHandler}
	 */
	public final ProxyHandler getProxyHandler() {
		if (proxyType == null) {
			return null;
		}
		InetSocketAddress proxyAddr = proxyAddress.get();
		String username = proxyUsername;
		String password = username != null && proxyPassword != null ?
				proxyPassword.apply(username) : null;

		switch (proxyType) {
			case HTTP:
				return username != null && password != null ?
						new HttpProxyHandler(proxyAddr, username, password) :
						new HttpProxyHandler(proxyAddr);
			case SOCKS4:
				return username != null ? new Socks4ProxyHandler(proxyAddr, username) :
						new Socks4ProxyHandler(proxyAddr);
			case SOCKS5:
				return username != null && password != null ?
						new Socks5ProxyHandler(proxyAddr, username, password) :
						new Socks5ProxyHandler(proxyAddr);
		}
		throw new IllegalArgumentException("Proxy type unsupported : " + proxyType);
	}

	/**
	 * Resolve the latest {@link #connect)} address
	 *
	 * @return the resolved address if any
	 */
	public final InetSocketAddress getRemoteAddress() {
		return null != connectAddress ? connectAddress.get() : null;
	}

	/**
	 * Configures the {@link ChannelPool} selector for the socket. Will effectively
	 * enable client connection-pooling.
	 *
	 * @param poolSelector the {@link BiFunction} to compute a {@link ChannelPool} given
	 * an {@link InetSocketAddress}
	 *
	 * @return {@code this}
	 */
	public ClientOptions poolSelector(BiFunction<? super InetSocketAddress, Supplier<? extends Bootstrap>, ? extends ChannelPool> poolSelector) {
		Objects.requireNonNull(poolSelector, "poolSelector");
		this.poolSelector = poolSelector;
		return this;
	}

	/**
	 * Configures the version family for the socket.
	 *
	 * @param protocolFamily the version family for the socket, or null for the system
	 * default family
	 *
	 * @return {@code this}
	 */
	public ClientOptions protocolFamily(InternetProtocolFamily protocolFamily) {
		Objects.requireNonNull(protocolFamily, "protocolFamily");
		this.protocolFamily = protocolFamily;
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
	public ClientOptions proxy(@Nonnull Proxy type,
			@Nonnull String host,
			int port,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		return proxy(type,
				InetSocketAddress.createUnresolved(host, port),
				username,
				password);
	}

	/**
	 * The host and port to which this client should connect.
	 *
	 * @param host The host to connect to.
	 * @param port The port to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull Proxy type, @Nonnull String host, int port) {
		return proxy(type, InetSocketAddress.createUnresolved(host, port));
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull Proxy type, @Nonnull InetSocketAddress connectAddress) {
		return proxy(type, connectAddress, null, null);
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull Proxy type,
			@Nonnull InetSocketAddress connectAddress,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		return proxy(type, connectAddress.isUnresolved() ?
						() -> new InetSocketAddress(connectAddress.getHostName(),
								connectAddress.getPort()) :
						() -> connectAddress,
				username,
				password);
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
		return proxy(type, connectAddress, null, null);
	}

	/**
	 * The address to which this client should connect.
	 *
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull Proxy type,
			@Nonnull Supplier<? extends InetSocketAddress> connectAddress,
			@Nullable String username,
			@Nullable Function<? super String, ? extends String> password) {
		this.proxyUsername = username;
		this.proxyPassword = password;
		this.proxyAddress = Objects.requireNonNull(connectAddress, "addressSupplier");
		this.proxyType = Objects.requireNonNull(type, "proxyType");
		bootstrapTemplate.resolver(NoopAddressResolverGroup.INSTANCE);
		return this;
	}

	/**
	 * Enable default sslContext support
	 *
	 * @return this {@link ClientOptions}
	 */
	public ClientOptions sslSupport() {
		return sslSupport(c -> {
		});
	}

	/**
	 * Enable default sslContext support and enable further customization via the passed
	 * configurator. The builder will then produce the {@link SslContext} to be passed to
	 * {@link #sslContext(SslContext)}.
	 *
	 * @param configurator builder callback for further customization.
	 *
	 * @return this {@link ClientOptions}
	 */
	public ClientOptions sslSupport(Consumer<? super SslContextBuilder> configurator) {
		Objects.requireNonNull(configurator, "configurator");
		try {
			SslContextBuilder builder = SslContextBuilder.forClient();
			configurator.accept(builder);
			return sslContext(builder.build());
		}
		catch (Exception sslException) {
			throw Exceptions.bubble(sslException);
		}
	}

//

	/**
	 * Proxy Type
	 */
	public enum Proxy {
		HTTP, SOCKS4, SOCKS5

	}

	/**
	 * Return true if {@link io.netty.channel.socket.DatagramChannel} should be used
	 *
	 * @return true if {@link io.netty.channel.socket.DatagramChannel} should be used
	 */
	protected boolean useDatagramChannel() {
		return false;
	}

	/**
	 * Return true if proxy options have been set
	 *
	 * @return true if proxy options have been set
	 */
	protected boolean useProxy() {
		return proxyType != null;
	}

	final void groupAndChannel(Bootstrap bootstrap) {
		ChannelResources loops =
				Objects.requireNonNull(this.channelResources, "channelResources");

		boolean useNative = preferNative && !(sslContext instanceof JdkSslContext);
		EventLoopGroup elg = loops.onClient(useNative);
		bootstrap.group(elg);

		if (useDatagramChannel()) {
			if (useNative && protocolFamily == null) {
				bootstrap.channel(loops.onDatagramChannel(elg));
			}
			else {
				bootstrap.channelFactory(() -> new NioDatagramChannel(protocolFamily));
			}
		}
		else {
			bootstrap.channel(loops.onChannel(elg));
		}
	}


}
