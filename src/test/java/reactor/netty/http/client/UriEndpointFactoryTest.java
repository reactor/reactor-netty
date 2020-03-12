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

package reactor.netty.http.client;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UriEndpointFactoryTest {
	private final UriEndpointFactoryBuilder builder = new UriEndpointFactoryBuilder();

	@Test
	public void shouldParseUrls() {
		List<String[]> inputs = Arrays.asList(
				new String[]{"http://localhost:80/path", "http", "localhost", "80", "/path"},
				new String[]{"http://localhost:80/path?key=val", "http", "localhost", "80", "/path?key=val"},
				new String[]{"http://localhost/path", "http", "localhost", null, "/path"},
				new String[]{"http://localhost/path?key=val", "http", "localhost", null, "/path?key=val"},
				new String[]{"http://localhost/", "http", "localhost", null, "/"},
				new String[]{"http://localhost/?key=val", "http", "localhost", null, "/?key=val"},
				new String[]{"http://localhost", "http", "localhost", null, null},
				new String[]{"http://localhost?key=val", "http", "localhost", null, "?key=val"},
				new String[]{"http://localhost:80", "http", "localhost", "80", null},
				new String[]{"http://localhost:80?key=val", "http", "localhost", "80", "?key=val"},
				new String[]{"http://localhost/:1234", "http", "localhost", null, "/:1234"},
				new String[]{"http://[::1]:80/path", "http", "[::1]", "80", "/path"},
				new String[]{"http://[::1]:80/path?key=val", "http", "[::1]", "80", "/path?key=val"},
				new String[]{"http://[::1]/path", "http", "[::1]", null, "/path"},
				new String[]{"http://[::1]/path?key=val", "http", "[::1]", null, "/path?key=val"},
				new String[]{"http://[::1]/", "http", "[::1]", null, "/"},
				new String[]{"http://[::1]/?key=val", "http", "[::1]", null, "/?key=val"},
				new String[]{"http://[::1]", "http", "[::1]", null, null},
				new String[]{"http://[::1]?key=val", "http", "[::1]", null, "?key=val"},
				new String[]{"http://[::1]:80", "http", "[::1]", "80", null},
				new String[]{"http://[::1]:80?key=val", "http", "[::1]", "80", "?key=val"},
				new String[]{"localhost:80/path", null, "localhost", "80", "/path"},
				new String[]{"localhost:80/path?key=val", null, "localhost", "80", "/path?key=val"},
				new String[]{"localhost/path", null, "localhost", null, "/path"},
				new String[]{"localhost/path?key=val", null, "localhost", null, "/path?key=val"},
				new String[]{"localhost/", null, "localhost", null, "/"},
				new String[]{"localhost/?key=val", null, "localhost", null, "/?key=val"},
				new String[]{"localhost", null, "localhost", null, null},
				new String[]{"localhost?key=val", null, "localhost", null, "?key=val"},
				new String[]{"localhost:80", null, "localhost", "80", null},
				new String[]{"localhost:80?key=val", null, "localhost", "80", "?key=val"},
				new String[]{"localhost/:1234", null, "localhost", null, "/:1234"}
				);

		for(String[] input : inputs) {
			Matcher matcher = UriEndpointFactory.URL_PATTERN
					.matcher(input[0]);
			assertTrue(matcher.matches());
			assertEquals(input[1], matcher.group(1));
			assertEquals(input[2], matcher.group(2));
			assertEquals(input[3], matcher.group(3));
			assertEquals(input[4], matcher.group(4));
		}
	}

	@Test
	public void createUriEndpointRelative() {
		String test1 = this.builder.build()
				.createUriEndpoint("/foo", false)
				.toExternalForm();
		String test2 = this.builder.build()
				.createUriEndpoint("/foo", true)
				.toExternalForm();

		assertThat(test1).isEqualTo("http://localhost/foo");
		assertThat(test2).isEqualTo("ws://localhost/foo");
	}

	@Test
	public void createUriEndpointRelativeSslSupport() {
		String test1 = this.builder.sslSupport()
				.build()
				.createUriEndpoint("/foo", false)
				.toExternalForm();
		String test2 = this.builder.sslSupport()
				.build()
				.createUriEndpoint("/foo", true)
				.toExternalForm();

		assertThat(test1).isEqualTo("https://localhost/foo");
		assertThat(test2).isEqualTo("wss://localhost/foo");
	}

	@Test
	public void createUriEndpointRelativeNoLeadingSlash() {
		String test1 = this.builder.build()
				.createUriEndpoint("example.com:8080/bar", false)
				.toExternalForm();
		String test2 = this.builder.build()
				.createUriEndpoint("example.com:8080/bar", true)
				.toExternalForm();

		assertThat(test1).isEqualTo("http://example.com:8080/bar");
		assertThat(test2).isEqualTo("ws://example.com:8080/bar");
	}

	@Test
	public void createUriEndpointRelativeAddress() {
		String test1 = this.builder.host("127.0.0.1")
				.port(8080)
				.build()
				.createUriEndpoint("/foo", false)
				.toExternalForm();
		String test2 = this.builder.host("127.0.0.1")
				.port(8080)
				.build()
				.createUriEndpoint("/foo", true)
				.toExternalForm();

		assertThat(test1).isEqualTo("http://127.0.0.1:8080/foo");
		assertThat(test2).isEqualTo("ws://127.0.0.1:8080/foo");
	}

	@Test
	public void createUriEndpointIPv6Address() {
		String test1 = this.builder.host("::1")
				.port(8080)
				.build()
				.createUriEndpoint("/foo", false)
				.toExternalForm();
		String test2 = this.builder.host("::1")
				.port(8080)
				.build()
				.createUriEndpoint("/foo", true)
				.toExternalForm();

		assertThat(test1).isEqualTo("http://[::1]:8080/foo");
		assertThat(test2).isEqualTo("ws://[::1]:8080/foo");
	}

	@Test
	public void createUriEndpointRelativeAddressSsl() {
		String test1 = this.builder.host("example.com")
				.port(8080)
				.sslSupport()
				.build()
				.createUriEndpoint("/foo", false)
				.toExternalForm();
		String test2 = this.builder.host("example.com")
				.port(8080)
				.sslSupport()
				.build()
				.createUriEndpoint("/foo", true)
				.toExternalForm();

		assertThat(test1).isEqualTo("https://example.com:8080/foo");
		assertThat(test2).isEqualTo("wss://example.com:8080/foo");
	}

	@Test
	public void createUriEndpointRelativeWithPort() {
		String test = this.builder
				.host("example.com")
				.port(80)
				.build()
				.createUriEndpoint("/foo", false)
				.toExternalForm();

		assertThat(test).isEqualTo("http://example.com/foo");
	}

	@Test
	public void createUriEndpointAbsoluteHttp() {
		String test1 = this.builder.build()
				.createUriEndpoint("https://localhost/foo", false)
				.toExternalForm();
		String test2 = this.builder.build()
				.createUriEndpoint("http://localhost/foo", true)
				.toExternalForm();

		String test3 = this.builder.sslSupport()
				.build()
				.createUriEndpoint("http://localhost/foo", false)
				.toExternalForm();
		String test4 = this.builder.sslSupport()
				.build()
				.createUriEndpoint("https://localhost/foo", true)
				.toExternalForm();

		assertThat(test1).isEqualTo("https://localhost/foo");
		assertThat(test2).isEqualTo("http://localhost/foo");
		assertThat(test3).isEqualTo("http://localhost/foo");
		assertThat(test4).isEqualTo("https://localhost/foo");
	}

	@Test
	public void createUriEndpointWithQuery() {
		assertThat(this.builder.build()
				.createUriEndpoint("http://localhost/foo?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/foo?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("http://localhost/?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("http://localhost?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("http://localhost:80/foo?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/foo?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("http://localhost:80/?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("http://localhost:80?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("localhost/foo?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/foo?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("localhost/?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("localhost?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("localhost:80/foo?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/foo?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("localhost:80/?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/?key=val");
		assertThat(this.builder.build()
				.createUriEndpoint("localhost:80?key=val", false)
				.toExternalForm()).isEqualTo("http://localhost/?key=val");
	}

	@Test
	public void createUriEndpointAbsoluteWs() {
		String test1 = this.builder.build()
				.createUriEndpoint("wss://localhost/foo", false)
				.toExternalForm();
		String test2 = this.builder.build()
				.createUriEndpoint("ws://localhost/foo", true)
				.toExternalForm();

		String test3 = this.builder.sslSupport()
				.build()
				.createUriEndpoint("ws://localhost/foo", false)
				.toExternalForm();
		String test4 = this.builder.sslSupport()
				.build()
				.createUriEndpoint("wss://localhost/foo", true)
				.toExternalForm();

		assertThat(test1).isEqualTo("wss://localhost/foo");
		assertThat(test2).isEqualTo("ws://localhost/foo");
		assertThat(test3).isEqualTo("ws://localhost/foo");
		assertThat(test4).isEqualTo("wss://localhost/foo");
	}

	private static final class UriEndpointFactoryBuilder {
		private boolean secure;
		private String host = "localhost";
		private int port = -1;

		public UriEndpointFactory build() {
			return new UriEndpointFactory(
					() -> InetSocketAddress.createUnresolved(host, port != -1 ? port : (secure ? 443 : 80)), secure,
					InetSocketAddress::createUnresolved);
		}

		public UriEndpointFactoryBuilder sslSupport() {
			this.secure = true;
			return this;
		}

		public UriEndpointFactoryBuilder host(String host) {
			this.host = host;
			return this;
		}

		public UriEndpointFactoryBuilder port(int port) {
			this.port = port;
			return this;
		}
	}
}
