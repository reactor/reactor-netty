/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.netty.tcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.net.InetSocketAddress;
import java.util.function.Function;
import org.junit.Test;

public class ProxyProviderTest {

    private static final Function<String, String> PASSWORD_1 = username -> "123";
    private static final Function<String, String> PASSWORD_2 = username -> "456";

    private static final InetSocketAddress ADDRESS_1 = InetSocketAddress.createUnresolved("localhost", 80);
    private static final InetSocketAddress ADDRESS_2 = InetSocketAddress.createUnresolved("example.com", 80);

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
    public void differentAddresses() {
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1), createProxy(ADDRESS_2, PASSWORD_1));
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1).hashCode(), createProxy(ADDRESS_2, PASSWORD_1).hashCode());
    }

    @Test
    public void differentPasswords() {
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1), createProxy(ADDRESS_1, PASSWORD_2));
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1).hashCode(), createProxy(ADDRESS_1, PASSWORD_2).hashCode());
    }

    private ProxyProvider createProxy(InetSocketAddress address, Function<String, String> passwordFunc) {
        return ProxyProvider
                .builder()
                .type(ProxyProvider.Proxy.SOCKS5)
                .address(address)
                .username("netty")
                .password(passwordFunc)
                .build();
    }

    private ProxyProvider createNoAuthProxy(InetSocketAddress address) {
        return ProxyProvider
                .builder()
                .type(ProxyProvider.Proxy.SOCKS5)
                .address(address)
                .build();
    }
}
