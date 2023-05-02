/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server.forwardheaderhandler;

import io.netty.handler.codec.http.HttpRequest;
import reactor.netty.http.server.ConnectionInfo;
import reactor.netty.transport.AddressUtils;

import static reactor.netty.http.server.ConnectionInfo.getDefaultHostPort;

/**
 * Custom X-Forwarded-XX headers handler which implement the same logic of the Default handler.
 * This class is meant to verify that people can implement at least the same logic provided
 * by the DefaultHttpForwardedHeaderHandler class.
 * <p>
 * <b> WARNING: This class is not for general purpose, it is not an API and can be changed at any time.</b>
 */
public final class CustomXForwardedHeadersHandler {

	public static final CustomXForwardedHeadersHandler INSTANCE = new CustomXForwardedHeadersHandler();

	static final String X_FORWARDED_IP_HEADER = "X-Forwarded-For";
	static final String X_FORWARDED_HOST_HEADER = "X-Forwarded-Host";
	static final String X_FORWARDED_PORT_HEADER = "X-Forwarded-Port";
	static final String X_FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

	private CustomXForwardedHeadersHandler() {
	}

	public ConnectionInfo apply(ConnectionInfo connectionInfo, HttpRequest request) {
		return parseXForwardedInfo(connectionInfo, request);
	}

	private ConnectionInfo parseXForwardedInfo(ConnectionInfo connectionInfo, HttpRequest request) {
		String ipHeader = request.headers().get(X_FORWARDED_IP_HEADER);
		if (ipHeader != null) {
			connectionInfo = connectionInfo.withRemoteAddress(
					AddressUtils.parseAddress(ipHeader.split(",", 2)[0], connectionInfo.getRemoteAddress().getPort()));
		}
		String protoHeader = request.headers().get(X_FORWARDED_PROTO_HEADER);
		if (protoHeader != null) {
			connectionInfo = connectionInfo.withScheme(protoHeader.split(",", 2)[0].trim());
		}
		String hostHeader = request.headers().get(X_FORWARDED_HOST_HEADER);
		if (hostHeader != null) {
			connectionInfo = connectionInfo.withHostAddress(
					AddressUtils.parseAddress(hostHeader.split(",", 2)[0].trim(),
							getDefaultHostPort(connectionInfo.getScheme()), true));
		}

		String portHeader = request.headers().get(X_FORWARDED_PORT_HEADER);
		if (portHeader != null && !portHeader.isEmpty()) {
			String portStr = portHeader.split(",", 2)[0].trim();
			if (portStr.chars().allMatch(Character::isDigit)) {
				int port = Integer.parseInt(portStr);
				connectionInfo = connectionInfo.withHostAddress(
						AddressUtils.createUnresolved(connectionInfo.getHostAddress().getHostString(), port),
						connectionInfo.getHostName(), port);
			}
			else {
				throw new IllegalArgumentException("Failed to parse a port from " + portHeader);
			}
		}
		return connectionInfo;
	}
}
