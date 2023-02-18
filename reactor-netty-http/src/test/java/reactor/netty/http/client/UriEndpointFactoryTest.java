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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import reactor.netty.transport.AddressUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class UriEndpointFactoryTest {
	private final UriEndpointFactoryBuilder builder = new UriEndpointFactoryBuilder();

	@Test
	void shouldParseUrls_1() throws Exception {
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
				new String[]{"http://[::1]:1234", "http://[::1]:1234/"},
				new String[]{"localhost:80/path", "http://localhost/path"},
				new String[]{"localhost:80/path?key=val", "http://localhost/path?key=val"},
				new String[]{"localhost/path", "http://localhost/path"},
				new String[]{"localhost/path%20", "http://localhost/path%20"},
				new String[]{"localhost/path?key=val", "http://localhost/path?key=val"},
				new String[]{"localhost/", "http://localhost/"},
				new String[]{"localhost/?key=val", "http://localhost/?key=val"},
				new String[]{"localhost", "http://localhost/"},
				new String[]{"localhost?key=val", "http://localhost/?key=val"},
				new String[]{"localhost:80", "http://localhost/"},
				new String[]{"localhost:80?key=val", "http://localhost/?key=val"},
				new String[]{"localhost:80/?key=val#fragment", "http://localhost/?key=val"},
				new String[]{"localhost:80/?key=%223", "http://localhost/?key=%223"},
				new String[]{"localhost/:1234", "http://localhost/:1234"},
				new String[]{"localhost:1234", "http://localhost:1234/"},
				new String[]{"[::1]:80/path", "http://[::1]/path"},
				new String[]{"[::1]:80/path?key=val", "http://[::1]/path?key=val"},
				new String[]{"[::1]/path", "http://[::1]/path"},
				new String[]{"[::1]/path%20", "http://[::1]/path%20"},
				new String[]{"[::1]/path?key=val", "http://[::1]/path?key=val"},
				new String[]{"[::1]/", "http://[::1]/"},
				new String[]{"[::1]/?key=val", "http://[::1]/?key=val"},
				new String[]{"[::1]", "http://[::1]/"},
				new String[]{"[::1]?key=val", "http://[::1]/?key=val"},
				new String[]{"[::1]:80", "http://[::1]/"},
				new String[]{"[::1]:80?key=val", "http://[::1]/?key=val"},
				new String[]{"[::1]:80/?key=val#fragment", "http://[::1]/?key=val"},
				new String[]{"[::1]:80/?key=%223", "http://[::1]/?key=%223"},
				new String[]{"[::1]:1234", "http://[::1]:1234/"},
				new String[]{"/?key=val#fragment", "https://example.com/?key=val"}
		);

		for (String[] input : inputs) {
			assertThat(externalForm(this.builder.baseUrl("https://example.com/").build(), input[0], false)).isEqualTo(input[1]);
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
			assertThat(externalForm(this.builder.baseUrl("https://example.com/").build(), input[0], true)).isEqualTo(input[1]);
		}
	}

	@Test
	void createUriEndpointOpaque() {
		assertThatExceptionOfType(IllegalArgumentException.class)
		.isThrownBy(() -> externalForm(this.builder.build(), "mailto:admin@example.com", true));
	}

	@Test
	void createUriEndpointFqdn_1() {
		UriEndpoint endpoint = this.builder.host("example.com").sslSupport().build()
				.createUriEndpoint("/path%20example?key=value#fragment");

		assertThat(endpoint.toExternalForm()).isEqualTo("https://example.com/path%20example?key=value");
		assertThat(endpoint.getHostHeader()).isEqualTo("example.com");
		assertThat(endpoint.getRawUri()).isEqualTo("/path%20example?key=value");
		assertThat(endpoint.getPath()).isEqualTo("/path example");
		assertThat(endpoint.getRemoteAddress()).isEqualTo(AddressUtils.createUnresolved("example.com", 443));
		assertThat(endpoint.isSecure()).isTrue();
	}

	@Test
	void createUriEndpointFqdn_2() {
		UriEndpoint endpoint = this.builder.host("example.com").port(8080).sslSupport().build()
				.createUriEndpoint("/path%20example?key=value#fragment");

		assertThat(endpoint.toExternalForm()).isEqualTo("https://example.com:8080/path%20example?key=value");
		assertThat(endpoint.getHostHeader()).isEqualTo("example.com:8080");
		assertThat(endpoint.getRawUri()).isEqualTo("/path%20example?key=value");
		assertThat(endpoint.getPath()).isEqualTo("/path example");
		assertThat(endpoint.getRemoteAddress()).isEqualTo(AddressUtils.createUnresolved("example.com", 8080));
		assertThat(endpoint.isSecure()).isTrue();
	}

	@Test
	void createUriEndpointFqdn_3() {
		UriEndpoint endpoint = this.builder.build()
				.createUriEndpoint("https://example.com:8080/path%20example?key=value#fragment");

		assertThat(endpoint.toExternalForm()).isEqualTo("https://example.com:8080/path%20example?key=value");
		assertThat(endpoint.getHostHeader()).isEqualTo("example.com:8080");
		assertThat(endpoint.getRawUri()).isEqualTo("/path%20example?key=value");
		assertThat(endpoint.getPath()).isEqualTo("/path example");
		assertThat(endpoint.getRemoteAddress()).isEqualTo(AddressUtils.createUnresolved("example.com", 8080));
		assertThat(endpoint.isSecure()).isTrue();
	}

	@Test
	void createUriEndpointIpv4() {
		UriEndpoint endpoint = this.builder.host("127.0.0.1").port(8080).build()
				.createUriEndpoint("/path%20example?key=value#fragment");

		assertThat(endpoint.toExternalForm()).isEqualTo("http://127.0.0.1:8080/path%20example?key=value");
		assertThat(endpoint.getHostHeader()).isEqualTo("127.0.0.1:8080");
		assertThat(endpoint.getRawUri()).isEqualTo("/path%20example?key=value");
		assertThat(endpoint.getPath()).isEqualTo("/path example");
		assertThat(endpoint.getRemoteAddress()).isEqualTo(AddressUtils.createUnresolved("127.0.0.1", 8080));
		assertThat(endpoint.isSecure()).isFalse();
	}

	@Test
	void createUriEndpointIpv6() throws UnknownHostException {
		UriEndpoint endpoint = this.builder.host("::1").port(8080).build()
				.createUriEndpoint("/path%20example?key=value#fragment");

		assertThat(endpoint.toExternalForm()).isEqualTo("http://[::1]:8080/path%20example?key=value");
		assertThat(endpoint.getHostHeader()).isEqualTo("[::1]:8080");
		assertThat(endpoint.getRawUri()).isEqualTo("/path%20example?key=value");
		assertThat(endpoint.getPath()).isEqualTo("/path example");
		assertThat(endpoint.getRemoteAddress()).isEqualTo(AddressUtils.createUnresolved("::1", 8080));
		assertThat(endpoint.isSecure()).isFalse();
	}

	@Test
	void createUriEndpointRedirectAbsolute() throws UnknownHostException {
		UriEndpoint endpoint = this.builder.build()
				.createUriEndpoint("https://source.example.com/foo/bar");

		endpoint = endpoint.redirect("https://example.com/path%20example?key=value#fragment");

		assertThat(endpoint.toExternalForm()).isEqualTo("https://example.com/path%20example?key=value");
		assertThat(endpoint.getHostHeader()).isEqualTo("example.com");
		assertThat(endpoint.getRawUri()).isEqualTo("/path%20example?key=value");
		assertThat(endpoint.getPath()).isEqualTo("/path example");
		assertThat(endpoint.getRemoteAddress()).isEqualTo(AddressUtils.createUnresolved("example.com", 443));
		assertThat(endpoint.isSecure()).isTrue();
	}

	@Test
	void createUriEndpointRedirectRelative() throws UnknownHostException {
		UriEndpoint endpoint = this.builder.build()
				.createUriEndpoint("https://example.com/");

		endpoint = endpoint.redirect("/path%20example?key=value#fragment");

		assertThat(endpoint.toExternalForm()).isEqualTo("https://example.com/path%20example?key=value");
		assertThat(endpoint.getHostHeader()).isEqualTo("example.com");
		assertThat(endpoint.getRawUri()).isEqualTo("/path%20example?key=value");
		assertThat(endpoint.getPath()).isEqualTo("/path example");
		assertThat(endpoint.getRemoteAddress()).isEqualTo(AddressUtils.createUnresolved("example.com", 443));
		assertThat(endpoint.isSecure()).isTrue();
	}

	@Test
	void createUriEndpointRedirectRelativeSubpath() throws UnknownHostException {
		UriEndpoint endpoint = this.builder.build()
				.createUriEndpoint("https://example.com/subpath/");

		endpoint = endpoint.redirect("path%20example?key=value#fragment");

		assertThat(endpoint.toExternalForm()).isEqualTo("https://example.com/subpath/path%20example?key=value");
		assertThat(endpoint.getHostHeader()).isEqualTo("example.com");
		assertThat(endpoint.getRawUri()).isEqualTo("/subpath/path%20example?key=value");
		assertThat(endpoint.getPath()).isEqualTo("/subpath/path example");
		assertThat(endpoint.getRemoteAddress()).isEqualTo(AddressUtils.createUnresolved("example.com", 443));
		assertThat(endpoint.isSecure()).isTrue();
	}

	@Test
	void createUriEndpointRedirectInvalid() throws UnknownHostException {
		UriEndpoint endpoint = this.builder.build()
				.createUriEndpoint("https://example.com/");

		assertThatExceptionOfType(IllegalArgumentException.class)
		.isThrownBy(() -> endpoint.redirect("path${MACRO_IS_INVALID}/test@@@@"));
	}

	@Test
	void createUriEndpointRelative() {
		String test1 = this.builder.build()
				.createUriEndpoint("/foo")
				.toExternalForm();
		String test2 = this.builder.webSocket(true).build()
				.createUriEndpoint("/foo")
				.toExternalForm();

		assertThat(test1).isEqualTo("http://localhost/foo");
		assertThat(test2).isEqualTo("ws://localhost/foo");
	}

	@Test
	void createUriEndpointRelativeSslSupport() {
		String test1 = this.builder.sslSupport()
				.build()
				.createUriEndpoint("/foo")
				.toExternalForm();
		String test2 = this.builder.sslSupport()
				.webSocket(true)
				.build()
				.createUriEndpoint("/foo")
				.toExternalForm();

		assertThat(test1).isEqualTo("https://localhost/foo");
		assertThat(test2).isEqualTo("wss://localhost/foo");
	}

	@Test
	void createUriEndpointRelativeNoLeadingSlash() {
		String test1 = this.builder.sslSupport().build()
				.createUriEndpoint("example.com:8443/bar")
				.toExternalForm();
		String test2 = this.builder.webSocket(true).build()
				.createUriEndpoint("example.com:8443/bar")
				.toExternalForm();

		assertThat(test1).isEqualTo("https://example.com:8443/bar");
		assertThat(test2).isEqualTo("wss://example.com:8443/bar");
	}

	@Test
	void createUriEndpointRelativeAddress() {
		String test1 = this.builder.host("127.0.0.1")
				.port(8080)
				.build()
				.createUriEndpoint("/foo")
				.toExternalForm();
		String test2 = this.builder.host("127.0.0.1")
				.port(8080)
				.webSocket(true)
				.build()
				.createUriEndpoint("/foo")
				.toExternalForm();

		assertThat(test1).isEqualTo("http://127.0.0.1:8080/foo");
		assertThat(test2).isEqualTo("ws://127.0.0.1:8080/foo");
	}

	@Test
	void createUriEndpointIPv6Address() {
		String test1 = this.builder.host("::1")
				.port(8080)
				.build()
				.createUriEndpoint("/foo")
				.toExternalForm();
		String test2 = this.builder.host("::1")
				.port(8080)
				.webSocket(true)
				.build()
				.createUriEndpoint("/foo")
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
				.createUriEndpoint("/foo")
				.toExternalForm();
		String test2 = this.builder.host("example.com")
				.port(8080)
				.sslSupport()
				.webSocket(true)
				.build()
				.createUriEndpoint("/foo")
				.toExternalForm();

		assertThat(test1).isEqualTo("https://example.com:8080/foo");
		assertThat(test2).isEqualTo("wss://example.com:8080/foo");
	}

	@Test
	void createUriEndpointRelativeWithPort() {
		String test = this.builder
				.host("example.com")
				.port(443)
				.sslSupport()
				.build()
				.createUriEndpoint("/foo")
				.toExternalForm();

		assertThat(test).isEqualTo("https://example.com/foo");
	}

	@Test
	void createUriEndpointAbsoluteHttp() throws Exception {
		testCreateUriEndpointAbsoluteHttp(false);
		testCreateUriEndpointAbsoluteHttp(true);
	}

	private void testCreateUriEndpointAbsoluteHttp(boolean useUri) throws Exception {
		String test1 = externalForm(this.builder.build(), "https://localhost/foo", useUri);
		String test2 = externalForm(this.builder.webSocket(true).build(), "http://localhost/foo", useUri);

		String test3 = externalForm(this.builder.sslSupport().build(), "http://localhost/foo", useUri);
		String test4 = externalForm(this.builder.sslSupport().webSocket(true).build(), "https://localhost/foo", useUri);

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
		assertThat(externalForm(this.builder.build(), "http://localhost/foo?key=val", useUri))
				.isEqualTo("http://localhost/foo?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost/?key=val", useUri))
				.isEqualTo("http://localhost/?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost?key=val", useUri))
				.isEqualTo("http://localhost/?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost:80/foo?key=val", useUri))
				.isEqualTo("http://localhost/foo?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost:80/?key=val", useUri))
				.isEqualTo("http://localhost/?key=val");
		assertThat(externalForm(this.builder.build(), "http://localhost:80?key=val", useUri))
				.isEqualTo("http://localhost/?key=val");

		if (useUri) {
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost/foo?key=val", useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost/?key=val", useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost?key=val", useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost:80/foo?key=val", useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost:80/?key=val", useUri));
			assertThatExceptionOfType(IllegalArgumentException.class)
					.isThrownBy(() -> externalForm(this.builder.build(), "localhost:80?key=val", useUri));
		}
		else {
			assertThat(externalForm(this.builder.build(), "localhost/foo?key=val", useUri))
					.isEqualTo("http://localhost/foo?key=val");
			assertThat(externalForm(this.builder.build(), "localhost/?key=val", useUri))
					.isEqualTo("http://localhost/?key=val");
			assertThat(externalForm(this.builder.build(), "localhost?key=val", useUri))
					.isEqualTo("http://localhost/?key=val");
			assertThat(externalForm(this.builder.build(), "localhost:80/foo?key=val", useUri))
					.isEqualTo("http://localhost/foo?key=val");
			assertThat(externalForm(this.builder.build(), "localhost:80/?key=val", useUri))
					.isEqualTo("http://localhost/?key=val");
			assertThat(externalForm(this.builder.build(), "localhost:80?key=val", useUri))
					.isEqualTo("http://localhost/?key=val");
		}
	}

	@Test
	void createUriEndpointAbsoluteWs() throws Exception {
		testCreateUriEndpointAbsoluteWs(false);
		testCreateUriEndpointAbsoluteWs(true);
	}

	private void testCreateUriEndpointAbsoluteWs(boolean useUri) throws Exception {
		String test1 = externalForm(this.builder.build(), "wss://localhost/foo", useUri);
		String test2 = externalForm(this.builder.webSocket(true).build(), "ws://localhost/foo", useUri);

		String test3 = externalForm(this.builder.sslSupport().build(), "ws://localhost/foo", useUri);
		String test4 = externalForm(this.builder.sslSupport().webSocket(true).build(), "wss://localhost/foo", useUri);

		assertThat(test1).isEqualTo("wss://localhost/foo");
		assertThat(test2).isEqualTo("ws://localhost/foo");
		assertThat(test3).isEqualTo("ws://localhost/foo");
		assertThat(test4).isEqualTo("wss://localhost/foo");
	}

	private static String externalForm(UriEndpointFactoryBuilder.UriEndpointFactory factory, String url, boolean useUri) throws Exception {
		if (useUri) {
			return factory.createUriEndpoint(new URI(url))
			              .toExternalForm();
		}
		else {
			return factory.createUriEndpoint(url)
			              .toExternalForm();
		}
	}

	private static final class UriEndpointFactoryBuilder {
		private boolean secure;
		private String host = "localhost";
		private int port = -1;
		private String baseUrl;
		private boolean isWs;

		public UriEndpointFactory build() {
			return new UriEndpointFactory();
		}

		private  final class UriEndpointFactory {
			InetSocketAddress remoteAddress() {
				return AddressUtils.createUnresolved(host, port != -1 ? port : (secure ? 443 : 80));
			}

			UriEndpoint createUriEndpoint(String uri) {
				return UriEndpoint.create(null, baseUrl, uri, this::remoteAddress, secure, isWs);
			}

			UriEndpoint createUriEndpoint(URI uri) {
				return UriEndpoint.create(uri, baseUrl, null, this::remoteAddress, secure, isWs);
			}
		}

		public UriEndpointFactoryBuilder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		public UriEndpointFactoryBuilder webSocket(boolean isWs) {
			this.isWs = isWs;
			return this;
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
