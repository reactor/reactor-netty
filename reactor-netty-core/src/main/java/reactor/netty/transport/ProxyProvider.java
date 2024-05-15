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
package reactor.netty.transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.util.internal.StringUtil;
import reactor.netty.NettyPipeline;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.annotation.Nullable;

/**
 * Proxy configuration.
 *
 * @author Violeta Georgieva
 */
public final class ProxyProvider {

	/**
	 * Creates a builder for {@link ProxyProvider ProxyProvider}.
	 *
	 * @return a new ProxyProvider builder
	 */
	public static ProxyProvider.TypeSpec builder() {
		return new ProxyProvider.Build();
	}

	final String username;
	final Function<? super String, ? extends String> password;
	final Supplier<? extends SocketAddress> address;
	final Predicate<SocketAddress> nonProxyHostPredicate;
	final Supplier<? extends HttpHeaders> httpHeaders;
	final Proxy type;
	final long connectTimeoutMillis;

	ProxyProvider(ProxyProvider.Build builder) {
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
		this.httpHeaders = builder.httpHeaders;
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
	public final ProxyHandler newProxyHandler() {
		SocketAddress proxyAddr = this.address.get();

		final boolean b = Objects.nonNull(username) && Objects.nonNull(password);

		String username = this.username;
		String password = b ? this.password.apply(username) : null;

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

	@SuppressWarnings("deprecation")
	public void addProxyHandler(Channel channel) {
		Objects.requireNonNull(channel, "channel");
		ChannelPipeline pipeline = channel.pipeline();
		pipeline.addFirst(NettyPipeline.ProxyHandler, newProxyHandler());
		// For SOCKS proxy, the netty SOCKS handlers may register listeners in channel promises, so we need to register a
		// special handler which ensures that any VoidPromise will be converted to "unvoided" promises (for support of listeners).
		// Note: an example of a VoidPromise which does not support listeners is the MonoSendMany.SendManyInner promise.
		if (this.type == Proxy.SOCKS4 || type == Proxy.SOCKS5) {
			pipeline.addAfter(NettyPipeline.ProxyHandler, NettyPipeline.UnvoidHandler, UnvoidHandler.INSTANCE);
		}

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
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		ProxyProvider that = (ProxyProvider) o;
		return Objects.equals(username, that.username) &&
				Objects.equals(getPasswordValue(), that.getPasswordValue()) &&
				Objects.equals(getSocketAddress().get(), that.getSocketAddress().get()) &&
				getNonProxyHostsValue() == that.getNonProxyHostsValue() &&
				Objects.equals(httpHeaders.get(), that.httpHeaders.get()) &&
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
		result = 31 * result + Objects.hashCode(httpHeaders.get());
		result = 31 * result + Objects.hashCode(getType());
		result = 31 * result + Long.hashCode(connectTimeoutMillis);
		return result;
	}

	private boolean getNonProxyHostsValue() {
		return nonProxyHostPredicate.test(getSocketAddress().get());
	}

	@Nullable
	private String getPasswordValue() {
		if (username == null || password == null) {
			return null;
		}
		return password.apply(username);
	}

	static final LoggingHandler LOGGING_HANDLER =
			AdvancedByteBufFormat.HEX_DUMP
					.toLoggingHandler("reactor.netty.proxy", LogLevel.DEBUG, Charset.defaultCharset());

	static final String HTTP_PROXY_HOST = "http.proxyHost";
	static final String HTTP_PROXY_PORT = "http.proxyPort";
	static final String HTTP_PROXY_USER = "http.proxyUser";
	static final String HTTP_PROXY_PASSWORD = "http.proxyPassword";
	static final String HTTPS_PROXY_HOST = "https.proxyHost";
	static final String HTTPS_PROXY_PORT = "https.proxyPort";
	static final String HTTPS_PROXY_USER = "https.proxyUser";
	static final String HTTPS_PROXY_PASSWORD = "https.proxyPassword";
	static final String HTTP_NON_PROXY_HOSTS = "http.nonProxyHosts";
	static final String DEFAULT_NON_PROXY_HOSTS = "localhost|127.*|[::1]";

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

		if (properties.containsKey(HTTP_PROXY_HOST) || properties.containsKey(HTTPS_PROXY_HOST)) {
			return createHttpProxyFrom(properties);
		}
		if (properties.containsKey(SOCKS_PROXY_HOST)) {
			return createSocksProxyFrom(properties);
		}

		return null;
	}

	/*
		assumes properties has either http.proxyHost or https.proxyHost
	 */
	static ProxyProvider createHttpProxyFrom(Properties properties) {
		String hostProperty;
		String portProperty;
		String userProperty;
		String passwordProperty;
		String defaultPort;
		if (properties.containsKey(HTTPS_PROXY_HOST)) {
			hostProperty = HTTPS_PROXY_HOST;
			portProperty = HTTPS_PROXY_PORT;
			userProperty = HTTPS_PROXY_USER;
			passwordProperty = HTTPS_PROXY_PASSWORD;
			defaultPort = "443";
		}
		else {
			hostProperty = HTTP_PROXY_HOST;
			portProperty = HTTP_PROXY_PORT;
			userProperty = HTTP_PROXY_USER;
			passwordProperty = HTTP_PROXY_PASSWORD;
			defaultPort = "80";
		}

		String hostname = Objects.requireNonNull(properties.getProperty(hostProperty), hostProperty);
		int port = parsePort(properties.getProperty(portProperty, defaultPort), portProperty);

		String nonProxyHosts = properties.getProperty(HTTP_NON_PROXY_HOSTS, DEFAULT_NON_PROXY_HOSTS);
		RegexShouldProxyPredicate transformedNonProxyHosts = RegexShouldProxyPredicate.fromWildcardedPattern(nonProxyHosts);

		ProxyProvider.Builder proxy = ProxyProvider.builder()
				.type(ProxyProvider.Proxy.HTTP)
				.host(hostname)
				.port(port)
				.nonProxyHostsPredicate(transformedNonProxyHosts);

		if (properties.containsKey(userProperty)) {
			proxy = proxy.username(properties.getProperty(userProperty));

			if (properties.containsKey(passwordProperty)) {
				proxy = proxy.password(u -> properties.getProperty(passwordProperty));
			}
			else {
				throw new NullPointerException("Proxy username is set via '" + userProperty + "', but '" + passwordProperty + "' is not set.");
			}
		}

		return proxy.build();
	}

	static ProxyProvider createSocksProxyFrom(Properties properties) {
		String hostname = Objects.requireNonNull(properties.getProperty(SOCKS_PROXY_HOST), SOCKS_PROXY_HOST);
		String version = properties.getProperty(SOCKS_VERSION, SOCKS_VERSION_5);
		if (!SOCKS_VERSION_5.equals(version) && !SOCKS_VERSION_4.equals(version)) {
			String message = "only socks versions 4 and 5 supported but got " + version;
			throw new IllegalArgumentException(message);
		}

		ProxyProvider.Proxy type = SOCKS_VERSION_5.equals(version) ? Proxy.SOCKS5 : Proxy.SOCKS4;
		int port = parsePort(properties.getProperty(SOCKS_PROXY_PORT, "1080"), SOCKS_PROXY_PORT);

		ProxyProvider.Builder proxy = ProxyProvider.builder()
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

	static int parsePort(String port, String propertyName) {
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

	static final class Build implements TypeSpec, AddressSpec, Builder {

		@SuppressWarnings("UnnecessaryLambda")
		static final Supplier<? extends HttpHeaders> NO_HTTP_HEADERS = () -> null;

		@SuppressWarnings("UnnecessaryLambda")
		static final Predicate<SocketAddress> ALWAYS_PROXY = a -> false;

		String username;
		Function<? super String, ? extends String> password;
		String host;
		int port;
		Supplier<? extends SocketAddress> address;
		Predicate<SocketAddress> nonProxyHostPredicate = ALWAYS_PROXY;
		Supplier<? extends HttpHeaders> httpHeaders = NO_HTTP_HEADERS;
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
		public final Builder address(InetSocketAddress address) {
			return socketAddress(address);
		}

		@Override
		public final Builder port(int port) {
			this.port = port;
			return this;
		}

		@Override
		public final Builder socketAddress(SocketAddress address) {
			Objects.requireNonNull(address, "address");
			this.address = () -> {
				if (address instanceof InetSocketAddress) {
					return AddressUtils.replaceWithResolved((InetSocketAddress) address);
				}
				else {
					return address;
				}
			};
			return this;
		}

		@Override
		public final Builder address(Supplier<? extends InetSocketAddress> addressSupplier) {
			return socketAddress(addressSupplier);
		}

		@Override
		public final Builder socketAddress(Supplier<? extends SocketAddress> addressSupplier) {
			this.address = Objects.requireNonNull(addressSupplier, "addressSupplier");
			return this;
		}

		@Override
		public final Builder nonProxyHosts(String nonProxyHostsPattern) {
			return StringUtil.isNullOrEmpty(nonProxyHostsPattern) ?
					nonProxyHostsPredicate(ALWAYS_PROXY) :
					nonProxyHostsPredicate(new RegexShouldProxyPredicate(nonProxyHostsPattern));
		}

		@Override
		public final Builder nonProxyHostsPredicate(Predicate<SocketAddress> nonProxyHostsPredicate) {
			this.nonProxyHostPredicate = Objects.requireNonNull(nonProxyHostsPredicate, "nonProxyHostsPredicate");
			return this;
		}

		@Override
		public Builder httpHeaders(Consumer<HttpHeaders> headers) {
			if (headers != null) {
				this.httpHeaders = () -> new DefaultHttpHeaders() {
					{
						headers.accept(this);
					}
				};
			}
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
			if (!(socketAddress instanceof InetSocketAddress)) {
				return false;
			}
			InetSocketAddress isa = (InetSocketAddress) socketAddress;
			String hostString = isa.getHostString();
			return hostString != null && pattern.matcher(hostString).matches();
		}

		@Override
		public String toString() {
			return regex;
		}
	}

	@ChannelHandler.Sharable
	static final class UnvoidHandler extends ChannelOutboundHandlerAdapter {

		static final UnvoidHandler INSTANCE = new UnvoidHandler();

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			ctx.write(msg, promise.unvoid());
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
		 * @deprecated as of 1.2.0. Prefer using {@link #socketAddress(SocketAddress)}.
		 * This method will be removed in 1.3.0.
		 */
		@Deprecated
		Builder address(InetSocketAddress address);

		/**
		 * The address to connect to.
		 *
		 * @param address The address to connect to.
		 * @return {@code this}
		 */
		default Builder socketAddress(SocketAddress address) {
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
		Builder address(Supplier<? extends InetSocketAddress> addressSupplier);

		/**
		 * The supplier for the address to connect to.
		 *
		 * @param addressSupplier The supplier for the address to connect to.
		 * @return {@code this}
		 */
		default Builder socketAddress(Supplier<? extends SocketAddress> addressSupplier) {
			throw new UnsupportedOperationException();
		}
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
		 *                             for a configured list of hosts that should be reached directly, bypassing the proxy.
		 * @return {@code this}
		 */
		Builder nonProxyHosts(String nonProxyHostsPattern);

		/**
		 * A standard predicate expression for a configured list of hosts that should be reached directly, bypassing
		 * the proxy.
		 *
		 * @param nonProxyHostsPredicate A Predicate {@link Predicate} on {@link SocketAddress} that returns
		 * {@literal true} if the host should bypass the configured proxy
		 * @return {@code this}
		 * @since 0.9.10
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
		 * If this value set as non-positive value, there is no connect timeout.
		 *
		 * @param connectTimeoutMillis The proxy connect timeout in millis.
		 * @return {@code this}
		 */
		Builder connectTimeoutMillis(long connectTimeoutMillis);

		/**
		 * Builds new ProxyProvider.
		 *
		 * @return builds new ProxyProvider
		 */
		ProxyProvider build();
	}
}
