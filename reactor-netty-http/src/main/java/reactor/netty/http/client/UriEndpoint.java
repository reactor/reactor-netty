/*
 * Copyright (c) 2017-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.netty.util.NetUtil;
import reactor.netty.transport.AddressUtils;
import static reactor.netty.http.client.HttpClient.DEFAULT_PORT;
import static reactor.netty.http.client.HttpClient.DEFAULT_SECURE_PORT;

final class UriEndpoint {
	private static final Pattern SCHEME_PATTERN = Pattern.compile("^\\w+://.*$");
	private static final String ROOT_PATH = "/";
	private static final String COLON_DOUBLE_SLASH = "://";

	private final SocketAddress remoteAddress;
	private final URI uri;
	private final String scheme;
	private final boolean secure;
	private final String authority;
	private final String rawUri;

	private UriEndpoint(URI uri) {
		this(uri, null);
	}

	private UriEndpoint(URI uri, SocketAddress remoteAddress) {
		this.uri = Objects.requireNonNull(uri, "uri");
		if (uri.isOpaque()) {
			throw new IllegalArgumentException("URI is opaque: " + uri);
		}
		if (!uri.isAbsolute()) {
			throw new IllegalArgumentException("URI is not absolute: " + uri);
		}
		this.scheme = uri.getScheme().toLowerCase();
		this.secure = isSecureScheme(scheme);
		this.authority = authority(uri);
		this.rawUri = rawUri(uri);
		if (remoteAddress == null) {
			int port = uri.getPort() != -1 ? uri.getPort() : (secure ? DEFAULT_SECURE_PORT : DEFAULT_PORT);
			this.remoteAddress = AddressUtils.createUnresolved(uri.getHost(), port);
		}
		else {
			this.remoteAddress = remoteAddress;
		}
	}

	static UriEndpoint create(URI uri, String baseUrl, String uriStr, Supplier<? extends SocketAddress> remoteAddress, boolean secure, boolean ws) {
		if (uri != null) {
			// fast path
			return new UriEndpoint(uri);
		}
		if (uriStr == null) {
			uriStr = ROOT_PATH;
		}
		if (baseUrl != null && uriStr.startsWith(ROOT_PATH)) {
			// support prepending a baseUrl
			if (baseUrl.endsWith(ROOT_PATH)) {
				// trim off trailing slash to avoid a double slash when appending uriStr
				baseUrl = baseUrl.substring(0, baseUrl.length() - ROOT_PATH.length());
			}
			uriStr = baseUrl + uriStr;
		}
		if (uriStr.startsWith(ROOT_PATH)) {
			// support "/path" base by prepending scheme and host
			SocketAddress socketAddress = remoteAddress.get();
			uriStr = resolveScheme(secure, ws) + COLON_DOUBLE_SLASH + socketAddressToAuthority(socketAddress, secure) + uriStr;
			return new UriEndpoint(URI.create(uriStr), socketAddress);
		}
		if (!SCHEME_PATTERN.matcher(uriStr).matches()) {
			// support "example.com/path" case by prepending scheme
			uriStr = resolveScheme(secure, ws) + COLON_DOUBLE_SLASH + uriStr;
		}
		return new UriEndpoint(URI.create(uriStr));
	}

	private static String socketAddressToAuthority(SocketAddress socketAddress, boolean secure) {
		if (!(socketAddress instanceof InetSocketAddress)) {
			return "localhost";
		}
		InetSocketAddress inetSocketAddress = (InetSocketAddress) socketAddress;
		String host;
		if (inetSocketAddress.isUnresolved()) {
			host = NetUtil.getHostname(inetSocketAddress);
		}
		else {
			InetAddress inetAddress = inetSocketAddress.getAddress();
			host = NetUtil.toAddressString(inetAddress);
			if (inetAddress instanceof Inet6Address) {
				host = '[' + host + ']';
			}
		}
		int port = inetSocketAddress.getPort();
		if ((!secure && port != DEFAULT_PORT) || (secure && port != DEFAULT_SECURE_PORT)) {
			return host + ':' + port;
		}
		return host;
	}

	private static String resolveScheme(boolean secure, boolean ws) {
		if (ws) {
			return secure ? HttpClient.WSS_SCHEME : HttpClient.WS_SCHEME;
		}
		else {
			return secure ? HttpClient.HTTPS_SCHEME : HttpClient.HTTP_SCHEME;
		}
	}

	private static boolean isSecureScheme(String scheme) {
		return HttpClient.HTTPS_SCHEME.equals(scheme) || HttpClient.WSS_SCHEME.equals(scheme);
	}

	private static String rawUri(URI uri) {
		String rawPath = uri.getRawPath();
		if (rawPath == null || rawPath.isEmpty()) {
			rawPath = ROOT_PATH;
		}
		String rawQuery = uri.getRawQuery();
		if (rawQuery == null) {
			return rawPath;
		}
		return rawPath + '?' + rawQuery;
	}

	private static String authority(URI uri) {
		String host = uri.getHost();
		int port = uri.getPort();
		if (port == -1 || port == DEFAULT_PORT || port == DEFAULT_SECURE_PORT) {
			return host;
		}
		return host + ':' + port;
	}

	UriEndpoint redirect(String to) {
		try {
			URI redirectUri = new URI(to);
			if (redirectUri.isAbsolute()) {
				// absolute path: treat as a brand new uri
				return new UriEndpoint(redirectUri);
			}
			// relative path: reuse the remote address
			return new UriEndpoint(uri.resolve(redirectUri), remoteAddress);
		}
		catch (URISyntaxException e) {
			throw new IllegalArgumentException("Cannot resolve location header", e);
		}
	}

	boolean isSecure() {
		return secure;
	}

	String getRawUri() {
		return rawUri;
	}

	String getPath() {
		String path = uri.getPath();
		if (path == null || path.isEmpty()) {
			return ROOT_PATH;
		}
		return path;
	}

	String getHostHeader() {
		return authority;
	}

	SocketAddress getRemoteAddress() {
		return remoteAddress;
	}

	String toExternalForm() {
		return scheme + COLON_DOUBLE_SLASH + authority + rawUri;
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
		return remoteAddress.equals(that.remoteAddress);
	}

	@Override
	public int hashCode() {
		return remoteAddress.hashCode();
	}
}
