/*
 * Copyright (c) 2017-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.Supplier;

import io.netty.util.NetUtil;
import org.jspecify.annotations.Nullable;

import static reactor.netty.transport.DomainSocketAddressUtils.isDomainSocketAddress;
import static reactor.netty.transport.DomainSocketAddressUtils.path;

final class UriEndpoint {
	final String scheme;
	final String host;
	final int port;
	// Exactly one of remoteAddress or remoteAddressSupplier is non-null.
	// remoteAddress is used for absolute URLs where the address is derived from the URL itself.
	// remoteAddressSupplier is used for relative URLs where the address is provided by the user and may change between invocations.
	@SuppressWarnings("NullAway")
	final @Nullable SocketAddress remoteAddress;
	@SuppressWarnings("NullAway")
	final @Nullable Supplier<? extends SocketAddress> remoteAddressSupplier;
	final String pathAndQuery;

	@Nullable String externalForm;

	UriEndpoint(String scheme, String host, int port, SocketAddress remoteAddress, String pathAndQuery) {
		this.host = host;
		this.port = port;
		this.scheme = Objects.requireNonNull(scheme, "scheme");
		this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
		this.remoteAddressSupplier = null;
		this.pathAndQuery = Objects.requireNonNull(pathAndQuery, "pathAndQuery");
	}

	UriEndpoint(String scheme, String host, int port, Supplier<? extends SocketAddress> remoteAddressSupplier, String pathAndQuery) {
		this.host = host;
		this.port = port;
		this.scheme = Objects.requireNonNull(scheme, "scheme");
		this.remoteAddress = null;
		this.remoteAddressSupplier = Objects.requireNonNull(remoteAddressSupplier, "remoteAddressSupplier");
		this.pathAndQuery = Objects.requireNonNull(pathAndQuery, "pathAndQuery");
	}

	boolean isWs() {
		return HttpClient.WS_SCHEME.equals(scheme) || HttpClient.WSS_SCHEME.equals(scheme);
	}

	boolean isSecure() {
		return isSecureScheme(scheme);
	}

	static boolean isSecureScheme(String scheme) {
		return HttpClient.HTTPS_SCHEME.equals(scheme) || HttpClient.WSS_SCHEME.equals(scheme);
	}

	String getPathAndQuery() {
		return pathAndQuery;
	}

	@SuppressWarnings("NullAway")
	SocketAddress getRemoteAddress() {
		return remoteAddress != null ? remoteAddress : remoteAddressSupplier.get();
	}

	@SuppressWarnings("NullAway")
	String toExternalForm() {
		if (remoteAddressSupplier != null) {
			return computeExternalForm(remoteAddressSupplier.get());
		}
		String result = externalForm;
		if (result == null) {
			result = computeExternalForm(remoteAddress);
			externalForm = result;
		}
		return result;
	}

	private String computeExternalForm(SocketAddress address) {
		StringBuilder sb = new StringBuilder();
		if (isDomainSocketAddress(address)) {
			sb.append(path(address));
		}
		else {
			sb.append(scheme);
			sb.append("://");
			sb.append(address != null
							  ? toSocketAddressStringWithoutDefaultPort(address, isSecure())
							  : "localhost");
			sb.append(pathAndQuery);
		}
		return sb.toString();
	}

	static String toSocketAddressStringWithoutDefaultPort(SocketAddress address, boolean secure) {
		if (!(address instanceof InetSocketAddress)) {
			throw new IllegalStateException("Only support InetSocketAddress representation");
		}
		InetSocketAddress inetAddr = (InetSocketAddress) address;
		int port = inetAddr.getPort();
		if ((secure && port == 443) || (!secure && port == 80)) {
			return inetSocketAddressHostString(inetAddr);
		}
		return NetUtil.toSocketAddressString(inetAddr);
	}

	static String inetSocketAddressHostString(InetSocketAddress addr) {
		if (addr.isUnresolved()) {
			String hostname = NetUtil.getHostname(addr);
			if (NetUtil.isValidIpV6Address(hostname)) {
				if (hostname.charAt(0) == '[') {
					return hostname;
				}
				return '[' + hostname + ']';
			}
			return hostname;
		}
		InetAddress inetAddress = addr.getAddress();
		String host = NetUtil.toAddressString(inetAddress);
		if (inetAddress instanceof Inet6Address) {
			return '[' + host + ']';
		}
		return host;
	}

	@Override
	public String toString() {
		return toExternalForm();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		UriEndpoint that = (UriEndpoint) o;
		return getRemoteAddress().equals(that.getRemoteAddress());
	}

	@Override
	public int hashCode() {
		return getRemoteAddress().hashCode();
	}
}
