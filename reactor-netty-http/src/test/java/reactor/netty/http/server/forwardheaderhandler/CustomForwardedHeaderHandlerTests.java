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

import io.netty.handler.codec.http.HttpHeaderNames;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.netty.http.server.ConnectionInfoTests;
import reactor.netty.transport.AddressUtils;

import java.net.InetSocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests for custom forwarded header handlers.
 */
class CustomForwardedHeaderHandlerTests extends ConnectionInfoTests {

	static final int DEFAULT_HTTP_PORT = 80;
	static final int DEFAULT_HTTPS_PORT = 443;
	static final String  FORWARDED_HEADER         = "Forwarded";
	static final String  X_FORWARDED_IP_HEADER    = "X-Forwarded-For";
	static final String  X_FORWARDED_HOST_HEADER  = "X-Forwarded-Host";
	static final String  X_FORWARDED_PORT_HEADER  = "X-Forwarded-Port";
	static final String  X_FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

	static final Pattern FORWARDED_HOST_PATTERN   = Pattern.compile("host=\"?([^;,\"]+)\"?");
	static final Pattern FORWARDED_PROTO_PATTERN  = Pattern.compile("proto=\"?([^;,\"]+)\"?");
	static final Pattern FORWARDED_FOR_PATTERN    = Pattern.compile("for=\"?([^;,\"]+)\"?");
	static final boolean DEFAULT_FORWARDED_HEADER_VALIDATION = true;

	@Test
	void customForwardedHandlerForMultipleHost() {
		testClientRequest(
				clientRequestHeaders ->
						clientRequestHeaders.add("X-Forwarded-Host", "a.example.com,b.example.com"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("b.example.com");
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("b.example.com");
				},
				(connectionInfo, request) -> {
					String hostHeader = request.headers().get(X_FORWARDED_HOST_HEADER);
					if (hostHeader != null) {
						InetSocketAddress hostAddress = AddressUtils.createUnresolved(
								hostHeader.split(",", 2)[1].trim(),
								connectionInfo.getHostAddress().getPort());
						connectionInfo = connectionInfo.withHostAddress(hostAddress);
					}
					return connectionInfo;
				});
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(strings = {"http", "https", "wss"})
	void customForwardedHandlerForwardedProtoOnly(String protocol) {
		int hostPort = protocol.equals("https") || protocol.equals("wss") ? 443 : 80;
		testClientRequest(
				clientRequestHeaders -> clientRequestHeaders.add("Forwarded", "proto=" + protocol)
						.set(HttpHeaderNames.HOST, "192.168.0.1"),
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
							.containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.disposableServer.port());
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("192.168.0.1");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(hostPort);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo(protocol);
				},
				(connectionInfo, request) -> {
					String forwardedHeader = request.headers().get(FORWARDED_HEADER);
					String forwarded = forwardedHeader.split(",", 2)[0];
					Matcher protoMatcher = FORWARDED_PROTO_PATTERN.matcher(forwarded);
					boolean protoMatched = false;
					if (protoMatcher.find()) {
						connectionInfo = connectionInfo.withScheme(protoMatcher.group(1).trim());
						protoMatched = true;
					}
					Matcher hostMatcher = FORWARDED_HOST_PATTERN.matcher(forwarded);
					if (hostMatcher.find()) {
						String scheme = connectionInfo.getScheme();
						int port = scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("wss") ?
								DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
						connectionInfo = connectionInfo.withHostAddress(
								AddressUtils.parseAddress(hostMatcher.group(1), port, DEFAULT_FORWARDED_HEADER_VALIDATION));
					}
					else if (protoMatched && !connectionInfo.isHostPortParsed()) {
						// There is no Forwarded host / port, and no port was found from the Host header.
						// But we have one Forwarded proto, so determine the default port from it.
						connectionInfo = connectionInfo.withHostPort(getDefaultHostPort(connectionInfo.getScheme()));
					}
					Matcher forMatcher = FORWARDED_FOR_PATTERN.matcher(forwarded);
					if (forMatcher.find()) {
						connectionInfo = connectionInfo.withRemoteAddress(
								AddressUtils.parseAddress(forMatcher.group(1).trim(), connectionInfo.getRemoteAddress().getPort(),
										DEFAULT_FORWARDED_HEADER_VALIDATION));
					}
					return connectionInfo;
				});
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(strings = {"http", "https", "wss"})
	void customForwardedHandlerXForwardedProtoOnly(String protocol) {
		int hostPort = protocol.equals("https") || protocol.equals("wss") ? 443 : 80;
		testClientRequest(
				clientRequestHeaders -> {
					clientRequestHeaders.add("Host", "a.example.com");
					clientRequestHeaders.add("X-Forwarded-Proto", protocol);
				},
				serverRequest -> {
					Assertions.assertThat(serverRequest.hostAddress().getHostString())
							.containsPattern("^0:0:0:0:0:0:0:1(%\\w*)?|127.0.0.1$");
					Assertions.assertThat(serverRequest.hostAddress().getPort()).isEqualTo(this.disposableServer.port());
					Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(hostPort);
					Assertions.assertThat(serverRequest.scheme()).isEqualTo(protocol);
				},
				(connectionInfo, request) -> {
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
										getDefaultHostPort(connectionInfo.getScheme()), DEFAULT_FORWARDED_HEADER_VALIDATION));
					}

					String portHeader = request.headers().get(X_FORWARDED_PORT_HEADER);
					if (portHeader != null && !portHeader.isEmpty()) {
						String portStr = portHeader.split(",", 2)[0].trim();
						if (portStr.chars().allMatch(Character::isDigit)) {
							int port = Integer.parseInt(portStr);
							// If a X-Forwarded-Host was present, update it with the provided port number.
							if (hostHeader != null) {
								connectionInfo = connectionInfo.withHostAddress(
										AddressUtils.createUnresolved(connectionInfo.getHostAddress().getHostString(), port));
							}
							else {
								connectionInfo = connectionInfo.withHostPort(port);
							}
						}
						else if (DEFAULT_FORWARDED_HEADER_VALIDATION) {
							throw new IllegalArgumentException("Failed to parse a port from " + portHeader);
						}
					}
					else if (hostHeader == null && !connectionInfo.isHostPortParsed() && protoHeader != null) {
						// There is no X-Forwarded-Host/X-Forwarded-Port headers, and no port was found from the Host
						// header. But we have one X-Forwarded-Proto header, so determine the default host port
						// from it.
						connectionInfo = connectionInfo.withHostPort(getDefaultHostPort(protoHeader));
					}
					return connectionInfo;
				});
	}

	private int getDefaultHostPort(String scheme) {
		return scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("wss") ?
				DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
	}
}
