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
package reactor.netty.http.server;

import java.net.InetSocketAddress;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.netty.handler.codec.http.HttpRequest;
import reactor.netty.tcp.InetSocketAddressUtil;

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
		String forwardedHeader = request.headers().get(FORWARDED_HEADER);
		if (forwardedHeader != null) {
			return parseForwardedInfo(connectionInfo, forwardedHeader);
		}
		return parseXForwardedInfo(connectionInfo, request);
	}

	private ConnectionInfo parseForwardedInfo(ConnectionInfo connectionInfo, String forwardedHeader) {
		String forwarded = forwardedHeader.split(",", 2)[0];
		Matcher hostMatcher = FORWARDED_HOST_PATTERN.matcher(forwarded);
		if (hostMatcher.find()) {
			connectionInfo = connectionInfo.withHostAddress(
					InetSocketAddressUtil.parseAddress(hostMatcher.group(1), connectionInfo.getHostAddress().getPort()));
		}
		Matcher protoMatcher = FORWARDED_PROTO_PATTERN.matcher(forwarded);
		if (protoMatcher.find()) {
			connectionInfo = connectionInfo.withScheme(protoMatcher.group(1).trim());
		}
		Matcher forMatcher = FORWARDED_FOR_PATTERN.matcher(forwarded);
		if (forMatcher.find()) {
			connectionInfo = connectionInfo.withRemoteAddress(
					InetSocketAddressUtil.parseAddress(forMatcher.group(1).trim(), connectionInfo.getRemoteAddress().getPort()));
		}
		return connectionInfo;
	}

	private ConnectionInfo parseXForwardedInfo(ConnectionInfo connectionInfo, HttpRequest request) {
		String ipHeader = request.headers().get(X_FORWARDED_IP_HEADER);
		if (ipHeader != null) {
			InetSocketAddress remoteAddress = InetSocketAddressUtil.parseAddress(ipHeader.split(",", 2)[0], connectionInfo.getRemoteAddress().getPort());
			connectionInfo = connectionInfo.withRemoteAddress(remoteAddress);
		}
		String hostHeader = request.headers().get(X_FORWARDED_HOST_HEADER);
		if (hostHeader != null) {
			String portHeader = request.headers().get(X_FORWARDED_PORT_HEADER);
			InetSocketAddress hostAddress = connectionInfo.getHostAddress();
			if (portHeader != null) {
				int port;
				try {
					port = Integer.parseInt(portHeader.split(",", 2)[0].trim());
				}
				catch (NumberFormatException e) {
					port = hostAddress.getPort();
				}
				hostAddress = InetSocketAddressUtil.createUnresolved(
						hostHeader.split(",", 2)[0].trim(), port);
			}
			else {
				hostAddress = InetSocketAddressUtil.createUnresolved(
						hostHeader.split(",", 2)[0].trim(),
						hostAddress.getPort());
			}
			connectionInfo = connectionInfo.withHostAddress(hostAddress);
		}
		String protoHeader = request.headers().get(X_FORWARDED_PROTO_HEADER);
		if (protoHeader != null) {
			connectionInfo = connectionInfo.withScheme(protoHeader.split(",", 2)[0].trim());
		}
		return connectionInfo;
	}

}
