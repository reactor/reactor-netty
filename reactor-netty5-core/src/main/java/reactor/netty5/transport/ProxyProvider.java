/*
 * Copyright (c) 2017-2024 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty5.transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelPipeline;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.logging.LoggingHandler;
import io.netty.contrib.handler.proxy.ProxyHandler;
import io.netty.contrib.handler.proxy.Socks4ProxyHandler;
import io.netty.contrib.handler.proxy.Socks5ProxyHandler;
import io.netty5.util.internal.StringUtil;
import reactor.netty5.NettyPipeline;
import reactor.netty5.transport.logging.AdvancedBufferFormat;
import reactor.util.annotation.Nullable;

/**
 * Proxy configuration.
 *
 * @author Violeta Georgieva
 */
public class ProxyProvider {

	/**
	 * Creates a builder for {@link ProxyProvider ProxyProvider}.
	 *
	 * @return a new ProxyProvider builder
	 */
	public static TypeSpec builder() {
		return new Build();
	}

	final String username;
	final Function<? super String, ? extends String> password;
	final Supplier<? extends SocketAddress> address;
	final Predicate<SocketAddress> nonProxyHostPredicate;
	final Proxy type;
	final long connectTimeoutMillis;

	protected ProxyProvider(AbstractBuild<?> builder) {
		this.username = builder.username;
		this.password = builder.password;
		this.nonProxyHostPredicate = builder.nonProxyHostPredicate;
		if (Objects.isNull(builder.address)) {
			if (builder.host != null) {
				this.address = () -> AddressUtils.createResolved(builder.host, builder.port);
			}
			else {
				throw new IllegalArgumentException("Neither address nor host is specified");
			}
		}
		else {
			this.address = builder.address;
		}
		this.type = builder.type;
		this.connectTimeoutMillis = builder.connectTimeoutMillis;
	}

	/**
	 * The proxy type.
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
	 * @deprecated as of 1.2.0. Prefer using {@link #getSocketAddress()}.
	 * This method will be removed in 1.3.0.
	 */
	@Deprecated
	public final Supplier<? extends InetSocketAddress> getAddress() {
		return () -> (InetSocketAddress) address.get();
	}

	/**
	 * The supplier for the address to connect to.
	 *
	 * @return The supplier for the address to connect to.
	 */
	public final Supplier<? extends SocketAddress> getSocketAddress() {
		return this.address;
	}

	/**
	 * A predicate {@link Predicate} on {@link SocketAddress} that returns true when the provided address should be
	 * reached directly, bypassing the proxy.
	 *
	 * @return The predicate {@link Predicate} to test the incoming {@link SocketAddress} if it should be reached
	 * directly, bypassing the proxy
	 * @since 0.9.10
	 */
	public final Predicate<SocketAddress> getNonProxyHostsPredicate() {
		return this.nonProxyHostPredicate;
	}

	/**
	 * Return a new eventual {@link ProxyHandler}.
	 *
	 * @return a new eventual {@link ProxyHandler}
	 */
	public ProxyHandler newProxyHandler() {
		SocketAddress proxyAddr = this.address.get();

		final boolean b = Objects.nonNull(username) && Objects.nonNull(password);

		String username = this.username;
		String password = b ? this.password.apply(username) : null;

		final ProxyHandler proxyHandler;
		switch (this.type) {
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
	 * Returns true when the given {@link SocketAddress} should be reached via the configured proxy. When the method
	 * returns false, the client should reach the address directly and bypass the proxy
	 *
	 * @param address the address to test
	 * @return true if of type {@link InetSocketAddress} and hostname candidate to proxy
	 */
	public boolean shouldProxy(SocketAddress address) {
		return address instanceof InetSocketAddress && !nonProxyHostPredicate.test(address);
	}

	/**
	 * Proxy Type.
	 */
	public enum Proxy {
		HTTP, SOCKS4, SOCKS5
	}

	public void addProxyHandler(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		ChannelPipeline pipeline = channel.pipeline();
		pipeline.addFirst(NettyPipeline.ProxyHandler, newProxyHandler());

		if (pipeline.get(NettyPipeline.LoggingHandler) != null) {
			pipeline.addBefore(NettyPipeline.ProxyHandler,
					NettyPipeline.ProxyLoggingHandler,
					LOGGING_HANDLER);
		}
	}

	@Override
	public String toString() {
		return "ProxyProvider {" +
				"address=" + address.get() +
				", nonProxyHosts=" + nonProxyHostPredicate +
				", type=" + type +
				'}';
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ProxyProvider that)) {
			return false;
		}
		return Objects.equals(username, that.username) &&
				Objects.equals(getPasswordValue(), that.getPasswordValue()) &&
				Objects.equals(getSocketAddress().get(), that.getSocketAddress().get()) &&
				getNonProxyHostsValue() == that.getNonProxyHostsValue() &&
				getType() == that.getType() &&
				connectTimeoutMillis == that.connectTimeoutMillis;
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + Objects.hashCode(username);
		result = 31 * result + Objects.hashCode(getPasswordValue());
		result = 31 * result + Objects.hashCode(getSocketAddress().get());
		result = 31 * result + Boolean.hashCode(getNonProxyHostsValue());
		result = 31 * result + Objects.hashCode(getType());
		result = 31 * result + Long.hashCode(connectTimeoutMillis);
		return result;
	}

