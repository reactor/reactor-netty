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
    public void differentAddresses() {
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1), createProxy(ADDRESS_2, PASSWORD_1));
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_1).hashCode(), createProxy(ADDRESS_2, PASSWORD_1).hashCode());
    }

    @Test
    public void differentPasswords() {
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_2), createProxy(ADDRESS_2, PASSWORD_2));
        assertNotEquals(createProxy(ADDRESS_1, PASSWORD_2).hashCode(), createProxy(ADDRESS_2, PASSWORD_2).hashCode());
    }

    private ProxyProvider createProxy(InetSocketAddress address, Function<String, String> passwordFunc) {
        return ProxyProvider
                .builder()
                .type(ProxyProvider.Proxy.SOCKS5)
                .address(address)
                .port(80)
                .password(passwordFunc)
                .build();
    }
}
