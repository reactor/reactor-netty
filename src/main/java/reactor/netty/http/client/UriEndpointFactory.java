/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reactor.util.annotation.Nullable;

final class UriEndpointFactory {
	final Supplier<SocketAddress> connectAddress;
	final boolean defaultSecure;
	final BiFunction<String, Integer, InetSocketAddress> inetSocketAddressFunction;

	static final Pattern URL_PATTERN = Pattern.compile(
			"(?:(\\w+)://)?((?:\\[.+?])|(?<!\\[)(?:[^/?]+?))(?::(\\d{2,5}))?([/?].*)?");

	UriEndpointFactory(Supplier<SocketAddress> connectAddress, boolean defaultSecure,
			BiFunction<String, Integer, InetSocketAddress> inetSocketAddressFunction) {
		this.connectAddress = connectAddress;
		this.defaultSecure = defaultSecure;
		this.inetSocketAddressFunction = inetSocketAddressFunction;
	}

	UriEndpoint createUriEndpoint(String url, boolean isWs) {
		return createUriEndpoint(url, isWs, connectAddress);

	}

	UriEndpoint createUriEndpoint(String url, boolean isWs, Supplier<SocketAddress> connectAddress) {
		if (url.startsWith("/")) {
			return new UriEndpoint(resolveScheme(isWs), "localhost", 80, connectAddress, url);
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
						host, port, () -> inetSocketAddressFunction.apply(host, port),
						pathAndQuery);
			}
			else {
				throw new IllegalArgumentException("Unable to parse url '" + url + "'");
			}
		}
	}

	String cleanPathAndQuery(@Nullable String pathAndQuery) {
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
		} else if (pathAndQuery.charAt(0) == '?') {
			pathAndQuery = "/" + pathAndQuery;
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
			return defaultSecure ? HttpClient.WSS_SCHEME : HttpClient.WS_SCHEME;
		}
		else {
			return defaultSecure ? HttpClient.HTTPS_SCHEME : HttpClient.HTTP_SCHEME;
		}
	}
}