	protected long getConnectTimeoutMillis() {
		return connectTimeoutMillis;
	}

	@Nullable
	protected String getPasswordValue() {
		if (username == null || password == null) {
			return null;
		}
		return password.apply(username);
	}

	@Nullable
	protected String getUsername() {
		return username;
	}

	private boolean getNonProxyHostsValue() {
		return nonProxyHostPredicate.test(getSocketAddress().get());
	}

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedBufferFormat.HEX_DUMP
					.toLoggingHandler("reactor.netty5.proxy", LogLevel.DEBUG, Charset.defaultCharset());

	static final String SOCKS_PROXY_HOST = "socksProxyHost";
	static final String SOCKS_PROXY_PORT = "socksProxyPort";
	static final String SOCKS_VERSION = "socksProxyVersion";
	static final String SOCKS_VERSION_5 = "5";
	static final String SOCKS_VERSION_4 = "4";
	static final String SOCKS_USERNAME = "java.net.socks.username";
	static final String SOCKS_PASSWORD = "java.net.socks.password";

	@Nullable
	static ProxyProvider createFrom(Properties properties) {
		Objects.requireNonNull(properties, "properties");

		if (properties.containsKey(SOCKS_PROXY_HOST)) {
			return createSocksProxyFrom(properties);
		}

		return null;
	}

	static ProxyProvider createSocksProxyFrom(Properties properties) {
		String hostname = Objects.requireNonNull(properties.getProperty(SOCKS_PROXY_HOST), SOCKS_PROXY_HOST);
		String version = properties.getProperty(SOCKS_VERSION, SOCKS_VERSION_5);
		if (!SOCKS_VERSION_5.equals(version) && !SOCKS_VERSION_4.equals(version)) {
			String message = "only socks versions 4 and 5 supported but got " + version;
			throw new IllegalArgumentException(message);
		}

		Proxy type = SOCKS_VERSION_5.equals(version) ? Proxy.SOCKS5 : Proxy.SOCKS4;
		int port = parsePort(properties.getProperty(SOCKS_PROXY_PORT, "1080"), SOCKS_PROXY_PORT);

		Build proxy = new Build()
				.type(type)
				.host(hostname)
				.port(port);

		if (properties.containsKey(SOCKS_USERNAME)) {
			proxy = proxy.username(properties.getProperty(SOCKS_USERNAME));
		}
		if (properties.containsKey(SOCKS_PASSWORD)) {
			proxy = proxy.password(u -> properties.getProperty(SOCKS_PASSWORD));
		}

		return proxy.build();
	}

	protected static int parsePort(String port, String propertyName) {
		Objects.requireNonNull(port, "port");
		Objects.requireNonNull(propertyName, "propertyName");

		if (port.isEmpty()) {
			String message = "expected system property " + propertyName + " to be a number but got empty string";
			throw new IllegalArgumentException(message);
		}
		if (!port.chars().allMatch(Character::isDigit)) {
			String message = "expected system property " + propertyName + " to be a number but got " + port;
			throw new IllegalArgumentException(message);
		}

		return Integer.parseInt(port);
	}

