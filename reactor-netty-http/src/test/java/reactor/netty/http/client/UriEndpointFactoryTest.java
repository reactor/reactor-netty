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
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;
import reactor.netty.transport.AddressUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class UriEndpointFactoryTest {
	private final UriEndpointFactoryBuilder builder = new UriEndpointFactoryBuilder();

	@Test
	void shouldParseUrls_1() {
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

		for (String[] input : inputs) {
			Matcher matcher = UriEndpointFactory.URL_PATTERN
					.matcher(input[0]);
			assertThat(matcher.matches()).isTrue();
			assertThat(input[1]).isEqualTo(matcher.group(1));
			assertThat(input[2]).isEqualTo(matcher.group(2));
			assertThat(input[3]).isEqualTo(matcher.group(3));
			assertThat(input[4]).isEqualTo(matcher.group(4));
		}
	}

	@Test
	void shouldParseUrls_2() throws Exception {
		List<String[]> inputs = Arrays.asList(
				new String[]{"http://localhost:80/path", "http://localhost/path"},
				new String[]{"http://localhost:80/path?key=val", "http://localhost/path?key=val"},
				new String[]{"http://localhost/path", "http://localhost/path"},
				new String[]{"http://localhost/path%20", "http://localhost/path%20"},
				new String[]{"http://localhost/path?key=val", "http://localhost/path?key=val"},
				new String[]{"http://localhost/", "http://localhost/"},
				new String[]{"http://localhost/?key=val", "http://localhost/?key=val"},
				new String[]{"http://localhost", "http://localhost/"},
				new String[]{"http://localhost?key=val", "http://localhost/?key=val"},
				new String[]{"http://localhost:80", "http://localhost/"},
				new String[]{"http://localhost:80?key=val", "http://localhost/?key=val"},
				new String[]{"http://localhost:80/?key=val#fragment", "http://localhost/?key=val"},
				new String[]{"http://localhost:80/?key=%223", "http://localhost/?key=%223"},
				new String[]{"http://localhost/:1234", "http://localhost/:1234"},
				new String[]{"http://localhost:1234", "http://localhost:1234/"},
				new String[]{"http://[::1]:80/path", "http://[::1]/path"},
				new String[]{"http://[::1]:80/path?key=val", "http://[::1]/path?key=val"},
				new String[]{"http://[::1]/path", "http://[::1]/path"},
				new String[]{"http://[::1]/path%20", "http://[::1]/path%20"},
				new String[]{"http://[::1]/path?key=val", "http://[::1]/path?key=val"},
				new String[]{"http://[::1]/", "http://[::1]/"},
				new String[]{"http://[::1]/?key=val", "http://[::1]/?key=val"},
				new String[]{"http://[::1]", "http://[::1]/"},
				new String[]{"http://[::1]?key=val", "http://[::1]/?key=val"},
				new String[]{"http://[::1]:80", "http://[::1]/"},
				new String[]{"http://[::1]:80?key=val", "http://[::1]/?key=val"},
				new String[]{"http://[::1]:80/?key=val#fragment", "http://[::1]/?key=val"},
				new String[]{"http://[::1]:80/?key=%223", "http://[::1]/?key=%223"},
				new String[]{"http://[::1]:1234", "http://[::1]:1234/"}
		);

		for (String[] input : inputs) {
			assertThat(externalForm(this.builder.build(), input[0], false, true)).isEqualTo(input[1]);
		}
	}

	@Test
	void createUriEndpointRelative() {
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
	void createUriEndpointRelativeSslSupport() {
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
	void createUriEndpointRelativeNoLeadingSlash() {
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
	void createUriEndpointRelativeAddress() {
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
	void createUriEndpointIPv6Address() {
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
	void createUriEndpointRelativeAddressSsl() {
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
	void createUriEndpointRelativeWithPort() {
		String test = this.builder
				.host("example.com")
				.port(80)
				.build()
				.createUriEndpoint("/foo", false)
				.toExternalForm();

		assertThat(test).isEqualTo("http://example.com/foo");
	}

	@Test
	void createUriEndpointAbsoluteHttp() throws Exception {
		testCreateUriEndpointAbsoluteHttp(false);
		testCreateUriEndpointAbsoluteHttp(true);
	}

	private void testCreateUriEndpointAbsoluteHttp(boolean useUri) throws Exception {
		String test1 = externalForm(this.builder.build(), "https://localhost/foo", false, useUri);
		String test2 = externalForm(this.builder.build(), "http://localhost/foo", true, useUri);

		String test3 = externalForm(this.builder.sslSupport().build(), "http://localhost/foo", false, useUri);
		String test4 = externalForm(this.builder.sslSupport().build(), "https://localhost/foo", true, useUri);

		assertThat(test1).isEqualTo("https://localhost/foo");
		assertThat(test2).isEqualTo("http://localhost/foo");
		assertThat(test3).isEqualTo("http://localhost/foo");
		assertThat(test4).isEqualTo("https://localhost/foo");
	}

	@Test
	void createUriEndpointWithQuery() throws Exception {
		testCreateUriEndpointWithQuery(false);
		testCreateUriEndpointWithQuery(true);
	}

	private void testCreateUriEndpointWithQuery(boolean useUri) throws Exception {
		assertThat(externalForm(this.builder.build(), "http://localhost/foo?key=val", false, useUri))
				.isEqualTo("http://localhost/foo?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost/?key=val", false, useUri))
				.isEqualTo("http://localhost/?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost?key=val", false, useUri))
				.isEqualTo("http://localhost/?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost:80/foo?key=val", false, useUri))
				.isEqualTo("http://localhost/foo?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost:80/?key=val", false, useUri))
				.isEqualTo("http://localhost/?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost:80?key=val", false, useUri))
				.isEqualTo("http://localhost/?key=val");

		if (useUri) {
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost/foo?key=val", false, useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost/?key=val", false, useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost?key=val", false, useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost:80/foo?key=val", false, useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost:80/?key=val", false, useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost:80?key=val", false, useUri));
		}
		else {
			assertThat(externalForm(this.builder.build(), "localhost/foo?key=val", false, useUri))
					.isEqualTo("http://localhost/foo?key=val");
			assertThat(externalForm(this.builder.build(), "localhost/?key=val", false, useUri))
					.isEqualTo("http://localhost/?key=val");
			assertThat(externalForm(this.builder.build(), "localhost?key=val", false, useUri))
					.isEqualTo("http://localhost/?key=val");
			assertThat(externalForm(this.builder.build(), "localhost:80/foo?key=val", false, useUri))
					.isEqualTo("http://localhost/foo?key=val");
			assertThat(externalForm(this.builder.build(), "localhost:80/?key=val", false, useUri))
					.isEqualTo("http://localhost/?key=val");
			assertThat(externalForm(this.builder.build(), "localhost:80?key=val", false, useUri))
					.isEqualTo("http://localhost/?key=val");
		}
	}

	@Test
	void createUriEndpointAbsoluteWs() throws Exception {
		testCreateUriEndpointAbsoluteWs(false);
		testCreateUriEndpointAbsoluteWs(true);
	}

	private void testCreateUriEndpointAbsoluteWs(boolean useUri) throws Exception {
		String test1 = externalForm(this.builder.build(), "wss://localhost/foo", false, useUri);
		String test2 = externalForm(this.builder.build(), "ws://localhost/foo", true, useUri);

		String test3 = externalForm(this.builder.sslSupport().build(), "ws://localhost/foo", false, useUri);
		String test4 = externalForm(this.builder.sslSupport().build(), "wss://localhost/foo", true, useUri);

		assertThat(test1).isEqualTo("wss://localhost/foo");
		assertThat(test2).isEqualTo("ws://localhost/foo");
		assertThat(test3).isEqualTo("ws://localhost/foo");
		assertThat(test4).isEqualTo("wss://localhost/foo");
	}

	private static String externalForm(UriEndpointFactory factory, String url, boolean isWs, boolean useUri) throws Exception {
		if (useUri) {
			return factory.createUriEndpoint(new URI(url), isWs)
			              .toExternalForm();
		}
		else {
			return factory.createUriEndpoint(url, isWs)
			              .toExternalForm();
		}
	}

	private static final class UriEndpointFactoryBuilder {
		private boolean secure;
		private String host = "localhost";
		private int port = -1;

		public UriEndpointFactory build() {
			return new UriEndpointFactory(
					() -> InetSocketAddress.createUnresolved(host, port != -1 ? port : (secure ? 443 : 80)), secure,
					URI_ADDRESS_MAPPER);
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

		static final BiFunction<String, Integer, InetSocketAddress> URI_ADDRESS_MAPPER =
				AddressUtils::createUnresolved;
	}
}
