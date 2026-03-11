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

import static reactor.netty.transport.DomainSocketAddressUtils.isDomainSocketAddress;
import static reactor.netty.transport.DomainSocketAddressUtils.path;

final class UriEndpoint {
	final String scheme;
	final String host;
	final int port;
	final Supplier<? extends SocketAddress> remoteAddress;
	final String pathAndQuery;

	UriEndpoint(String scheme, String host, int port, Supplier<? extends SocketAddress> remoteAddress, String pathAndQuery) {
		this.host = host;
		this.port = port;
		this.scheme = Objects.requireNonNull(scheme, "scheme");
		this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddressSupplier");
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

	SocketAddress getRemoteAddress() {
		return remoteAddress.get();
	}

	String toExternalForm() {
		StringBuilder sb = new StringBuilder();
		SocketAddress address = remoteAddress.get();
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
		return Objects.hash(getRemoteAddress());
	}
}
