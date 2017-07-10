/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.client;

import org.junit.Before;
import org.junit.Test;
import reactor.ipc.netty.options.ClientProxyOptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;

public class HttpClientOptionsTest {
	private HttpClientOptions.Builder builder;
	private Consumer<? super ClientProxyOptions.Builder> proxyOptions;

	@Before
	public void setUp() {
		this.builder = HttpClientOptions.builder();
		this.proxyOptions = ops -> ops.type(ClientProxyOptions.Proxy.SOCKS4)
		                              .host("http://proxy")
		                              .port(456);
	}

	@Test
	public void asSimpleString() {
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to no base address");

		//proxy
		this.builder.proxy(proxyOptions);
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to no base address through SOCKS4 proxy");

		//address
		this.builder.host("http://google.com").port(123);
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to http://google.com:123 through SOCKS4 proxy");

		//gzip
		this.builder.compression(true);
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to http://google.com:123 through SOCKS4 proxy with gzip");
	}

	@Test
	public void asDetailedString() {
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=null, proxy=null")
				.endsWith(", acceptGzip=false");

		//proxy
		this.builder.proxy(proxyOptions);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=null, proxy=SOCKS4(http://proxy:456)")
				.endsWith(", acceptGzip=false");

		//address
		this.builder.host("http://google.com").port(123);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith(", acceptGzip=false");

		//gzip
		this.builder.compression(true);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith(", acceptGzip=true");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		this.builder.host("http://google.com")
		            .port(123)
		            .proxy(proxyOptions)
		            .compression(true);
		assertThat(this.builder.build().toString())
				.startsWith("HttpClientOptions{connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith(", acceptGzip=true}");
	}

	@Test
	public void formatSchemeAndHostRelative() throws Exception {
		String test1 = this.builder.build()
		                           .formatSchemeAndHost("/foo", false);
		String test2 = this.builder.build()
		                           .formatSchemeAndHost("/foo", true);

		assertThat(test1).isEqualTo("http://localhost/foo");
		assertThat(test2).isEqualTo("ws://localhost/foo");
	}

	@Test
	public void formatSchemeAndHostRelativeSslSupport() throws Exception {
		String test1 = this.builder.sslSupport()
		                           .build()
		                           .formatSchemeAndHost("/foo", false);
		String test2 = this.builder.sslSupport()
		                           .build()
		                           .formatSchemeAndHost("/foo", true);

		assertThat(test1).isEqualTo("https://localhost/foo");
		assertThat(test2).isEqualTo("wss://localhost/foo");
	}

	@Test
	public void formatSchemeAndHostRelativeNoLeadingSlash() throws Exception {
		String test1 = this.builder.build()
		                           .formatSchemeAndHost("foo:8080/bar", false);
		String test2 = this.builder.build()
		                           .formatSchemeAndHost("foo:8080/bar", true);

		assertThat(test1).isEqualTo("http://foo:8080/bar");
		assertThat(test2).isEqualTo("ws://foo:8080/bar");
	}

	@Test
	public void formatSchemeAndHostRelativeAddress() throws Exception {
		String test1 = this.builder.host("127.0.0.1")
		                           .port(8080)
		                           .build()
		                           .formatSchemeAndHost("/foo", false);
		String test2 = this.builder.host("127.0.0.1")
		                           .port(8080)
		                           .build()
		                           .formatSchemeAndHost("/foo", true);

		assertThat(test1).isEqualTo("http://127.0.0.1:8080/foo");
		assertThat(test2).isEqualTo("ws://127.0.0.1:8080/foo");
	}

	@Test
	public void formatSchemeAndHostRelativeAddressSsl() throws Exception {
		String test1 = this.builder.host("example")
		                           .port(8080)
		                           .sslSupport()
		                           .build()
		                           .formatSchemeAndHost("/foo", false);
		String test2 = this.builder.host("example")
		                           .port(8080)
		                           .sslSupport()
		                           .build()
		                           .formatSchemeAndHost("/foo", true);

		assertThat(test1).isEqualTo("https://example:8080/foo");
		assertThat(test2).isEqualTo("wss://example:8080/foo");
	}

	@Test
	public void formatSchemeAndHostRelativeAndProxy() {
		String test = this.builder.proxy(proxyOptions)
				.host("google.com")
				.port(80)
				.build()
				.formatSchemeAndHost("/foo", false);

		assertThat(test).isEqualTo("http://google.com:80/foo");
	}

	@Test
	public void formatSchemeAndHostAbsoluteHttp() throws Exception {
		String test1 = this.builder.build()
		                           .formatSchemeAndHost("https://localhost/foo", false);
		String test2 = this.builder.build()
		                           .formatSchemeAndHost("http://localhost/foo", true);

		String test3 = this.builder.sslSupport()
		                           .build()
		                           .formatSchemeAndHost("http://localhost/foo", false);
		String test4 = this.builder.sslSupport()
		                           .build()
		                           .formatSchemeAndHost("https://localhost/foo", true);

		assertThat(test1).isEqualTo("https://localhost/foo");
		assertThat(test2).isEqualTo("http://localhost/foo");
		assertThat(test3).isEqualTo("http://localhost/foo");
		assertThat(test4).isEqualTo("https://localhost/foo");
	}

	@Test
	public void formatSchemeAndHostAbsoluteWs() throws Exception {
		String test1 = this.builder.build()
		                           .formatSchemeAndHost("wss://localhost/foo", false);
		String test2 = this.builder.build()
		                           .formatSchemeAndHost("ws://localhost/foo", true);

		String test3 = this.builder.sslSupport()
		                           .build()
		                           .formatSchemeAndHost("ws://localhost/foo", false);
		String test4 = this.builder.sslSupport()
		                           .build()
		                           .formatSchemeAndHost("wss://localhost/foo", true);

		assertThat(test1).isEqualTo("wss://localhost/foo");
		assertThat(test2).isEqualTo("ws://localhost/foo");
		assertThat(test3).isEqualTo("ws://localhost/foo");
		assertThat(test4).isEqualTo("wss://localhost/foo");
	}

}
