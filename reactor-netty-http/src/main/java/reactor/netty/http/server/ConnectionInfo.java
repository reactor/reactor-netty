/*
 * Copyright (c) 2011-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;
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

	final InetSocketAddress hostAddress;

	final InetSocketAddress remoteAddress;

	final String scheme;

	@Nullable
	static ConnectionInfo from(Channel channel, HttpRequest request, boolean secured, SocketAddress remoteAddress,
			@Nullable BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> forwardedHeaderHandler) {
		if (!(remoteAddress instanceof InetSocketAddress)) {
			return null;
		}
		else {
			ConnectionInfo connectionInfo = ConnectionInfo.newConnectionInfo(channel, secured, (InetSocketAddress) remoteAddress);
			if (forwardedHeaderHandler != null) {
				return forwardedHeaderHandler.apply(connectionInfo, request);
			}
			return connectionInfo;
		}
	}

	/**
	 * Retrieve the connection information from the current connection directly
	 * @param c the current channel
	 * @param secured is transport secure (SSL)
	 * @return the connection information
	 */
	static ConnectionInfo newConnectionInfo(Channel c, boolean secured, InetSocketAddress remoteAddress) {
		SocketChannel channel = (SocketChannel) c;
		InetSocketAddress hostAddress = channel.localAddress();
		String scheme = secured ? "https" : "http";
		return new ConnectionInfo(hostAddress, remoteAddress, scheme);
	}

	ConnectionInfo(InetSocketAddress hostAddress, InetSocketAddress remoteAddress, String scheme) {
		this.hostAddress = hostAddress;
		this.remoteAddress = remoteAddress;
		this.scheme = scheme;
	}

	/**
	 * Return the host address of the connection.
	 * @return the host address
	 */
	public InetSocketAddress getHostAddress() {
		return hostAddress;
	}

	/**
	 * Return the remote address of the connection.
	 * @return the remote address
	 */
	public InetSocketAddress getRemoteAddress() {
		return remoteAddress;
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
		return new ConnectionInfo(hostAddress, this.remoteAddress, this.scheme);
	}

	/**
	 * Return a new {@link ConnectionInfo} with the updated remote address.
	 * @param remoteAddress the remote address
	 * @return a new {@link ConnectionInfo}
	 */
	public ConnectionInfo withRemoteAddress(InetSocketAddress remoteAddress) {
		requireNonNull(remoteAddress, "remoteAddress");
		return new ConnectionInfo(this.hostAddress, remoteAddress, this.scheme);
	}

	/**
	 * Return a new {@link ConnectionInfo} with the updated scheme.
	 * @param scheme the connection scheme
	 * @return a new {@link ConnectionInfo}
	 */
	public ConnectionInfo withScheme(String scheme) {
		requireNonNull(scheme, "scheme");
		return new ConnectionInfo(this.hostAddress, this.remoteAddress, scheme);
	}

}
