/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.client;

import io.netty5.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import reactor.netty5.transport.ProxyProvider;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientProxyProviderTest {

	private static final InetSocketAddress ADDRESS_1 = InetSocketAddress.createUnresolved("localhost", 80);
	@SuppressWarnings("UnnecessaryLambda")
	private static final Consumer<HttpHeaders> HEADER_1 = list -> list.add("Authorization", "Bearer 123");
	@SuppressWarnings("UnnecessaryLambda")
	private static final Consumer<HttpHeaders> HEADER_2 = list -> list.add("Authorization", "Bearer 456");

	@Test
	void equalProxyProvidersAuthHeader() {
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1)).isEqualTo(createHeaderProxy(ADDRESS_1, HEADER_1));
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode()).isEqualTo(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode());
	}

	@Test
	void differentAuthHeaders() {
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1)).isNotEqualTo(createHeaderProxy(ADDRESS_1, HEADER_2));
		assertThat(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode()).isNotEqualTo(createHeaderProxy(ADDRESS_1, HEADER_2).hashCode());
	}

	private ProxyProvider createHeaderProxy(InetSocketAddress address, Consumer<HttpHeaders> authHeader) {
		return new HttpClientProxyProvider.Build()
				.type(ProxyProvider.Proxy.HTTP)
				.address(address)
				.httpHeaders(authHeader)
				.build();
	}
}
