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
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;

/**
 * Proxy configuration
 *
 * @author Violeta Georgieva
 */
public class ClientProxyOptions {

	/**
	 * Creates a builder for {@link ClientProxyOptions ClientProxyOptions}
	 *
	 * @return a new ClientProxyOptions builder
	 */
	public static ClientProxyOptions.Builder builder() {
		return new ClientProxyOptions.Builder();
	}

	private final String username;
	private final Function<? super String, ? extends String> password;
	private final Supplier<? extends InetSocketAddress> address;
	private final Pattern nonProxyHosts;
	private final Proxy type;

	private ClientProxyOptions(ClientProxyOptions.Builder builder) {
		this.username = builder.username;
		this.password = builder.password;
		if (Objects.isNull(builder.address)) {
			if (Objects.nonNull(builder.host)) {
				this.address = () -> new InetSocketAddress(builder.host, builder.port);
			}
			else {
				this.address = null;
			}
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
	public final Pattern getNonProxyHosts() {
		return this.nonProxyHosts;
	}

	/**
	 * Return a new eventual {@link ProxyHandler}
	 *
	 * @return a new eventual {@link ProxyHandler}
	 */
	public final ProxyHandler newProxyHandler() {
		if (Objects.isNull(this.type) || Objects.isNull(this.address)) {
			return null;
		}
		InetSocketAddress proxyAddr = this.address.get();
		String username = this.username;
		String password = Objects.nonNull(username) && Objects.nonNull(this.password) ?
				this.password.apply(username) : null;

		switch (this.type) {
			case HTTP:
				return Objects.nonNull(username) && Objects.nonNull(password) ?
						new HttpProxyHandler(proxyAddr, username, password) :
						new HttpProxyHandler(proxyAddr);
			case SOCKS4:
				return Objects.nonNull(username) ? new Socks4ProxyHandler(proxyAddr, username) :
						new Socks4ProxyHandler(proxyAddr);
			case SOCKS5:
				return Objects.nonNull(username) && Objects.nonNull(password) ?
						new Socks5ProxyHandler(proxyAddr, username, password) :
						new Socks5ProxyHandler(proxyAddr);
		}
		throw new IllegalArgumentException("Proxy type unsupported : " + this.type);
	}

	/**
	 * Proxy Type
	 */
	public enum Proxy {
		HTTP, SOCKS4, SOCKS5
	}


	public String asSimpleString() {
		return "proxy=" + this.type +
				"(" + (this.address == null ? null : this.address.get()) + ")";
	}

	public String asDetailedString() {
		return "address=" + (this.address == null ? null : this.address.get()) +
				", nonProxyHosts=" + this.nonProxyHosts +
				", type=" + this.type;
	}

	@Override
	public String toString() {
		return "ClientProxyOptions{" + asDetailedString() + "}";
	}

	public static final class Builder {
		private String username;
		private Function<? super String, ? extends String> password;
		private String host;
		private int port;
		private Supplier<? extends InetSocketAddress> address;
		private String nonProxyHosts;
		private Proxy type;

		private Builder() {
		}

		/**
		 * The proxy username.
		 *
		 * @param username The proxy username.
		 * @return {@code this}
		 */
		public final Builder username(String username) {
			this.username = username;
			return this;
		}

		/**
		 * A function to supply the proxy's password from the username.
		 *
		 * @param password A function to supply the proxy's password from the username.
		 * @return {@code this}
		 */
		public final Builder password(Function<? super String, ? extends String> password) {
			this.password = password;
			return this;
		}

		/**
		 * The proxy host to connect to.
		 *
		 * @param host The proxy host to connect to.
		 * @return {@code this}
		 */
		public final Builder host(String host) {
			this.host = Objects.requireNonNull(host, "host");
			return this;
		}

		/**
		 * The proxy port to connect to.
		 *
		 * @param port The proxy port to connect to.
		 * @return {@code this}
		 */
		public final Builder port(int port) {
			this.port = Objects.requireNonNull(port, "port");
			return this;
		}

		/**
		 * The address to connect to.
		 *
		 * @param address The address to connect to.
		 * @return {@code this}
		 */
		public final Builder address(InetSocketAddress address) {
			Objects.requireNonNull(address, "address");
			this.address = address.isUnresolved() ?
					() -> new InetSocketAddress(address.getHostName(),
							address.getPort()) :
					() -> address;
			return this;
		}

		/**
		 * The supplier for the address to connect to.
		 *
		 * @param addressSupplier The supplier for the address to connect to.
		 * @return {@code this}
		 */
		public final Builder address(Supplier<? extends InetSocketAddress> addressSupplier) {
			this.address = Objects.requireNonNull(addressSupplier, "addressSupplier");
			return this;
		}

		/**
		 * Regular expression (<code>using java.util.regex</code>) for a configured
		 * list of hosts that should be reached directly, bypassing the proxy.
		 *
		 * @param nonProxyHostsPattern Regular expression (<code>using java.util.regex</code>)
		 * for a configured list of hosts that should be reached directly, bypassing the proxy.
		 * @return {@code this}
		 */
		public final Builder nonProxyHosts(String nonProxyHostsPattern) {
			this.nonProxyHosts = nonProxyHostsPattern;
			return this;
		}

		/**
		 * The proxy type.
		 *
		 * @param type The proxy type.
		 * @return {@code this}
		 */
		public final Builder type(Proxy type) {
			this.type = Objects.requireNonNull(type, "type");
			return this;
		}

		public ClientProxyOptions build() {
			return new ClientProxyOptions(this);
		}
	}
}
