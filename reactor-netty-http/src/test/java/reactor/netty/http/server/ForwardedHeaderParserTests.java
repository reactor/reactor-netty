/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class ForwardedHeaderParserTests {
	static final InetSocketAddress HOST_ADDRESS = new InetSocketAddress("127.0.0.1", 8080);
	static final String HOST_NAME = "a.example.com";
	static final int HOST_PORT = 9090;
	static final InetSocketAddress REMOTE_ADDRESS = new InetSocketAddress("10.0.0.1", 12345);

	@ParameterizedTest
	@ValueSource(strings = {
			"by=203.0.113.43;host=a.example.com:443;for=192.168.0.1;proto=https",
			"by=\"203.0.113.43\";host=\"a.example.com:443\";for=\"192.168.0.1\";proto=\"https\"",
			"by=203.0.113.43;Host=a.example.com:443;FOR=192.168.0.1;ProTo=https",
			"by = 203.0.113.43;host = a.example.com:443 ;for\t=\t192.168.0.1;proto=https"
	})
	void allParams(String header) {
		ConnectionInfo connectionInfo = parse(header);
		assertThat(connectionInfo.getHostName()).isEqualTo("a.example.com");
		assertThat(connectionInfo.getHostPort()).isEqualTo(443);
		assertThat(connectionInfo.getHostAddress().getHostString()).isEqualTo("203.0.113.43");
		assertThat(connectionInfo.getHostAddress().getPort()).isEqualTo(HOST_ADDRESS.getPort());
		assertThat(connectionInfo.getRemoteAddress().getHostString()).isEqualTo("192.168.0.1");
		assertThat(connectionInfo.getRemoteAddress().getPort()).isEqualTo(REMOTE_ADDRESS.getPort());
		assertThat(connectionInfo.getScheme()).isEqualTo("https");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			" ",
			",",
			";",
			" ; , ",
			"by=;host=;for=;proto=",
			"by=\"\";host=\"\";for=\"\";proto=\"\""
	})
	void nothingToParse(String header) {
		ConnectionInfo connectionInfo = parse(header);
		assertThat(connectionInfo.getHostName()).isEqualTo(HOST_NAME);
		assertThat(connectionInfo.getHostPort()).isEqualTo(HOST_PORT);
		assertThat(connectionInfo.getHostAddress()).isEqualTo(HOST_ADDRESS);
		assertThat(connectionInfo.getRemoteAddress()).isEqualTo(REMOTE_ADDRESS);
		assertThat(connectionInfo.getScheme()).isEqualTo("http");
	}

	static ConnectionInfo parse(String header) {
		ConnectionInfo connectionInfo =
				new ConnectionInfo(HOST_ADDRESS, HOST_NAME, HOST_PORT, REMOTE_ADDRESS, "http", true);
		return ForwardedHeaderParser.parse(connectionInfo, header);
	}
}
