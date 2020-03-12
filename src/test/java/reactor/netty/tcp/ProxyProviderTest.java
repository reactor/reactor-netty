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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.function.Function;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.Test;

public class ProxyProviderTest {

    private static final Function<String, String> PASSWORD_1 = username -> "123";
    private static final Function<String, String> PASSWORD_2 = username -> "456";

    private static final String NON_PROXY_HOSTS = "localhost";

    private static final InetSocketAddress ADDRESS_1 = InetSocketAddress.createUnresolved("localhost", 80);
    private static final InetSocketAddress ADDRESS_2 = InetSocketAddress.createUnresolved("example.com", 80);

    private static final Consumer<HttpHeaders> HEADER_1 = list -> list.add("Authorization", "Bearer 123");
    private static final Consumer<HttpHeaders> HEADER_2 = list -> list.add("Authorization", "Bearer 456");

    @Test
    public void equalProxyProviders() {
        assertEquals(createProxy(ADDRESS_1, PASSWORD_1), createProxy(ADDRESS_1, PASSWORD_1));
        assertEquals(createProxy(ADDRESS_1, PASSWORD_1).hashCode(), createProxy(ADDRESS_1, PASSWORD_1).hashCode());
    }

    @Test
    public void equalProxyProvidersNoAuth() {
        assertEquals(createNoAuthProxy(ADDRESS_1), createNoAuthProxy(ADDRESS_1));
        assertEquals(createNoAuthProxy(ADDRESS_1).hashCode(), createNoAuthProxy(ADDRESS_1).hashCode());
    }

    @Test
    public void equalProxyProvidersAuthHeader() {
        assertEquals(createHeaderProxy(ADDRESS_1, HEADER_1), createHeaderProxy(ADDRESS_1, HEADER_1));
        assertEquals(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode(), createHeaderProxy(ADDRESS_1, HEADER_1).hashCode());
    }

    @Test
    public void differentAddresses() {
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1), createProxy(ADDRESS_2, PASSWORD_1));
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1).hashCode(), createProxy(ADDRESS_2, PASSWORD_1).hashCode());
    }

    @Test
    public void differentPasswords() {
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1), createProxy(ADDRESS_1, PASSWORD_2));
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1).hashCode(), createProxy(ADDRESS_1, PASSWORD_2).hashCode());
    }

    @Test
    public void differentAuthHeaders() {
        assertNotEquals(createHeaderProxy(ADDRESS_1, HEADER_1), createHeaderProxy(ADDRESS_1, HEADER_2));
        assertNotEquals(createHeaderProxy(ADDRESS_1, HEADER_1).hashCode(), createHeaderProxy(ADDRESS_1, HEADER_2).hashCode());
    }

    private ProxyProvider createProxy(InetSocketAddress address, Function<String, String> passwordFunc) {
        return ProxyProvider
                .builder()
                .type(ProxyProvider.Proxy.SOCKS5)
                .address(address)
                .username("netty")
                .password(passwordFunc)
                .nonProxyHosts(NON_PROXY_HOSTS)
                .build();
    }

    private ProxyProvider createNoAuthProxy(InetSocketAddress address) {
        return ProxyProvider
                .builder()
                .type(ProxyProvider.Proxy.SOCKS5)
                .address(address)
                .nonProxyHosts("localhost")
                .build();
    }

    private ProxyProvider createHeaderProxy(InetSocketAddress address, Consumer<HttpHeaders> authHeader) {
        return ProxyProvider
                .builder()
                .type(ProxyProvider.Proxy.HTTP)
                .address(address)
                .httpHeaders(authHeader)
                .build();
    }

}
