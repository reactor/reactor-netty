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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.Test;

import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import reactor.ipc.netty.options.ClientProxyOptions.Proxy;

public class ClientProxyOptionsTests {

	@Test
	public void asSimpleString() {
		ClientProxyOptions.Builder builder = ClientProxyOptions.builder();

		assertThat(builder.build().asSimpleString()).isEqualTo("proxy=null(null)");

		builder.type(Proxy.HTTP);
		assertThat(builder.build().asSimpleString()).isEqualTo("proxy=HTTP(null)");

		builder.host("http://proxy").port(456);
		assertThat(builder.build().asSimpleString()).isEqualTo("proxy=HTTP(http://proxy:456)");

		builder.address(new InetSocketAddress("http://another.proxy", 123));
		assertThat(builder.build().asSimpleString()).isEqualTo("proxy=HTTP(http://another.proxy:123)");
	}

	@Test
	public void asDetailedString() {
		ClientProxyOptions.Builder builder = ClientProxyOptions.builder();

		assertThat(builder.build().asDetailedString())
				.isEqualTo("address=null, nonProxyHosts=null, type=null");

		builder.type(Proxy.HTTP);
		assertThat(builder.build().asDetailedString())
				.isEqualTo("address=null, nonProxyHosts=null, type=HTTP");

		builder.host("http://proxy").port(456);
		assertThat(builder.build().asDetailedString())
				.isEqualTo("address=http://proxy:456, nonProxyHosts=null, type=HTTP");

		builder.address(() -> new InetSocketAddress("http://another.proxy", 123));
		assertThat(builder.build().asDetailedString())
				.isEqualTo("address=http://another.proxy:123, nonProxyHosts=null, type=HTTP");

		builder.nonProxyHosts("localhost");
		assertThat(builder.build().asDetailedString())
				.isEqualTo("address=http://another.proxy:123, nonProxyHosts=localhost, type=HTTP");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		ClientProxyOptions.Builder builder = ClientProxyOptions.builder();
		builder.host("http://proxy")
		       .port(456)
		       .type(Proxy.HTTP)
		       .build();
		assertThat(builder.build().toString()).isEqualTo(
				"ClientProxyOptions{address=http://proxy:456, nonProxyHosts=null, type=HTTP}");
	}

	@Test
	public void getProxyHandlerTypeNotSpecified() {
		ClientProxyOptions.Builder builder = ClientProxyOptions.builder();
		assertThat(builder.build().getProxyHandler()).isNull();
	}

	@Test
	public void getProxyHandlerAddressNotSpecified() {
		ClientProxyOptions.Builder builder = ClientProxyOptions.builder();
		builder.type(Proxy.HTTP);
		assertThat(builder.build().getProxyHandler()).isNull();
	}

	@Test
	public void getProxyHandlerTypeHttp() {
		ClientProxyOptions.Builder builder = ClientProxyOptions.builder();
		builder.host("http://proxy")
		       .port(456)
		       .type(Proxy.HTTP);

		assertThat(builder.build().getProxyHandler()).isInstanceOf(HttpProxyHandler.class);
		assertThat(builder.build().getProxyHandler().proxyAddress().toString()).isEqualTo("http://proxy:456");

		builder.username("test1");
		assertThat(((HttpProxyHandler) builder.build().getProxyHandler()).username()).isNull();

		builder.password(name -> "test2");
		assertThat(((HttpProxyHandler) builder.build().getProxyHandler()).username()).isEqualTo("test1");
		assertThat(((HttpProxyHandler) builder.build().getProxyHandler()).password()).isEqualTo("test2");
	}

	@Test
	public void getProxyHandlerTypeSocks4() {
		ClientProxyOptions.Builder builder = ClientProxyOptions.builder();
		builder.host("http://proxy")
		       .port(456)
		       .type(Proxy.SOCKS4);

		assertThat(builder.build().getProxyHandler()).isInstanceOf(Socks4ProxyHandler.class);
		assertThat(builder.build().getProxyHandler().proxyAddress().toString()).isEqualTo("http://proxy:456");

		builder.username("test1");
		assertThat(((Socks4ProxyHandler) builder.build().getProxyHandler()).username()).isEqualTo("test1");
	}

	@Test
	public void getProxyHandlerTypeSocks5() {
		ClientProxyOptions.Builder builder = ClientProxyOptions.builder();
		builder.host("http://proxy")
		       .port(456)
		       .type(Proxy.SOCKS5);

		assertThat(builder.build().getProxyHandler()).isInstanceOf(Socks5ProxyHandler.class);
		assertThat(builder.build().getProxyHandler().proxyAddress().toString()).isEqualTo("http://proxy:456");

		builder.username("test1");
		assertThat(((Socks5ProxyHandler) builder.build().getProxyHandler()).username()).isNull();

		builder.password(name -> "test2");
		assertThat(((Socks5ProxyHandler) builder.build().getProxyHandler()).username()).isEqualTo("test1");
		assertThat(((Socks5ProxyHandler) builder.build().getProxyHandler()).password()).isEqualTo("test2");
	}
}
