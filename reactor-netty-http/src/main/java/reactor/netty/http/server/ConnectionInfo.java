/*
 * Copyright (c) 2018-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.BiFunction;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import reactor.util.annotation.Nullable;

import static java.util.Objects.requireNonNull;

/**
 * Resolve information about the current connection, including the
 * host (server) address, the remote (client) address and the scheme.
 *
 * <p>Depending on the chosen factory method, the information
 * can be retrieved directly from the channel or additionally
 * using the {@code "Forwarded"}, or {@code "X-Forwarded-*"}
 * HTTP request headers.
 *
 * @author Brian Clozel
 * @author Andrey Shlykov
 * @since 0.8
 * @see <a href="https://tools.ietf.org/html/rfc7239">rfc7239</a>
 */
public final class ConnectionInfo {
	static final int DEFAULT_HTTP_PORT = 80;
	static final int DEFAULT_HTTPS_PORT = 443;
	static final String DEFAULT_HOST_NAME = "localhost";

	final SocketAddress hostAddress;

	final SocketAddress remoteAddress;

	final String scheme;

	final String hostName;

	final int hostPort;

	final boolean isInetAddress;

	static ConnectionInfo from(HttpRequest request, boolean secured, SocketAddress localAddress, SocketAddress remoteAddress,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler) {
		String hostName = DEFAULT_HOST_NAME;
		int hostPort = -1;
		String scheme = secured ? "https" : "http";

		String header = request.headers().get(HttpHeaderNames.HOST);
		if (header != null) {
			hostName = header;
			if (!header.isEmpty()) {
				int portIndex = header.charAt(0) == '[' ? header.indexOf(':', header.indexOf(']')) : header.indexOf(':');
				if (portIndex != -1) {
					hostName = header.substring(0, portIndex);
					hostPort = Integer.parseInt(header.substring(portIndex + 1));
				}
			}
		}

		if (!(remoteAddress instanceof InetSocketAddress)) {
			return new ConnectionInfo(localAddress, hostName, hostPort, remoteAddress, scheme, false);
		}
		else {
			ConnectionInfo connectionInfo =
					new ConnectionInfo(localAddress, hostName, hostPort, remoteAddress, scheme, true);
			if (forwardedHeaderHandler != null) {
				return forwardedHeaderHandler.apply(connectionInfo, request);
			}
			return connectionInfo;
		}
	}

	ConnectionInfo(SocketAddress hostAddress, SocketAddress remoteAddress, boolean secured) {
		this(hostAddress, DEFAULT_HOST_NAME, -1, remoteAddress, secured ? "https" : "http", remoteAddress instanceof InetSocketAddress);
	}

	ConnectionInfo(SocketAddress hostAddress, String hostName, int hostPort,
			SocketAddress remoteAddress, String scheme, boolean isInetAddress) {
		this.hostAddress = hostAddress;
		this.hostName = hostName;
		this.hostPort = hostPort;
		this.isInetAddress = isInetAddress;
		this.remoteAddress = remoteAddress;
		this.scheme = scheme;
	}

	/**
	 * Return the host address of the connection.
	 * @return the host address
	 */
	@Nullable
	public InetSocketAddress getHostAddress() {
		return isInetAddress ? (InetSocketAddress) hostAddress : null;
	}

	/**
	 * Return the remote address of the connection.
	 * @return the remote address
	 */
	@Nullable
	public InetSocketAddress getRemoteAddress() {
		return isInetAddress ? (InetSocketAddress) remoteAddress : null;
	}

	/**
	 * Return the connection scheme.
	 * @return the connection scheme
	 */
	public String getScheme() {
		return scheme;
	}

	/**
	 * Return a new {@link ConnectionInfo} with the updated host address.
	 * @param hostAddress the host address
	 * @return a new {@link ConnectionInfo}
	 */
	public ConnectionInfo withHostAddress(InetSocketAddress hostAddress) {
		requireNonNull(hostAddress, "hostAddress");
		return new ConnectionInfo(hostAddress, hostAddress.getHostString(), hostAddress.getPort(), this.remoteAddress,
				this.scheme, true);
	}

	/**
	 * Return a new {@link ConnectionInfo} with updated host address, host name, and host port information.
	 * @param hostAddress the updated host address
	 * @param hostName the updated host name
	 * @param hostPort the updated host port
	 * @return a new {@link ConnectionInfo}
	 * @since 1.0.32
	 */
	public ConnectionInfo withHostAddress(InetSocketAddress hostAddress, String hostName, int hostPort) {
		requireNonNull(hostAddress, "hostAddress");
		requireNonNull(hostName, "hostName");
		return new ConnectionInfo(hostAddress, hostName, hostPort, this.remoteAddress, this.scheme, true);
	}

	/**
	 * Return a new {@link ConnectionInfo} with the updated remote address.
	 * @param remoteAddress the remote address
	 * @return a new {@link ConnectionInfo}
	 */
	public ConnectionInfo withRemoteAddress(InetSocketAddress remoteAddress) {
		requireNonNull(remoteAddress, "remoteAddress");
		return new ConnectionInfo(this.hostAddress, this.hostName, this.hostPort, remoteAddress, this.scheme, true);
	}

	/**
	 * Return a new {@link ConnectionInfo} with the updated scheme.
	 * @param scheme the connection scheme
	 * @return a new {@link ConnectionInfo}
	 */
	public ConnectionInfo withScheme(String scheme) {
		requireNonNull(scheme, "scheme");
		return new ConnectionInfo(this.hostAddress, this.hostName, this.hostPort, this.remoteAddress, scheme, this.isInetAddress);
	}

	/**
	 * Returns the connection host name.
	 * @return the connection host name
	 * @since 1.0.32
	 */
	public String getHostName() {
		return hostName;
	}

	/**
	 * Returns the connection host port.
	 * @return the connection host port
	 * @since 1.0.32
	 */
	public int getHostPort() {
		return hostPort != -1 ? hostPort : getDefaultHostPort(scheme);
	}

	/**
	 * Returns the default host port number based on scheme.
	 * @param scheme a connection scheme like "http", "https", or "wss"
	 * @return the default host port number based on scheme
	 * @since 1.0.32
	 */
	public static int getDefaultHostPort(String scheme) {
		return scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("wss") ?
				DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
	}
}