	protected abstract static class AbstractBuild<T extends AbstractBuild<T>>
			implements TypeSpec, AddressSpec<T>, Builder<T>, Supplier<T> {

		static final Predicate<SocketAddress> ALWAYS_PROXY = a -> false;

		String username;
		Function<? super String, ? extends String> password;
		String host;
		int port;
		Supplier<? extends SocketAddress> address;
		Predicate<SocketAddress> nonProxyHostPredicate = ALWAYS_PROXY;
		Proxy type;
		long connectTimeoutMillis = 10000;

		@Override
		public final T username(String username) {
			this.username = username;
			return get();
		}

		@Override
		public final T password(Function<? super String, ? extends String> password) {
			this.password = password;
			return get();
		}

		@Override
		public final T host(String host) {
			this.host = Objects.requireNonNull(host, "host");
			return get();
		}

		@Override
		public final T address(InetSocketAddress address) {
			return socketAddress(address);
		}

		@Override
		public final T port(int port) {
			this.port = port;
			return get();
		}

		@Override
		public final T socketAddress(SocketAddress address) {
			Objects.requireNonNull(address, "address");
			this.address = () -> {
				if (address instanceof InetSocketAddress) {
					return AddressUtils.replaceWithResolved((InetSocketAddress) address);
				}
				else {
					return address;
				}
			};
			return get();
		}

		@Override
		public final T address(Supplier<? extends InetSocketAddress> addressSupplier) {
			return socketAddress(addressSupplier);
		}

		@Override
		public final T socketAddress(Supplier<? extends SocketAddress> addressSupplier) {
			this.address = Objects.requireNonNull(addressSupplier, "addressSupplier");
			return get();
		}

		@Override
		public final T nonProxyHosts(String nonProxyHostsPattern) {
			return StringUtil.isNullOrEmpty(nonProxyHostsPattern) ?
					nonProxyHostsPredicate(ALWAYS_PROXY) :
					nonProxyHostsPredicate(new RegexShouldProxyPredicate(nonProxyHostsPattern));
		}

		@Override
		public final T nonProxyHostsPredicate(Predicate<SocketAddress> nonProxyHostsPredicate) {
			this.nonProxyHostPredicate = Objects.requireNonNull(nonProxyHostsPredicate, "nonProxyHostsPredicate");
			return get();
		}

		@Override
		public final T type(Proxy type) {
			this.type = Objects.requireNonNull(type, "type");
			return get();
		}

		@Override
		public final T connectTimeoutMillis(long connectTimeoutMillis) {
			this.connectTimeoutMillis = connectTimeoutMillis;
			return get();
		}
	}

	static final class Build extends AbstractBuild<Build> {

		@Override
		public ProxyProvider build() {
			return new ProxyProvider(this);
		}

		@Override
		public Build get() {
			return this;
		}
	}

	protected static final class RegexShouldProxyPredicate implements Predicate<SocketAddress> {

		public static final RegexShouldProxyPredicate DEFAULT_NON_PROXY = RegexShouldProxyPredicate.fromWildcardedPattern("localhost|127.*|[::1]|0.0.0.0|[::0]");

		private final String regex;
		private final Pattern pattern;

		/**
		 * Creates a {@link RegexShouldProxyPredicate} based off the provided pattern with possible wildcards as
		 * described in https://docs.oracle.com/javase/7/docs/api/java/net/doc-files/net-properties.html.
		 *
		 * @param pattern The string wildcarded expression
		 * @return a predicate whether we should direct to proxy
		 */
		public static RegexShouldProxyPredicate fromWildcardedPattern(String pattern) {
			String transformed;
			if (StringUtil.isNullOrEmpty(pattern)) {
				transformed = "$^"; // match nothing
			}
			else {
				String[] parts = pattern.split("\\|");
				for (int i = 0; i < parts.length; i++) {
					parts[i] = transformWildcardComponent(parts[i]);
				}
				transformed = String.join("|", parts);
			}
			return new RegexShouldProxyPredicate(transformed);
		}

		private static String transformWildcardComponent(String in) {
			String[] parts = new String[]{"", "", ""};
			if (in.startsWith("*")) {
				parts[0] = ".*";
				in = in.substring(1);
			}
			if (in.endsWith("*")) {
				parts[2] = ".*";
				in = in.substring(0, in.length() - 1);
			}
			parts[1] = Pattern.quote(in);
			return String.join("", parts);
		}

