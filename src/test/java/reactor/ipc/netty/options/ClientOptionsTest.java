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

package reactor.ipc.netty.options;

import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

public class ClientOptionsTest {
	private ClientOptions.Builder<?> builder;
	private ClientProxyOptions proxyOptions;

	@Before
	public void setUp() {
		this.builder = ClientOptions.builder();
		this.proxyOptions =
				ClientProxyOptions.builder()
				                  .type(ClientProxyOptions.Proxy.SOCKS4)
				                  .host("http://proxy")
				                  .port(456)
				                  .build();
	}

	@Test
	public void asSimpleString() {
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to no base address");

		//proxy
		this.builder.proxyOptions(this.proxyOptions);
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to no base address through SOCKS4 proxy");

		//address
		this.builder.host("http://google.com").port(123);
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to http://google.com:123 through SOCKS4 proxy");
	}

	@Test
	public void asDetailedString() {
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=null, proxy=null");

		//proxy
		this.builder.proxyOptions(this.proxyOptions);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=null, proxy=SOCKS4(http://proxy:456)");

		//address
		this.builder.host("http://google.com").port(123);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		this.builder.host("http://google.com")
		            .port(123)
		            .proxyOptions(proxyOptions)
		            .build();
		assertThat(this.builder.build().toString())
				.startsWith("ClientOptions{connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith("}");
	}

	@Test
	public void useProxy() {
		ClientProxyOptions.Builder proxyBuilder = ClientProxyOptions.builder()
		                                             .type(ClientProxyOptions.Proxy.SOCKS4)
		                                             .host("http://proxy")
		                                             .port(456);

		ClientOptions.Builder<?> opsBuilder = ClientOptions.builder();

		assertThat(opsBuilder.build().useProxy("hostName")).isFalse();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("google.com", 123))).isFalse();

		opsBuilder.proxyOptions(proxyBuilder.build());
		assertThat(opsBuilder.build().useProxy("hostName")).isTrue();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("google.com", 123))).isTrue();

		proxyBuilder.nonProxyHosts("localhost");
		opsBuilder.proxyOptions(proxyBuilder.build());
		assertThat(opsBuilder.build().useProxy((String) null)).isTrue();
		assertThat(opsBuilder.build().useProxy((InetSocketAddress) null)).isTrue();
		assertThat(opsBuilder.build().useProxy("hostName")).isTrue();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("google.com", 123))).isTrue();
		assertThat(opsBuilder.build().useProxy("localhost")).isFalse();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("localhost", 8080))).isFalse();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("127.0.0.1", 8080))).isFalse();
	}
}