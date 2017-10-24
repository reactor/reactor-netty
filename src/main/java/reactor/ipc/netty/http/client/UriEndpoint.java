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

package reactor.ipc.netty.http.client;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

import io.netty.util.NetUtil;

final class UriEndpoint {
	private final String scheme;
	private final Supplier<InetSocketAddress> remoteAddress;
	private final String pathAndQuery;

	public UriEndpoint(String scheme, Supplier<InetSocketAddress> remoteAddress,
			String pathAndQuery) {
		this.scheme = scheme;
		this.remoteAddress = remoteAddress;
		this.pathAndQuery = pathAndQuery;
	}

	public boolean isWs() {
		return HttpClient.WS_SCHEME.equals(scheme)
				|| HttpClient.WSS_SCHEME.equals(scheme);
	}

	public boolean isSecure() {
		return isSecureScheme(scheme);
	}

	static boolean isSecureScheme(String scheme) {
		return HttpClient.HTTPS_SCHEME.equals(scheme)
				|| HttpClient.WSS_SCHEME.equals(scheme);
	}

	public String getPathAndQuery() {
		return pathAndQuery;
	}

	public InetSocketAddress getRemoteAddress() {
		return remoteAddress.get();
	}

	public String toExternalForm() {
		StringBuilder sb = new StringBuilder();
		sb.append(scheme);
		sb.append("://");
		InetSocketAddress address = remoteAddress != null ? remoteAddress.get() : null;
		sb.append(address != null
				? toSocketAddressStringWithoutDefaultPort(address, isSecure())
				: "localhost");
		sb.append(pathAndQuery);
		return sb.toString();
	}

	static String toSocketAddressStringWithoutDefaultPort(
			InetSocketAddress address, boolean secure) {
		String addressString = NetUtil.toSocketAddressString(address);
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

	public String getScheme() {
		return scheme;
	}
}
