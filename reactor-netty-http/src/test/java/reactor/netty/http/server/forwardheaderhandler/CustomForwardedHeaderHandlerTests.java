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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.netty.BaseHttpTest;
import reactor.netty.transport.AddressUtils;

import java.net.InetSocketAddress;
import java.util.function.Function;

/**
 * Tests for custom forwarded header handlers.
 */
class CustomForwardedHeaderHandlerTests extends BaseHttpTest {

	static final String X_FORWARDED_HOST_HEADER = "X-Forwarded-Host";
	static final String X_FORWARDED_PORT_HEADER = "X-Forwarded-Port";
	static final int DEFAULT_HTTP_PORT = 80;
	static final int DEFAULT_HTTPS_PORT = 443;

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
				},
				Function.identity(),
				Function.identity(),
				false,
				false);
	}

	@ParameterizedTest(name = "{displayName}({arguments})")
	@ValueSource(booleans = {true, false})
	void xForwardedHostPort(boolean useXForwardedHost) {
		testClientRequest(
				clientRequestHeaders -> {
					if (useXForwardedHost) {
						clientRequestHeaders.add("X-Forwarded-Host", "a.example.com");
					}
					clientRequestHeaders.add("X-Forwarded-Port", "8080");
				},
				serverRequest -> {
					if (useXForwardedHost) {
						Assertions.assertThat(serverRequest.hostAddress().getHostString()).isEqualTo("a.example.com");
						Assertions.assertThat(serverRequest.hostName()).isEqualTo("a.example.com");
					}
					Assertions.assertThat(serverRequest.hostPort()).isEqualTo(8080);
				},
				(connectionInfo, request) -> {
					String hostHeader = request.headers().get(X_FORWARDED_HOST_HEADER);
					if (hostHeader != null) {
						connectionInfo = connectionInfo.withHostAddress(
								AddressUtils.parseAddress(hostHeader, getDefaultHostPort(connectionInfo.getScheme()), true));
					}

					String portHeader = request.headers().get(X_FORWARDED_PORT_HEADER);
					if (portHeader != null) {
						int port = Integer.parseInt(portHeader);
						// If an X-Forwarded-Host was present, update it with the provided port number.
						if (hostHeader != null) {
							connectionInfo = connectionInfo.withHostAddress(
									AddressUtils.createUnresolved(connectionInfo.getHostAddress().getHostString(), port));
						}
						else {
							connectionInfo = connectionInfo.withHostPort(port);
						}
					}
					return connectionInfo;
				},
				Function.identity(),
				Function.identity(),
				false,
				false);
	}

	static int getDefaultHostPort(String scheme) {
		return scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("wss") ?
				DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
	}
}
