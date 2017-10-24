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

import java.util.function.Function;

public class HttpClientOptionsTest {
	private HttpClientOptions.Builder builder;
	private Function<ClientProxyOptions.TypeSpec, ClientProxyOptions.Builder> proxyOptions;

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
				.startsWith("connectAddress=null, proxy=SOCKS4(http://proxy")
				.contains(":456)")
				.endsWith(", acceptGzip=false");

		//address
		this.builder.host("http://google.com").port(123);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy")
				.contains(":456)")
				.endsWith(", acceptGzip=false");

		//gzip
		this.builder.compression(true);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy")
				.contains(":456)")
				.endsWith(", acceptGzip=true");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		this.builder.host("http://google.com")
		            .port(123)
		            .proxy(proxyOptions)
		            .compression(true);
		assertThat(this.builder.build().toString())
				.startsWith("HttpClientOptions{connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy")
				.contains(":456)")
				.endsWith(", acceptGzip=true}");
	}
}
