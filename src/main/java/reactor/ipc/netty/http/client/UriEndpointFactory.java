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
import java.net.SocketAddress;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UriEndpointFactory {
	private final Supplier<SocketAddress> connectAddress;
	private final boolean secure;
	private final BiFunction<String, Integer, InetSocketAddress> inetSocketAddressFunction;
	static final Pattern URL_PATTERN = Pattern.compile(
			"(?:(\\w+)://)?((?:\\[.+?])|(?<!\\[)(?:[^/]+?))(?::(\\d{2,5}))?(/.*)?");

	UriEndpointFactory(Supplier<SocketAddress> connectAddress, boolean secure,
			BiFunction<String, Integer, InetSocketAddress> inetSocketAddressFunction) {
		this.connectAddress = connectAddress;
		this.secure = secure;
		this.inetSocketAddressFunction = inetSocketAddressFunction;
	}

	public UriEndpoint createUriEndpoint(String url, boolean isWs) {
		if (url.startsWith("/")) {
			return new UriEndpoint(resolveScheme(isWs), getAddress(), url);
		}
		else {
			Matcher matcher = URL_PATTERN.matcher(url);
			if (matcher.matches()) {
				// scheme is optional in pattern. use default if it's not specified
				String scheme = matcher.group(1) != null ? matcher.group(1).toLowerCase()
						: resolveScheme(isWs);
				String host = cleanHostString(matcher.group(2));

				String portString = matcher.group(3);
				int port = portString != null ? Integer.parseInt(portString)
						: (UriEndpoint.isSecureScheme(scheme) ? 443 : 80);
				String pathAndQuery = cleanPathAndQuery(matcher.group(4));
				return new UriEndpoint(scheme,
						() -> inetSocketAddressFunction.apply(host, port), pathAndQuery);
			}
			else {
				throw new IllegalArgumentException("Unable to parse url '" + url + "'");
			}
		}
	}

	String cleanPathAndQuery(String pathAndQuery) {
		if (pathAndQuery == null) {
			pathAndQuery = "/";
		}
		else {
			// remove possible fragment since it shouldn't be sent to the server
			int pos = pathAndQuery.indexOf("#");
			if (pos > -1) {
				pathAndQuery = pathAndQuery.substring(0, pos);
			}
		}
		if (pathAndQuery.length() == 0) {
			pathAndQuery = "/";
		}
		return pathAndQuery;
	}

	String cleanHostString(String host) {
		// remove brackets around IPv6 address in host name
		if (host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
			host = host.substring(1, host.length() - 1);
		}
		return host;
	}

	String resolveScheme(boolean isWs) {
		if (isWs) {
			return secure ? HttpClient.WSS_SCHEME : HttpClient.WS_SCHEME;
		}
		else {
			return secure ? HttpClient.HTTPS_SCHEME : HttpClient.HTTP_SCHEME;
		}
	}

	Supplier<InetSocketAddress> getAddress() {
		return () -> (InetSocketAddress) connectAddress.get();
	}
}
