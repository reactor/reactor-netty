/*
 * Copyright (c) 2020-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.netty.handler.codec.http.HttpRequest;
import reactor.netty.transport.AddressUtils;

import static reactor.netty.http.server.ConnectionInfo.getDefaultHostPort;

/**
 * Default implementation for handling the standard {@code Forwarded} header or the
 * {@code X-Forwarded-*} alternative headers. Support for {@code X-Forwarded-Prefix} is
 * enabled separately.
 *
 * @author Andrey Shlykov
 * @since 0.9.12
 */
final class DefaultHttpForwardedHeaderHandler implements BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> {

	static final DefaultHttpForwardedHeaderHandler FORWARDED = new DefaultHttpForwardedHeaderHandler(true, false);
	static final DefaultHttpForwardedHeaderHandler FORWARDED_WITH_PREFIX = new DefaultHttpForwardedHeaderHandler(true, true);
	static final DefaultHttpForwardedHeaderHandler X_FORWARDED = new DefaultHttpForwardedHeaderHandler(false, false);
	static final DefaultHttpForwardedHeaderHandler X_FORWARDED_WITH_PREFIX = new DefaultHttpForwardedHeaderHandler(false, true);

	/**
	 * Handles the standard {@code Forwarded} header when it is present, otherwise falls back to the
	 * {@code X-Forwarded-*} alternative headers, {@code X-Forwarded-Prefix} included.
	 *
	 * @deprecated as of 1.4.0. This instance exists only for {@link HttpServer#forwarded(boolean)}
	 * and will be removed in version 1.5.0.
	 */
	@Deprecated
	static final DefaultHttpForwardedHeaderHandler LEGACY = new DefaultHttpForwardedHeaderHandler(true, true, true);

	static final String  FORWARDED_HEADER         = "Forwarded";
	static final String  X_FORWARDED_IP_HEADER    = "X-Forwarded-For";
	static final String  X_FORWARDED_HOST_HEADER  = "X-Forwarded-Host";
	static final String  X_FORWARDED_PORT_HEADER  = "X-Forwarded-Port";
	static final String  X_FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";
	static final String  X_FORWARDED_PREFIX_HEADER = "X-Forwarded-Prefix";

	static final Pattern FORWARDED_HOST_PATTERN   = Pattern.compile("host=\"?([^;,\"]+)\"?");
	static final Pattern FORWARDED_PROTO_PATTERN  = Pattern.compile("proto=\"?([a-zA-Z][a-zA-Z0-9+.-]*)\"?");
	static final Pattern FORWARDED_FOR_PATTERN    = Pattern.compile("for=\"?([^;,\"]+)\"?");
	static final Pattern X_FORWARDED_PROTO_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*$");

	private static final String[] EMPTY_STRING_ARRAY = {};

	/**
	 * Specifies whether the Http Server applies a strict {@code Forwarded} header validation.
	 * By default, it is enabled and strict validation is used.
	 * @since 1.0.8
	 * @deprecated The system property is used for backwards compatibility and will be removed in version 1.2.0.
	 */
	@Deprecated
	static final String FORWARDED_HEADER_VALIDATION = "reactor.netty.http.server.forwarded.strictValidation";
	static final boolean DEFAULT_FORWARDED_HEADER_VALIDATION =
			Boolean.parseBoolean(System.getProperty(FORWARDED_HEADER_VALIDATION, "true"));

	final boolean useStandardHeader;

	final boolean useForwardedPrefix;

	/**
	 * Whether both alternatives are handled, the standard {@code Forwarded} header taking precedence.
	 * Used only by {@link #LEGACY}, it will be removed in version 1.5.0.
	 */
	final boolean legacy;

	private DefaultHttpForwardedHeaderHandler(boolean useStandardHeader, boolean useForwardedPrefix) {
		this(useStandardHeader, useForwardedPrefix, false);
	}

	private DefaultHttpForwardedHeaderHandler(boolean useStandardHeader, boolean useForwardedPrefix, boolean legacy) {
		this.useStandardHeader = useStandardHeader;
		this.useForwardedPrefix = useForwardedPrefix;
		this.legacy = legacy;
	}

	static DefaultHttpForwardedHeaderHandler instance(boolean useStandardHeader, boolean useForwardedPrefix) {
		if (useStandardHeader) {
			return useForwardedPrefix ? FORWARDED_WITH_PREFIX : FORWARDED;
		}
		return useForwardedPrefix ? X_FORWARDED_WITH_PREFIX : X_FORWARDED;
	}

	@Override
	public ConnectionInfo apply(ConnectionInfo connectionInfo, HttpRequest request) {
		if (legacy) {
			String forwardedHeader = request.headers().get(FORWARDED_HEADER);
			if (forwardedHeader != null) {
				return parseForwardedInfo(connectionInfo, forwardedHeader);
			}
			return parseForwardedPrefixInfo(parseXForwardedInfo(connectionInfo, request), request);
		}
		if (useStandardHeader) {
			String forwardedHeader = request.headers().get(FORWARDED_HEADER);
			if (forwardedHeader != null) {
				connectionInfo = parseForwardedInfo(connectionInfo, forwardedHeader);
			}
		}
		else {
			connectionInfo = parseXForwardedInfo(connectionInfo, request);
		}
		if (useForwardedPrefix) {
			connectionInfo = parseForwardedPrefixInfo(connectionInfo, request);
		}
		return connectionInfo;
	}

