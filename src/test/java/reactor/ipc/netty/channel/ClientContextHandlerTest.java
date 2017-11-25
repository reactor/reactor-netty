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

package reactor.ipc.netty.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.Test;

import io.netty.channel.embedded.EmbeddedChannel;
import reactor.ipc.netty.NettyPipeline;

public class ClientContextHandlerTest {
	@Test public void test() {}
/*
	@Test
	public void addProxyHandler() {
		ClientOptions.Builder<?> builder = ClientOptions.builder();
		EmbeddedChannel channel = new EmbeddedChannel();

		ClientContextHandler.addProxyHandler(builder.build(), channel.pipeline(),
				new InetSocketAddress("localhost", 8080));
		assertThat(channel.pipeline().get(NettyPipeline.ProxyHandler)).isNull();

		builder.proxy(ops -> ops.type(Proxy.HTTP)
		                        .host("proxy")
		                        .port(8080));
		ClientContextHandler.addProxyHandler(builder.build(), channel.pipeline(),
				new InetSocketAddress("localhost", 8080));
		assertThat(channel.pipeline().get(NettyPipeline.ProxyHandler)).isNull();
	}*/
}
