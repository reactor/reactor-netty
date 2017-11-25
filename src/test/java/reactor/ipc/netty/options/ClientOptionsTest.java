/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
import java.util.function.Function;

public class ClientOptionsTest {	@Test public void test() {}
/*

	private ClientOptions.Builder<?> builder;
	private Function<ClientProxyOptions.TypeSpec, ClientProxyOptions.Builder> proxyOptions;

	@Before
	public void setUp() {
		this.builder = ClientOptions.builder();
		this.proxyOptions = ops -> ops.type(ClientProxyOptions.Proxy.SOCKS4)
		                              .host("http://proxy")
		                              .port(456);
	}

	@Test
	public void asSimpleString() {
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to no base address");

		//proxy
		this.builder.proxy(this.proxyOptions);
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
		this.builder.proxy(this.proxyOptions);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=null, proxy=SOCKS4(http://proxy")
				.contains(":456)");

		//address
		this.builder.host("http://google.com").port(123);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy")
				.contains(":456)");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		this.builder.host("http://google.com")
		            .port(123)
		            .proxy(proxyOptions)
		            .build();
		assertThat(this.builder.build().toString())
				.startsWith("ClientOptions{connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy")
				.contains(":456)")
				.endsWith("}");
	}

	@Test
	public void useProxy() {
		Function<ClientProxyOptions.TypeSpec, ClientProxyOptions.Builder> proxyBuilder = ops ->
		                                             ops.type(ClientProxyOptions.Proxy.SOCKS4)
		                                                .host("http://proxy")
		                                                .port(456);

		ClientOptions.Builder<?> opsBuilder = ClientOptions.builder();

		assertThat(opsBuilder.build().useProxy("hostName")).isFalse();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("google.com", 123))).isFalse();

		opsBuilder.proxy(proxyBuilder);
		assertThat(opsBuilder.build().useProxy("hostName")).isTrue();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("google.com", 123))).isTrue();

		Function<ClientProxyOptions.TypeSpec, ClientProxyOptions.Builder> extProxyBuilder = 
				proxyBuilder.andThen(ops -> ops.nonProxyHosts("localhost|127\\.0\\.0\\.1"));
		opsBuilder.proxy(extProxyBuilder);
		assertThat(opsBuilder.build().useProxy((String) null)).isTrue();
		assertThat(opsBuilder.build().useProxy((InetSocketAddress) null)).isTrue();
		assertThat(opsBuilder.build().useProxy("hostName")).isTrue();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("google.com", 123))).isTrue();
		assertThat(opsBuilder.build().useProxy("localhost")).isFalse();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("localhost", 8080))).isFalse();
		assertThat(opsBuilder.build().useProxy(new InetSocketAddress("127.0.0.1", 8080))).isFalse();
	}*/
}