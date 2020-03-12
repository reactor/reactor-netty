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

package reactor.netty.http.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.Supplier;

import io.netty.util.NetUtil;

final class UriEndpoint {
	final String scheme;
	final String host;
	final int port;
	final Supplier<SocketAddress> remoteAddress;
	final String pathAndQuery;

	UriEndpoint(String scheme, String host, int port, Supplier<SocketAddress> remoteAddress, String pathAndQuery) {
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
		sb.append(scheme);
		sb.append("://");
		SocketAddress address = remoteAddress.get();
		sb.append(address != null
				? toSocketAddressStringWithoutDefaultPort(address, isSecure())
				: "localhost");
		sb.append(pathAndQuery);
		return sb.toString();
	}

	static String toSocketAddressStringWithoutDefaultPort(SocketAddress address, boolean secure) {
		if (!(address instanceof InetSocketAddress)) {
			throw new IllegalStateException("Only support InetSocketAddress representation");
		}
		String addressString = NetUtil.toSocketAddressString((InetSocketAddress) address);
		if (secure) {
			if (addressString.endsWith(":443")) {
				addressString = addressString.substring(0, addressString.length() - 4);
			}
		}
		else {
			if (addressString.endsWith(":80")) {
				addressString = addressString.substring(0, addressString.length() - 3);
			}
		}
		return addressString;
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