		private RegexShouldProxyPredicate(String pattern) {
			this.regex = pattern;
			this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		}

		/**
		 * The test returns true when the nonProxyHost {@link Predicate} is true, and we should not go through the
		 * configured proxy.
		 *
		 * @param socketAddress the address we are choosing to connect via proxy or not
		 * @return true we should bypass the proxy
		 */
		@Override
		public boolean test(SocketAddress socketAddress) {
			if (!(socketAddress instanceof InetSocketAddress isa)) {
				return false;
			}
			String hostString = isa.getHostString();
			return hostString != null && pattern.matcher(hostString).matches();
		}

		@Override
		public String toString() {
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
		AddressSpec<?> type(Proxy type);
	}

	public interface AddressSpec<T extends Builder<T>> {

		/**
		 * The proxy host to connect to.
		 *
		 * @param host The proxy host to connect to.
		 * @return {@code this}
		 */
		T host(String host);

		/**
		 * The address to connect to.
		 *
		 * @param address The address to connect to.
		 * @return {@code this}
		 * @deprecated as of 1.2.0. Prefer using {@link #socketAddress(SocketAddress)}.
		 * This method will be removed in 1.3.0.
		 */
		@Deprecated
		T address(InetSocketAddress address);

		/**
		 * The address to connect to.
		 *
		 * @param address The address to connect to.
		 * @return {@code this}
		 */
		default T socketAddress(SocketAddress address) {
			throw new UnsupportedOperationException();
		}

		/**
		 * The supplier for the address to connect to.
		 *
		 * @param addressSupplier The supplier for the address to connect to.
		 * @return {@code this}
		 * @deprecated as of 1.2.0. Prefer using {@link #socketAddress(SocketAddress)}.
		 * This method will be removed in 1.3.0.
		 */
		@Deprecated
		T address(Supplier<? extends InetSocketAddress> addressSupplier);

		/**
		 * The supplier for the address to connect to.
		 *
		 * @param addressSupplier The supplier for the address to connect to.
		 * @return {@code this}
		 */
		default T socketAddress(Supplier<? extends SocketAddress> addressSupplier) {
			throw new UnsupportedOperationException();
		}
	}

	public interface Builder<T extends Builder<T>> {

		/**
		 * The proxy username.
		 *
		 * @param username The proxy username.
		 * @return {@code this}
		 */
		T username(String username);

		/**
		 * A function to supply the proxy's password from the username.
		 *
		 * @param password A function to supply the proxy's password from the username.
		 * @return {@code this}
		 */
		T password(Function<? super String, ? extends String> password);

		/**
		 * The proxy port to connect to.
		 *
		 * @param port The proxy port to connect to.
		 * @return {@code this}
		 */
		T port(int port);

		/**
		 * Regular expression (<code>using java.util.regex</code>) for a configured
		 * list of hosts that should be reached directly, bypassing the proxy.
		 *
		 * @param nonProxyHostsPattern Regular expression (<code>using java.util.regex</code>)
		 *                             for a configured list of hosts that should be reached directly, bypassing the proxy.
		 * @return {@code this}
		 */
		T nonProxyHosts(String nonProxyHostsPattern);

		/**
		 * A standard predicate expression for a configured list of hosts that should be reached directly, bypassing
		 * the proxy.
		 *
		 * @param nonProxyHostsPredicate A Predicate {@link Predicate} on {@link SocketAddress} that returns
		 * {@literal true} if the host should bypass the configured proxy
		 * @return {@code this}
		 * @since 0.9.10
		 */
		T nonProxyHostsPredicate(Predicate<SocketAddress> nonProxyHostsPredicate);

		/**
		 * The proxy connect timeout in millis. Default to 10000 ms.
		 * If this value set as non-positive value, there is no connect timeout.
		 *
		 * @param connectTimeoutMillis The proxy connect timeout in millis.
		 * @return {@code this}
		 */
		T connectTimeoutMillis(long connectTimeoutMillis);

		/**
		 * Builds new ProxyProvider.
		 *
		 * @return builds new ProxyProvider
		 */
		ProxyProvider build();
	}
}
