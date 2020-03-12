/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.tcp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.BootstrapHandlers;

/**
 * Proxy configuration
 *
 * @author Violeta Georgieva
 */
public final class ProxyProvider {

	/**
	 * Creates a builder for {@link ProxyProvider ProxyProvider}
	 *
	 * @return a new ProxyProvider builder
	 */
	public static ProxyProvider.TypeSpec builder() {
		return new ProxyProvider.Build();
	}

	/**
	 * Find Proxy support in the given client bootstrap
	 *
	 * @param b a bootstrap to search
	 *
	 * @return any {@link ProxyProvider} found or null
	 */
	@Nullable
	public static ProxyProvider findProxySupport(Bootstrap b) {
		ProxyProvider.DeferredProxySupport proxy =
				BootstrapHandlers.findConfiguration(ProxyProvider.DeferredProxySupport.class, b.config().handler());

		if (proxy == null) {
			return null;
		}
		return proxy.proxyProvider;
	}

	private static final Supplier<? extends HttpHeaders> NO_HTTP_HEADERS = () -> null;

	final String username;
	final Function<? super String, ? extends String> password;
	final Supplier<? extends InetSocketAddress> address;
	final Pattern nonProxyHosts;
	final Supplier<? extends HttpHeaders> httpHeaders;
	final Proxy type;

	ProxyProvider(ProxyProvider.Build builder) {
		this.username = builder.username;
		this.password = builder.password;
		if (Objects.isNull(builder.address)) {
			this.address = () -> InetSocketAddressUtil.createResolved(builder.host, builder.port);
		}
		else {
			this.address = builder.address;
		}
		if (builder.nonProxyHosts != null) {
			this.nonProxyHosts = Pattern.compile(builder.nonProxyHosts, Pattern.CASE_INSENSITIVE);
		}
		else {
			this.nonProxyHosts = null;
		}
		if (Objects.isNull(builder.httpHeaders)) {
			this.httpHeaders = NO_HTTP_HEADERS;
		}
		else {
			this.httpHeaders = builder.httpHeaders;
		}
		this.type = builder.type;
	}

	/**
	 * The proxy type
	 *
	 * @return The proxy type
	 */
	public final Proxy getType() {
		return this.type;
	}

	/**
	 * The supplier for the address to connect to.
	 *
	 * @return The supplier for the address to connect to.
	 */
	public final Supplier<? extends InetSocketAddress> getAddress() {
		return this.address;
	}

	/**
	 * Regular expression (<code>using java.util.regex</code>) for a configured
	 * list of hosts that should be reached directly, bypassing the proxy.
	 *
	 * @return Regular expression (<code>using java.util.regex</code>) for
	 * a configured list of hosts that should be reached directly, bypassing the
	 * proxy.
	 */
	@Nullable
	public final Pattern getNonProxyHosts() {
		return this.nonProxyHosts;
	}

	/**
	 * Return a new eventual {@link ProxyHandler}
	 *
	 * @return a new eventual {@link ProxyHandler}
	 */
	public final ProxyHandler newProxyHandler() {
		InetSocketAddress proxyAddr = this.address.get();
		String username = this.username;
		String password = Objects.nonNull(username) && Objects.nonNull(this.password) ?
				this.password.apply(username) : null;

		final boolean b = Objects.nonNull(username) && Objects.nonNull(password);
		switch (this.type) {
			case HTTP:
				return b ?
						new HttpProxyHandler(proxyAddr, username, password, this.httpHeaders.get()) :
						new HttpProxyHandler(proxyAddr, this.httpHeaders.get());
			case SOCKS4:
				return Objects.nonNull(username) ? new Socks4ProxyHandler(proxyAddr, username) :
						new Socks4ProxyHandler(proxyAddr);
			case SOCKS5:
				return b ?
						new Socks5ProxyHandler(proxyAddr, username, password) :
						new Socks5ProxyHandler(proxyAddr);
		}
		throw new IllegalArgumentException("Proxy type unsupported : " + this.type);
	}

