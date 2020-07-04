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

package reactor.netty.transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import reactor.netty.NettyPipeline;
import reactor.util.annotation.Nullable;

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

	@SuppressWarnings("UnnecessaryLambda")
	static final Supplier<? extends HttpHeaders> NO_HTTP_HEADERS = () -> null;

	final String username;
	final Function<? super String, ? extends String> password;
	final Supplier<? extends InetSocketAddress> address;
	final Predicate<SocketAddress> shouldProxy;
	final Supplier<? extends HttpHeaders> httpHeaders;
	final Proxy type;
	final long connectTimeoutMillis;

	ProxyProvider(ProxyProvider.Build builder) {
		this.username = builder.username;
		this.password = builder.password;
		if (Objects.isNull(builder.address)) {
			this.address = () -> AddressUtils.createResolved(builder.host, builder.port);
		}
		else {
			this.address = builder.address;
		}
		if (builder.shouldProxy != null) {
			this.shouldProxy = builder.shouldProxy;
		}
		else {
			this.shouldProxy = a -> true;
		}
		if (Objects.isNull(builder.httpHeaders)) {
			this.httpHeaders = NO_HTTP_HEADERS;
		}
		else {
			this.httpHeaders = builder.httpHeaders;
		}
		this.type = builder.type;
		this.connectTimeoutMillis = builder.connectTimeoutMillis;
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
	 * A predicate {@link Predicate} on {@link SocketAddress} that returns true when the provided address should be
	 * reached directly, bypassing the proxy
	 *
	 * @return The predicate {@link Predicate} to test the incoming {@link SocketAddress} if we can reach directly,
	 * bypassing the proxy
	 */
	public final Predicate<SocketAddress> getNonProxyHosts() {
		return this.shouldProxy;
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
		final ProxyHandler proxyHandler;
		switch (this.type) {
			case HTTP:
				proxyHandler = b ?
						new HttpProxyHandler(proxyAddr, username, password, this.httpHeaders.get()) :
						new HttpProxyHandler(proxyAddr, this.httpHeaders.get());
				break;
			case SOCKS4:
				proxyHandler = Objects.nonNull(username) ? new Socks4ProxyHandler(proxyAddr, username) :
						new Socks4ProxyHandler(proxyAddr);
				break;
			case SOCKS5:
				proxyHandler = b ?
						new Socks5ProxyHandler(proxyAddr, username, password) :
						new Socks5ProxyHandler(proxyAddr);
				break;
			default:
				throw new IllegalArgumentException("Proxy type unsupported : " + this.type);
		}
		proxyHandler.setConnectTimeoutMillis(connectTimeoutMillis);
		return proxyHandler;
	}

	/**
	 * Should proxy the given address
	 *
	 * @param address the address to test
	 * @return true if of type {@link InetSocketAddress} and hostname candidate to proxy
	 */
	public boolean shouldProxy(SocketAddress address) {
		return address instanceof InetSocketAddress && shouldProxy.test(address);
	}

	/**
	 * Proxy Type
	 */
	public enum Proxy {
		HTTP, SOCKS4, SOCKS5
	}

	public void addProxyHandler(Channel channel) {
		ChannelPipeline pipeline = channel.pipeline();
		pipeline.addFirst(NettyPipeline.ProxyHandler, newProxyHandler());

		if (pipeline.get(NettyPipeline.LoggingHandler) != null) {
			pipeline.addBefore(NettyPipeline.ProxyHandler,
					NettyPipeline.ProxyLoggingHandler,
					new LoggingHandler("reactor.netty.proxy"));
		}
	}

	@Override
	public String toString() {
		return "ProxyProvider {" +
				"address=" + address.get() +
				", nonProxyHosts=" + shouldProxy +
				", type=" + type +
				'}';
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
				getType() == that.getType() &&
				connectTimeoutMillis == that.connectTimeoutMillis;
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				username, getPasswordValue(), getAddress().get(), getNonProxyHostsValue(), httpHeaders.get(), getType(), connectTimeoutMillis);
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
		Predicate<SocketAddress> shouldProxy = a -> true;
		Supplier<? extends HttpHeaders> httpHeaders;
		Proxy type;
		long connectTimeoutMillis = 10000;

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
			this.address = () -> AddressUtils.replaceWithResolved(address);
			return this;
		}

		@Override
		public final Builder address(Supplier<? extends InetSocketAddress> addressSupplier) {
			this.address = Objects.requireNonNull(addressSupplier, "addressSupplier");
			return this;
		}

		@Override
		public final Builder nonProxyHosts(String nonProxyHostsPattern) {
			return nonProxyHostsPredicate(new RegexShouldProxyPredicate(nonProxyHostsPattern));
		}

		@Override
		public final Builder nonProxyHostsPredicate(Predicate<SocketAddress> nonProxyHostsPredicate){
			this.shouldProxy = nonProxyHostsPredicate;
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
		public Builder connectTimeoutMillis(long connectTimeoutMillis) {
			this.connectTimeoutMillis = connectTimeoutMillis;
			return this;
		}

		@Override
		public ProxyProvider build() {
			return new ProxyProvider(this);
		}
	}

	static final class RegexShouldProxyPredicate implements Predicate<SocketAddress> {

		private final String regex;
		private Pattern pattern;

		public RegexShouldProxyPredicate(String pattern) {
			this.regex = pattern;
		}

		private Pattern getPattern(){
			if(pattern == null){
				pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
			}
			return pattern;
		}

		@Override
		public boolean test(SocketAddress socketAddress) {
			if(!(socketAddress instanceof InetSocketAddress)){
				return false;
			}
			InetSocketAddress isa = (InetSocketAddress) socketAddress;
			return isa.getHostName() == null || !getPattern().matcher(isa.getHostName()).matches();
		}

		@Override
		public String toString(){
			return regex;
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
		 * A standard predicate expression for a configured list of hosts that should be reached directly, bypassing
		 * the proxy.
		 *
		 * @param nonProxyHostsPredicate A Predicate {@link Predicate} on {@link SocketAddress} to determine if the
		 * address should not go through the configured proxy
		 * @return {@code this}
		 */
		Builder nonProxyHostsPredicate(Predicate<SocketAddress> nonProxyHostsPredicate);

		/**
		 * A consumer to add request headers for the http proxy.
		 *
		 * @param headers A consumer to add request headers for the http proxy.
		 * @return {@code this}
		 */
		Builder httpHeaders(Consumer<HttpHeaders> headers);

		/**
		 * The proxy connect timeout in millis. Default to 10000 ms.
		 * If this value set as non positive value, there is no connect timeout.
		 *
		 * @param connectTimeoutMillis The proxy connect timeout in millis.
		 * @return {@code this}
		 */
		Builder connectTimeoutMillis(long connectTimeoutMillis);

		/**
		 * Builds new ProxyProvider
		 *
		 * @return builds new ProxyProvider
		 */
		ProxyProvider build();
	}
}
