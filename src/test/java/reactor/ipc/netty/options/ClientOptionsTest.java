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

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ClientOptionsTest {

	@Test
	public void asSimpleString() {
		ClientOptions opt = ClientOptions.create();

		assertThat(opt.asSimpleString()).isEqualTo("connecting to no base address");

		//proxy
		opt.proxy(ClientOptions.Proxy.SOCKS4, "http://proxy", 456);
		assertThat(opt.asSimpleString()).isEqualTo("connecting to no base address through SOCKS4 proxy");

		//address
		opt.connect("http://google.com", 123);
		assertThat(opt.asSimpleString()).isEqualTo("connecting to http://google.com:123 through SOCKS4 proxy");
	}

	@Test
	public void asDetailedString() {
		ClientOptions opt = ClientOptions.create();

		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=null, proxy=null");

		//proxy
		opt.proxy(ClientOptions.Proxy.SOCKS4, "http://proxy", 456);
		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=null, proxy=SOCKS4(http://proxy:456)");

		//address
		opt.connect("http://google.com", 123);
		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		ClientOptions opt = ClientOptions.create()
		                                         .connect("http://google.com", 123)
		                                         .proxy(ClientOptions.Proxy.SOCKS4, "http://proxy", 456);
		assertThat(opt.toString())
				.startsWith("ClientOptions{connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith("}");
	}

}