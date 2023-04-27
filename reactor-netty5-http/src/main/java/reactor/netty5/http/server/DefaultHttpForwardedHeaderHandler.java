/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.server;

import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.netty5.handler.codec.http.HttpRequest;
import reactor.netty5.transport.AddressUtils;

import static reactor.netty5.http.server.ConnectionInfo.DEFAULT_HTTPS_PORT;
import static reactor.netty5.http.server.ConnectionInfo.DEFAULT_HTTP_PORT;
import static reactor.netty5.http.server.ConnectionInfo.getDefaultHostPort;

/**
 * @author Andrey Shlykov
 * @since 0.9.12
 */
final class DefaultHttpForwardedHeaderHandler implements BiFunction<ConnectionInfo, HttpRequest, ConnectionInfo> {

	static final DefaultHttpForwardedHeaderHandler INSTANCE = new DefaultHttpForwardedHeaderHandler();

	static final String  FORWARDED_HEADER         = "Forwarded";
	static final String  X_FORWARDED_IP_HEADER    = "X-Forwarded-For";
	static final String  X_FORWARDED_HOST_HEADER  = "X-Forwarded-Host";
	static final String  X_FORWARDED_PORT_HEADER  = "X-Forwarded-Port";
	static final String  X_FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

	static final Pattern FORWARDED_HOST_PATTERN   = Pattern.compile("host=\"?([^;,\"]+)\"?");
	static final Pattern FORWARDED_PROTO_PATTERN  = Pattern.compile("proto=\"?([^;,\"]+)\"?");
	static final Pattern FORWARDED_FOR_PATTERN    = Pattern.compile("for=\"?([^;,\"]+)\"?");

	@Override
	public ConnectionInfo apply(ConnectionInfo connectionInfo, HttpRequest request) {
		CharSequence forwardedHeader = request.headers().get(FORWARDED_HEADER);
		if (forwardedHeader != null) {
			return parseForwardedInfo(connectionInfo, forwardedHeader.toString());
		}
		return parseXForwardedInfo(connectionInfo, request);
	}

	private ConnectionInfo parseForwardedInfo(ConnectionInfo connectionInfo, String forwardedHeader) {
		String forwarded = forwardedHeader.split(",", 2)[0];
		Matcher protoMatcher = FORWARDED_PROTO_PATTERN.matcher(forwarded);
		if (protoMatcher.find()) {
			connectionInfo = connectionInfo.withScheme(protoMatcher.group(1).trim());
		}
		Matcher hostMatcher = FORWARDED_HOST_PATTERN.matcher(forwarded);
		if (hostMatcher.find()) {
			String scheme = connectionInfo.getScheme();
			int port = scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("wss") ?
					DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
			connectionInfo = connectionInfo.withHostAddress(AddressUtils.parseAddress(hostMatcher.group(1), port, true));
		}
		Matcher forMatcher = FORWARDED_FOR_PATTERN.matcher(forwarded);
		if (forMatcher.find()) {
			connectionInfo = connectionInfo.withRemoteAddress(
					AddressUtils.parseAddress(forMatcher.group(1).trim(), connectionInfo.getRemoteAddress().getPort(), true));
		}
		return connectionInfo;
	}

	private ConnectionInfo parseXForwardedInfo(ConnectionInfo connectionInfo, HttpRequest request) {
		CharSequence ipHeader = request.headers().get(X_FORWARDED_IP_HEADER);
		if (ipHeader != null) {
			connectionInfo = connectionInfo.withRemoteAddress(
					AddressUtils.parseAddress(ipHeader.toString().split(",", 2)[0], connectionInfo.getRemoteAddress().getPort()));
		}
		CharSequence protoHeader = request.headers().get(X_FORWARDED_PROTO_HEADER);
		if (protoHeader != null) {
			connectionInfo = connectionInfo.withScheme(protoHeader.toString().split(",", 2)[0].trim());
		}
		CharSequence hostHeader = request.headers().get(X_FORWARDED_HOST_HEADER);
		if (hostHeader != null) {
			connectionInfo = connectionInfo.withHostAddress(
					AddressUtils.parseAddress(hostHeader.toString().split(",", 2)[0].trim(),
							getDefaultHostPort(connectionInfo.getScheme()), true));
		}

		CharSequence portHeader = request.headers().get(X_FORWARDED_PORT_HEADER);
		if (portHeader != null && !portHeader.toString().isEmpty()) {
			String portStr = portHeader.toString().split(",", 2)[0].trim();
			if (portStr.chars().allMatch(Character::isDigit)) {
				int port = Integer.parseInt(portStr);
				connectionInfo = new ConnectionInfo(
						AddressUtils.createUnresolved(connectionInfo.getHostAddress().getHostString(), port),
						connectionInfo.getHostName(), port, connectionInfo.getRemoteAddress(), connectionInfo.getScheme());
			}
			else {
				throw new IllegalArgumentException("Failed to parse a port from " + portHeader);
			}
		}
		return connectionInfo;
	}
}
