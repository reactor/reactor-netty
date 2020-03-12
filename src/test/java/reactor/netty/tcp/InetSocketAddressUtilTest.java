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

package reactor.netty.tcp;

import java.net.InetSocketAddress;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class InetSocketAddressUtilTest {

	@Test
	public void shouldCreateResolvedNumericIPv4Address() {
		InetSocketAddress socketAddress = InetSocketAddressUtil
				.createResolved("127.0.0.1", 8080);
		assertThat(socketAddress.isUnresolved()).isFalse();
		assertThat(socketAddress.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
		assertThat(socketAddress.getPort()).isEqualTo(8080);
		assertThat(socketAddress.getHostString()).isEqualTo("127.0.0.1");
	}

	@Test
	public void shouldCreateResolvedNumericIPv6Address() {
		InetSocketAddress socketAddress = InetSocketAddressUtil.createResolved("::1",
				8080);
		assertThat(socketAddress.isUnresolved()).isFalse();
		assertThat(socketAddress.getAddress().getHostAddress())
				.isEqualTo("0:0:0:0:0:0:0:1");
		assertThat(socketAddress.getPort()).isEqualTo(8080);
		assertThat(socketAddress.getHostString()).isEqualTo("0:0:0:0:0:0:0:1");
	}

	@Test
	public void shouldCreateUnresolvedAddressByHostName() {
		InetSocketAddress socketAddress = InetSocketAddressUtil
				.createUnresolved("example.com", 80);
		assertThat(socketAddress.isUnresolved()).isTrue();
		assertThat(socketAddress.getPort()).isEqualTo(80);
		assertThat(socketAddress.getHostString()).isEqualTo("example.com");
	}

	@Test
	public void shouldAlwaysCreateResolvedNumberIPAddress() {
		InetSocketAddress socketAddress = InetSocketAddressUtil
				.createUnresolved("127.0.0.1", 8080);
		assertThat(socketAddress.isUnresolved()).isFalse();
		assertThat(socketAddress.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
		assertThat(socketAddress.getPort()).isEqualTo(8080);
		assertThat(socketAddress.getHostString()).isEqualTo("127.0.0.1");
	}

	@Test
	public void shouldReplaceNumericIPAddressWithResolvedInstance() {
		InetSocketAddress socketAddress = InetSocketAddress.createUnresolved("127.0.0.1",
				8080);
		InetSocketAddress replacedAddress = InetSocketAddressUtil
				.replaceUnresolvedNumericIp(socketAddress);
		assertThat(replacedAddress).isNotSameAs(socketAddress);
		assertThat(socketAddress.isUnresolved()).isTrue();
		assertThat(replacedAddress.isUnresolved()).isFalse();
		assertThat(replacedAddress.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
		assertThat(replacedAddress.getPort()).isEqualTo(8080);
		assertThat(replacedAddress.getHostString()).isEqualTo("127.0.0.1");
	}

	@Test
	public void shouldNotReplaceIfNonNumeric() {
		InetSocketAddress socketAddress = InetSocketAddress.createUnresolved("example.com",
				80);
		InetSocketAddress processedAddress = InetSocketAddressUtil
				.replaceUnresolvedNumericIp(socketAddress);
		assertThat(processedAddress).isSameAs(socketAddress);
	}

	@Test
	public void shouldNotReplaceIfAlreadyResolvedWhenCallingReplaceUnresolvedNumericIp() {
		InetSocketAddress socketAddress = new InetSocketAddress("127.0.0.1", 80);
		InetSocketAddress processedAddress = InetSocketAddressUtil
				.replaceUnresolvedNumericIp(socketAddress);
		assertThat(processedAddress).isSameAs(socketAddress);
	}

	@Test
	public void shouldResolveUnresolvedAddress() {
		InetSocketAddress socketAddress = InetSocketAddress.createUnresolved("example.com",
				80);
		InetSocketAddress processedAddress = InetSocketAddressUtil
				.replaceWithResolved(socketAddress);
		assertThat(processedAddress).isNotSameAs(socketAddress);
		assertThat(processedAddress.isUnresolved()).isFalse();
	}

	@Test
	public void shouldNotReplaceIfAlreadyResolved() {
		InetSocketAddress socketAddress = new InetSocketAddress("example.com", 80);
		InetSocketAddress processedAddress = InetSocketAddressUtil
				.replaceWithResolved(socketAddress);
		assertThat(processedAddress).isSameAs(socketAddress);
	}
}