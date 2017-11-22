/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
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
import reactor.ipc.netty.tcp.InetSocketAddressUtil;

/**
 * A client connector builder with low-level connection options including connection pooling and
 * proxy.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
public class ClientOptions extends NettyOptions<Bootstrap, ClientOptions> {

	/**
	 * Creates a builder for {@link ClientOptions ClientOptions}
	 *
	 * @param <BUILDER> A ClientOptions.Builder subclass
	 * @return a new ClientOptions builder
	 */
	public static <BUILDER extends ClientOptions.Builder<BUILDER>> ClientOptions.Builder<BUILDER> builder() {
		return builder(new Bootstrap());
	}

	/**
	 * Creates a builder for {@link ClientOptions ClientOptions}
	 *
	 * @param bootstrap the bootstrap reference to use
	 * @param <BUILDER> A ClientOptions.Builder subclass
	 * @return a new ClientOptions builder
	 */
	public static <BUILDER extends ClientOptions.Builder<BUILDER>> ClientOptions.Builder<BUILDER> builder(Bootstrap bootstrap) {
		return new ClientOptions.Builder<>(bootstrap);
	}

	/**
	 * Client connection pool selector
	 */
	private final PoolResources poolResources;

	/**
	 * Proxy options
	 */
	private final ClientProxyOptions proxyOptions;

	private final InternetProtocolFamily protocolFamily;
	private final Supplier<? extends SocketAddress> connectAddress;

	/**
	 * Deep-copy all references from the passed builder into this new
	 * {@link NettyOptions} instance.
	 *
	 * @param builder the ClientOptions builder
	 */
	protected ClientOptions(ClientOptions.Builder<?> builder){
		super(builder);
		this.proxyOptions = builder.proxyOptions;
		if (Objects.isNull(builder.connectAddress)) {
			if (builder.port >= 0) {
				if (Objects.isNull(builder.host)) {
					this.connectAddress = () -> new InetSocketAddress(NetUtil.LOCALHOST, builder.port);
				}
				else {
					this.connectAddress = () -> InetSocketAddressUtil.createUnresolved(builder.host, builder.port);
				}
			}
			else {
				this.connectAddress = null;
			}
		}
		else {
			this.connectAddress = builder.connectAddress;
		}
		this.poolResources = builder.poolResources;
		this.protocolFamily = builder.protocolFamily;
	}

	@Override
	public ClientOptions duplicate() {
		return builder().from(this).build();
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
		return this.poolResources;
	}

	public final ClientProxyOptions getProxyOptions() {
		return this.proxyOptions;
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
	 * Return true if proxy options have been set and the host is not
	 * configured to bypass the proxy.
	 *
	 * @param address The address to which this client should connect.
	 * @return true if proxy options have been set and the host is not
	 * configured to bypass the proxy.
	 */
	public boolean useProxy(SocketAddress address) {
		if (this.proxyOptions != null) { 
			if (this.proxyOptions.getNonProxyHosts() != null && 
					address instanceof InetSocketAddress) {
				String hostName = ((InetSocketAddress) address).getHostString();
				return !this.proxyOptions.getNonProxyHosts().matcher(hostName).matches();
			}
			return true;
		}
		return false;
	}

	/**
	 * Return true if proxy options have been set and the host is not
	 * configured to bypass the proxy.
	 *
	 * @param hostName The host name to which this client should connect.
	 * @return true if proxy options have been set and the host is not
	 * configured to bypass the proxy.
	 */
	public boolean useProxy(String hostName) {
		if (this.proxyOptions != null) { 
			if (this.proxyOptions.getNonProxyHosts() != null && hostName != null) {
				return !this.proxyOptions.getNonProxyHosts().matcher(hostName).matches();
			}
			return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	final void groupAndChannel(Bootstrap bootstrap) {
		LoopResources loops = Objects.requireNonNull(getLoopResources(), "loopResources");

		boolean useNative =
				this.protocolFamily == null && preferNative() && !(sslContext() instanceof JdkSslContext);
		EventLoopGroup elg = loops.onClient(useNative);

		if (this.poolResources != null && elg instanceof Supplier) {
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
		if (proxyOptions != null) {
			s.append(" through ").append(proxyOptions.getType()).append(" proxy");
		}
		return s.toString();
	}

	@Override
	public String asDetailedString() {
		if (proxyOptions == null) {
			return "connectAddress=" + getAddress() + ", proxy=null, " + super.asDetailedString();
		}
		return "connectAddress=" + getAddress() +
				", " + proxyOptions.asSimpleString() + ", " +
				super.asDetailedString();
	}

	@Override
	public String toString() {
		return "ClientOptions{" + asDetailedString() + "}";
	}

	protected InetSocketAddress createInetSocketAddress(String hostname, int port, boolean resolve) {
		return InetSocketAddressUtil.createInetSocketAddress(hostname, port, resolve);
	}

	public static class Builder<BUILDER extends Builder<BUILDER>>
			extends NettyOptions.Builder<Bootstrap, ClientOptions, BUILDER> {
		private PoolResources poolResources;
		private boolean poolDisabled = false;
		private InternetProtocolFamily protocolFamily;
		private String host;
		private int port = -1;
		private Supplier<? extends SocketAddress> connectAddress;
		private ClientProxyOptions proxyOptions;

		/**
		 * Apply common option via super constructor then apply
		 * {@link #defaultClientOptions(Bootstrap)}
		 * to the passed bootstrap.
		 *
		 * @param bootstrapTemplate the bootstrap reference to use
		 */
		protected Builder(Bootstrap bootstrapTemplate) {
			super(bootstrapTemplate);
			defaultClientOptions(bootstrapTemplate);
		}

		private void defaultClientOptions(Bootstrap bootstrap) {
			bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
			         .option(ChannelOption.AUTO_READ, false)
			         .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
			         .option(ChannelOption.SO_SNDBUF, 1024 * 1024);
		}

		/**
		 * Assign an {@link AddressResolverGroup}.
		 *
		 * @param resolver the new {@link AddressResolverGroup}
		 * @return {@code this}
		 */
		public final BUILDER resolver(AddressResolverGroup<?> resolver) {
			Objects.requireNonNull(resolver, "resolver");
			this.bootstrapTemplate.resolver(resolver);
			return get();
		}

		/**
		 * Configures the {@link ChannelPool} selector for the socket. Will effectively
		 * enable client connection-pooling.
		 *
		 * @param poolResources the {@link PoolResources} given
		 * an {@link InetSocketAddress}
		 * @return {@code this}
		 */
		public final BUILDER poolResources(PoolResources poolResources) {
			this.poolResources = Objects.requireNonNull(poolResources, "poolResources");
			this.poolDisabled = false;
			return get();
		}

		/**
		 * Disable current {@link #poolResources}
		 *
		 * @return {@code this}
		 */
		public BUILDER disablePool() {
			this.poolResources = null;
			this.poolDisabled = true;
			return get();
		}

		public final boolean isPoolDisabled() {
			return poolDisabled;
		}

		public final boolean isPoolAvailable() {
			return this.poolResources != null;
		}

		/**
		 * Configures the version family for the socket.
		 *
		 * @param protocolFamily the version family for the socket, or null for the system
		 * default family
		 * @return {@code this}
		 */
		public final BUILDER protocolFamily(InternetProtocolFamily protocolFamily) {
			this.protocolFamily = Objects.requireNonNull(protocolFamily, "protocolFamily");
			return get();
		}

		/**
		 * Enable default sslContext support
		 *
		 * @return {@code this}
		 */
		public final BUILDER sslSupport() {
			return sslSupport(c -> {
			});
		}

		/**
		 * Enable default sslContext support and enable further customization via the passed
		 * configurator. The builder will then produce the {@link SslContext} to be passed to
		 * {@link #sslContext(SslContext)}.
		 *
		 * @param configurator builder callback for further customization.
		 * @return {@code this}
		 */
		public final BUILDER sslSupport(Consumer<? super SslContextBuilder> configurator) {
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

		/**
		 * The host to which this client should connect.
		 *
		 * @param host The host to connect to.
		 * @return {@code this}
		 */
		public final BUILDER host(String host) {
			if (Objects.isNull(host)) {
				this.host = NetUtil.LOCALHOST.getHostAddress();
			}
			else {
				this.host = host;
			}
			return get();
		}

		/**
		 * The port to which this client should connect.
		 *
		 * @param port The port to connect to.
		 * @return {@code this}
		 */
		public final BUILDER port(int port) {
			this.port = Objects.requireNonNull(port, "port");
			return get();
		}

		/**
		 * The address to which this client should connect.
		 *
		 * @param connectAddressSupplier A supplier of the address to connect to.
		 * @return {@code this}
		 */
		public final BUILDER connectAddress(Supplier<? extends SocketAddress> connectAddressSupplier) {
			this.connectAddress = Objects.requireNonNull(connectAddressSupplier, "connectAddressSupplier");
			return get();
		}

		/**
		 * The proxy configuration
		 *
		 * @param proxyOptions the proxy configuration
		 * @return {@code this}
		 */
		public final BUILDER proxy(Function<ClientProxyOptions.TypeSpec, ClientProxyOptions.Builder> proxyOptions) {
			Objects.requireNonNull(proxyOptions, "proxyOptions");
			ClientProxyOptions.Builder builder = proxyOptions.apply(ClientProxyOptions.builder());
			this.proxyOptions = builder.build();
			if(bootstrapTemplate.config().resolver() == DefaultAddressResolverGroup.INSTANCE) {
				resolver(NoopAddressResolverGroup.INSTANCE);
			}
			return get();
		}

		@Override
		public final BUILDER from(ClientOptions options) {
			super.from(options);
			this.proxyOptions = options.proxyOptions;
			this.connectAddress = options.connectAddress;
			this.poolResources = options.poolResources;
			this.protocolFamily = options.protocolFamily;
			return get();
		}

		@Override
		@SuppressWarnings("unchecked")
		public BUILDER get() {
			return (BUILDER) this;
		}

		public ClientOptions build() {
			return new ClientOptions(this);
		}
	}
}