	/**
	 * Should proxy the given address
	 *
	 * @param address the address to test
	 *
	 * @return true if of type {@link InetSocketAddress} and hostname candidate to proxy
	 */
	public boolean shouldProxy(SocketAddress address) {
		SocketAddress addr = address;
		if (address instanceof TcpUtils.SocketAddressSupplier) {
			addr = ((TcpUtils.SocketAddressSupplier) address).get();
		}
		return addr instanceof InetSocketAddress && shouldProxy(((InetSocketAddress) addr).getHostString());
	}

	/**
	 * Should proxy the given hostname
	 *
	 * @param hostName the hostname to test
	 *
	 * @return true if candidate to proxy
	 */
	public boolean shouldProxy(@Nullable String hostName) {
		return nonProxyHosts == null || hostName == null || !nonProxyHosts.matcher(hostName)
		                                                                  .matches();
	}

	/**
	 * Proxy Type
	 */
	public enum Proxy {
		HTTP, SOCKS4, SOCKS5
	}


	public String asSimpleString() {
		return "proxy=" + this.type +
				"(" + this.address.get() + ")";
	}

	public String asDetailedString() {
		return "address=" + this.address.get() +
				", nonProxyHosts=" + this.nonProxyHosts +
				", type=" + this.type;
	}

	@Override
	public String toString() {
		return "ProxyProvider{" + asDetailedString() + "}";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		ProxyProvider that = (ProxyProvider) o;
		return Objects.equals(username, that.username) &&
				Objects.equals(getPasswordValue(), that.getPasswordValue()) &&
				Objects.equals(getAddress().get(), that.getAddress().get()) &&
				Objects.equals(getNonProxyHostsValue(), that.getNonProxyHostsValue()) &&
				Objects.equals(httpHeaders.get(), that.httpHeaders.get()) &&
				getType() == that.getType();
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				username, getPasswordValue(), getAddress().get(), getNonProxyHostsValue(), httpHeaders.get(), getType());
	}

	@Nullable
	private String getNonProxyHostsValue() {
		return (getNonProxyHosts() == null) ? null : getNonProxyHosts().toString();
	}

	@Nullable
	private String getPasswordValue() {
		if (username == null || password == null) {
			return null;
		}
		return password.apply(username);
	}

	static final class Build implements TypeSpec, AddressSpec, Builder {
		String username;
		Function<? super String, ? extends String> password;
		String host;
		int port;
		Supplier<? extends InetSocketAddress> address;
		String nonProxyHosts;
		Supplier<? extends HttpHeaders> httpHeaders;
		Proxy type;

		Build() {
		}

		@Override
		public final Builder username(String username) {
			this.username = username;
			return this;
		}

		@Override
		public final Builder password(Function<? super String, ? extends String> password) {
			this.password = password;
			return this;
		}

		@Override
		public final Builder host(String host) {
			this.host = Objects.requireNonNull(host, "host");
			return this;
		}

		@Override
		public final Builder port(int port) {
			this.port = port;
			return this;
		}

		@Override
		public final Builder address(InetSocketAddress address) {
			Objects.requireNonNull(address, "address");
			this.address = () -> InetSocketAddressUtil.replaceWithResolved(address);
			return this;
		}

		@Override
		public final Builder address(Supplier<? extends InetSocketAddress> addressSupplier) {
			this.address = Objects.requireNonNull(addressSupplier, "addressSupplier");
			return this;
		}

		@Override
		public final Builder nonProxyHosts(String nonProxyHostsPattern) {
			this.nonProxyHosts = nonProxyHostsPattern;
			return this;
		}

		@Override
		public Builder httpHeaders(Consumer<HttpHeaders> headers) {
			this.httpHeaders = () -> new DefaultHttpHeaders() {
				{
					headers.accept(this);
				}
			};
			return this;
		}

		@Override
		public final AddressSpec type(Proxy type) {
			this.type = Objects.requireNonNull(type, "type");
			return this;
		}

		@Override
		public ProxyProvider build() {
			return new ProxyProvider(this);
		}
	}

	public interface TypeSpec {

		/**
		 * The proxy type.
		 *
		 * @param type The proxy type.
		 * @return {@code this}
		 */
		AddressSpec type(Proxy type);
	}

	public interface AddressSpec {

		/**
		 * The proxy host to connect to.
		 *
		 * @param host The proxy host to connect to.
		 * @return {@code this}
		 */
		Builder host(String host);

		/**
		 * The address to connect to.
		 *
		 * @param address The address to connect to.
		 * @return {@code this}
		 */
		Builder address(InetSocketAddress address);

		/**
		 * The supplier for the address to connect to.
		 *
		 * @param addressSupplier The supplier for the address to connect to.
		 * @return {@code this}
		 */
		Builder address(Supplier<? extends InetSocketAddress> addressSupplier);
	}

	public interface Builder {

		/**
		 * The proxy username.
		 *
		 * @param username The proxy username.
		 * @return {@code this}
		 */
		Builder username(String username);

		/**
		 * A function to supply the proxy's password from the username.
		 *
		 * @param password A function to supply the proxy's password from the username.
		 * @return {@code this}
		 */
		Builder password(Function<? super String, ? extends String> password);

		/**
		 * The proxy port to connect to.
		 *
		 * @param port The proxy port to connect to.
		 * @return {@code this}
		 */
		Builder port(int port);

		/**
		 * Regular expression (<code>using java.util.regex</code>) for a configured
		 * list of hosts that should be reached directly, bypassing the proxy.
		 *
		 * @param nonProxyHostsPattern Regular expression (<code>using java.util.regex</code>)
		 * for a configured list of hosts that should be reached directly, bypassing the proxy.
		 * @return {@code this}
		 */
		Builder nonProxyHosts(String nonProxyHostsPattern);

		/**
		 * A consumer to add request headers for the http proxy.
		 *
		 * @param headers A consumer to add request headers for the http proxy.
		 * @return {@code this}
		 */
		Builder httpHeaders(Consumer<HttpHeaders> headers);

		/**
		 * Builds new ProxyProvider
		 *
		 * @return builds new ProxyProvider
		 */
		ProxyProvider build();
	}

	static final class DeferredProxySupport
			implements Function<Bootstrap, BiConsumer<ConnectionObserver, Channel>> {

		final ProxyProvider proxyProvider;

		DeferredProxySupport(ProxyProvider proxyProvider) {
			this.proxyProvider = proxyProvider;
		}

		@Override
		public BiConsumer<ConnectionObserver, Channel> apply(Bootstrap bootstrap) {
			return new ProxySupportConsumer(bootstrap, proxyProvider);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			DeferredProxySupport that = (DeferredProxySupport) o;
			return Objects.equals(proxyProvider, that.proxyProvider);
		}

		@Override
		public int hashCode() {
			return Objects.hash(proxyProvider);
		}
	}

	static final class ProxySupportConsumer
			implements BiConsumer<ConnectionObserver, Channel> {

		final Bootstrap bootstrap;
		final ProxyProvider proxyProvider;

		ProxySupportConsumer(Bootstrap bootstrap, ProxyProvider proxyProvider) {
			this.bootstrap = bootstrap;
			this.proxyProvider = proxyProvider;
		}

		@Override
		public void accept(ConnectionObserver connectionObserver, Channel channel) {
			if (proxyProvider.shouldProxy(bootstrap.config()
			                                       .remoteAddress())) {

				ChannelPipeline pipeline = channel.pipeline();
				pipeline.addFirst(NettyPipeline.ProxyHandler,
								proxyProvider.newProxyHandler());

				if (channel.pipeline()
				           .get(NettyPipeline.LoggingHandler) != null) {
					pipeline.addBefore(NettyPipeline.ProxyHandler,
					                   NettyPipeline.ProxyLoggingHandler,
					                   new LoggingHandler("reactor.netty.proxy"));
				}
			}
		}
	}
}
