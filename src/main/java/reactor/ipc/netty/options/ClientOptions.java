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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
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
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.NetUtil;
import reactor.core.Exceptions;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.resources.PoolResources;

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
	PoolResources poolResources;

	/**
	 * Proxy options
	 */
	String                                     proxyUsername;
	Function<? super String, ? extends String> proxyPassword;
	Supplier<? extends InetSocketAddress>      proxyAddress;
	Proxy                                      proxyType;

	InternetProtocolFamily                protocolFamily = null;
	Supplier<? extends SocketAddress> connectAddress = null;

	/**
	 * Build a new {@link ClientOptions} out of a new {@link Bootstrap}
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
		this.poolResources = options.poolResources;
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
		return connect(new InetSocketAddress(NetUtil.LOCALHOST.getHostAddress(), port));
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
	 * @param connectAddress A supplier of the address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions connect(@Nonnull Supplier<? extends InetSocketAddress> connectAddress) {
		this.connectAddress = Objects.requireNonNull(connectAddress, "connectAddress");
		return this;
	}

	/**
	 * Disable current {@link #poolResources}
	 *
	 * @return {@code this}
	 */
	public ClientOptions disablePool() {
		this.poolResources = null;
		return this;
	}

	@Override
	public ClientOptions duplicate() {
		return new ClientOptions(this);
	}

	@Override
	public Bootstrap get() {
		Bootstrap b = super.get();
		groupAndChannel(b);
		return b;
	}

	@Override
	public final SocketAddress getAddress() {
		return connectAddress == null ? null : connectAddress.get();
	}

	/**
	 * Get the configured Pool Resources if any
	 *
	 * @return an eventual {@link PoolResources}
	 */
	public final PoolResources getPoolResources() {
		return poolResources;
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
	 * Configures the {@link ChannelPool} selector for the socket. Will effectively
	 * enable client connection-pooling.
	 *
	 * @param poolResources the {@link PoolResources} given
	 * an {@link InetSocketAddress}
	 *
	 * @return {@code this}
	 */
	public ClientOptions poolResources(PoolResources poolResources) {
		Objects.requireNonNull(poolResources, "poolResources");
		this.poolResources = poolResources;
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
	 * Let this client connect through a proxy by providing a host, port and credentials.
	 *
	 * @param type The proxy type.
	 * @param host The proxy host to connect to.
	 * @param port The proxy port to connect to.
	 * @param username The proxy username.
	 * @param password A function to supply the proxy's password from the username.
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
	 * Let this client connect through a proxy by providing a host and port.
	 *
	 * @param type The proxy type.
	 * @param host The host to connect to.
	 * @param port The port to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull Proxy type, @Nonnull String host, int port) {
		return proxy(type, InetSocketAddress.createUnresolved(host, port));
	}

	/**
	 * Let this client connect through a proxy by providing an address.
	 *
	 * @param type The proxy type.
	 * @param connectAddress The address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull Proxy type, @Nonnull InetSocketAddress connectAddress) {
		return proxy(type, connectAddress, null, null);
	}

	/**
	 * Let this client connect through a proxy by providing an address and credentials.
	 *
	 * @param type The proxy type.
	 * @param connectAddress The address to connect to.
	 * @param username The proxy username.
	 * @param password A function to supply the proxy's password from the username.
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
	 * Let this client connect through a proxy by providing an address supplier.
	 *
	 * @param type The proxy type.
	 * @param connectAddress The supplier for the address to connect to.
	 *
	 * @return {@literal this}
	 */
	public ClientOptions proxy(@Nonnull Proxy type,
			@Nonnull Supplier<? extends InetSocketAddress> connectAddress) {
		return proxy(type, connectAddress, null, null);
	}

	/**
	 * Let this client connect through a proxy by providing an address supplier.
	 *
	 * @param type The proxy type.
	 * @param connectAddress The supplier for the address to connect to.
	 * @param username The proxy username.
	 * @param password A function to supply the proxy's password from the username.
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
		if(bootstrapTemplate.config().resolver() == DefaultAddressResolverGroup.INSTANCE){
			resolver(NoopAddressResolverGroup.INSTANCE);
		}
		return this;
	}

	/**
	 * Assign an {@link AddressResolverGroup}.
	 * @param resolver the new {@link AddressResolverGroup}
	 *
	 * @return this {@link ClientOptions}
	 */
	public ClientOptions resolver(AddressResolverGroup<?> resolver) {
		Objects.requireNonNull(resolver, "resolver");
		bootstrapTemplate.resolver(resolver);
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

	@SuppressWarnings("unchecked")
	final void groupAndChannel(Bootstrap bootstrap) {
		LoopResources loops = Objects.requireNonNull(this.loopResources, "loopResources");

		boolean useNative =
				protocolFamily == null && preferNative && !(sslContext instanceof JdkSslContext);
		EventLoopGroup elg = loops.onClient(useNative);

		if (poolResources != null && elg instanceof Supplier) {
			//don't colocate
			bootstrap.group(((Supplier<EventLoopGroup>) elg).get());
		}
		else {
			bootstrap.group(elg);
		}

		if (useDatagramChannel()) {
			if (useNative) {
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


	@Override
	public String asSimpleString() {
		StringBuilder s = new StringBuilder();
		if (getAddress() == null) {
			s.append("connecting to no base address");
		}
		else {
			s.append("connecting to ").append(getAddress());
		}
		if (proxyType != null) {
			s.append(" through ").append(proxyType).append(" proxy");
		}
		return s.toString();
	}

	@Override
	public String asDetailedString() {
		if (proxyType == null) {
			return "connectAddress=" + getAddress() + ", proxy=null, " + super.asDetailedString();
		}
		return "connectAddress=" + getAddress() +
				", proxy=" + proxyType +
				"(" + proxyAddress.get() + "), " +
				super.asDetailedString();
	}

	@Override
	public String toString() {
		return "ClientOptions{" + asDetailedString() + "}";
	}
}