	@SuppressWarnings("NullAway")
	private static ConnectionInfo parseForwardedInfo(ConnectionInfo connectionInfo, String forwardedHeader) {
		String forwarded = forwardedHeader.split(",", 2)[0];
		Matcher protoMatcher = FORWARDED_PROTO_PATTERN.matcher(forwarded);
		if (protoMatcher.find()) {
			connectionInfo = connectionInfo.withScheme(protoMatcher.group(1).trim());
		}
		Matcher hostMatcher = FORWARDED_HOST_PATTERN.matcher(forwarded);
		if (hostMatcher.find()) {
			connectionInfo = connectionInfo.withHostAddress(
					AddressUtils.parseAddress(hostMatcher.group(1),
							getDefaultHostPort(connectionInfo.getScheme()), DEFAULT_FORWARDED_HEADER_VALIDATION));
		}
		Matcher forMatcher = FORWARDED_FOR_PATTERN.matcher(forwarded);
		if (forMatcher.find()) {
			connectionInfo = connectionInfo.withRemoteAddress(
					// Deliberately suppress "NullAway"
					// This implementation is invoked always with InetSocketAddress and remote address != null
					AddressUtils.parseAddress(forMatcher.group(1).trim(), connectionInfo.getRemoteAddress().getPort(),
							DEFAULT_FORWARDED_HEADER_VALIDATION));
		}
		return connectionInfo;
	}

	@SuppressWarnings("NullAway")
	private static ConnectionInfo parseXForwardedInfo(ConnectionInfo connectionInfo, HttpRequest request) {
		String ipHeader = request.headers().get(X_FORWARDED_IP_HEADER);
		if (ipHeader != null) {
			connectionInfo = connectionInfo.withRemoteAddress(
					// Deliberately suppress "NullAway"
					// This implementation is invoked always with InetSocketAddress and remote address != null
					AddressUtils.parseAddress(ipHeader.split(",", 2)[0], connectionInfo.getRemoteAddress().getPort()));
		}
		String protoHeader = request.headers().get(X_FORWARDED_PROTO_HEADER);
		if (protoHeader != null) {
			String protoStr = protoHeader.split(",", 2)[0].trim();
			if (X_FORWARDED_PROTO_PATTERN.matcher(protoStr).matches()) {
				connectionInfo = connectionInfo.withScheme(protoStr);
			}
		}
		String hostHeader = request.headers().get(X_FORWARDED_HOST_HEADER);
		if (hostHeader != null) {
			connectionInfo = connectionInfo.withHostAddress(
					AddressUtils.parseAddress(hostHeader.split(",", 2)[0].trim(),
							getDefaultHostPort(connectionInfo.getScheme()), DEFAULT_FORWARDED_HEADER_VALIDATION));
		}

		String portHeader = request.headers().get(X_FORWARDED_PORT_HEADER);
		if (portHeader != null && !portHeader.isEmpty()) {
			String portStr = portHeader.split(",", 2)[0].trim();
			if (portStr.chars().allMatch(Character::isDigit)) {
				int port = Integer.parseInt(portStr);
				connectionInfo = connectionInfo.withHostAddress(
						// Deliberately suppress "NullAway"
						// This implementation is invoked always with InetSocketAddress and local address != null
						AddressUtils.createUnresolved(connectionInfo.getHostAddress().getHostString(), port),
						connectionInfo.getHostName(), port);
			}
			else if (DEFAULT_FORWARDED_HEADER_VALIDATION) {
				throw new IllegalArgumentException("Failed to parse a port from " + portHeader);
			}
		}

		return connectionInfo;
	}

	private static ConnectionInfo parseForwardedPrefixInfo(ConnectionInfo connectionInfo, HttpRequest request) {
		String prefixHeader = request.headers().get(X_FORWARDED_PREFIX_HEADER);
		if (prefixHeader != null) {
			return connectionInfo.withForwardedPrefix(parseForwardedPrefix(prefixHeader));
		}
		return connectionInfo;
	}

	private static String parseForwardedPrefix(String prefixHeader) {
		StringBuilder prefix = new StringBuilder(prefixHeader.length());
		String[] rawPrefixes = tokenizeToStringArray(prefixHeader);
		for (String rawPrefix : rawPrefixes) {
			int endIndex = rawPrefix.length();
			while (endIndex > 1 && rawPrefix.charAt(endIndex - 1) == '/') {
				endIndex--;
			}
			prefix.append((endIndex != rawPrefix.length() ? rawPrefix.substring(0, endIndex) : rawPrefix));
		}
		String parsedPrefix = prefix.toString();
		if (!parsedPrefix.isEmpty() && DEFAULT_FORWARDED_HEADER_VALIDATION && parsedPrefix.charAt(0) != '/') {
			throw new IllegalArgumentException("X-Forwarded-Prefix did not start with a slash (\"/\"): " + prefixHeader);
		}
		return parsedPrefix;
	}

	private static String[] tokenizeToStringArray(String str) {
		StringTokenizer st = new StringTokenizer(str, ",");
		ArrayList<String> tokens = new ArrayList<>();
		while (st.hasMoreTokens()) {
			String token = st.nextToken().trim();
			if (!token.isEmpty()) {
				tokens.add(token);
			}
		}
		return !tokens.isEmpty() ? tokens.toArray(EMPTY_STRING_ARRAY) : EMPTY_STRING_ARRAY;
	}
}
