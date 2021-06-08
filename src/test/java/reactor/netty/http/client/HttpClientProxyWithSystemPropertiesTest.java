package reactor.netty.http.client;

import org.junit.Test;
import reactor.netty.http.client.HttpClientProxyWithSystemProperties.ProxySettings;
import reactor.netty.tcp.ProxyProvider;
import reactor.netty.tcp.TcpClient;

import java.util.Properties;

import static org.junit.Assert.*;

public class HttpClientProxyWithSystemPropertiesTest {

    @Test
    public void nullProxySettingsIfNoHostnameDefined() {
        Properties properties = new Properties();
        ProxySettings settings = ProxySettings.from(properties);

        assertNull(settings);
    }

    @Test
    public void proxySettingsIsNotNullWhenHttpHostSet() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTP_PROXY_HOST, "host");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(ProxyProvider.Proxy.HTTP, settings.type);
        assertEquals("host", settings.hostname);
    }

    @Test
    public void port80SetByDefaultForHttpProxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTP_PROXY_HOST, "host");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(80, settings.port);
    }

    @Test
    public void parseHttpPortFromSystemProperties() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTP_PROXY_HOST, "host");
        properties.setProperty(ProxySettings.HTTP_PROXY_PORT, "8080");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(8080, settings.port);
    }

    @Test
    public void proxySettingsIsNotNullWhenHttpSHostSet() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTPS_PROXY_HOST, "host");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(ProxyProvider.Proxy.HTTP, settings.type);
        assertEquals("host", settings.hostname);
    }

    @Test
    public void port443SetByDefaultForHttpProxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTPS_PROXY_HOST, "host");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(443, settings.port);
    }

    @Test
    public void parseHttpsPortFromSystemProperties() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTPS_PROXY_HOST, "host");
        properties.setProperty(ProxySettings.HTTP_PROXY_PORT, "8443");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(8443, settings.port);
    }

    @Test
    public void defaultNonHttpHostsSetForHttpProxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTP_PROXY_HOST, "host");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(ProxySettings.DEFAULT_NON_PROXY_HOSTS, settings.nonProxyHosts);
    }

    @Test
    public void defaultNonHttpHostsSetForHttpsProxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTPS_PROXY_HOST, "host");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(ProxySettings.DEFAULT_NON_PROXY_HOSTS, settings.nonProxyHosts);
    }

    @Test
    public void customNonProxyHostsSetForHttpProxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTP_PROXY_HOST, "host");
        properties.setProperty(ProxySettings.HTTP_NON_PROXY_HOSTS, "non-host");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals("non-host", settings.nonProxyHosts);
    }

    @Test
    public void customNonProxyHostsSetForHttpsProxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTPS_PROXY_HOST, "host");
        properties.setProperty(ProxySettings.HTTP_NON_PROXY_HOSTS, "non-host");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals("non-host", settings.nonProxyHosts);
    }

    @Test
    public void socksProxy5SetWhenSocksSystemPropertySet() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.SOCKS_PROXY_HOST, "host");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals("host", settings.hostname);
        assertEquals(ProxyProvider.Proxy.SOCKS5, settings.type);
    }

    @Test
    public void overrideSocksVersionWithCustomProperty() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.SOCKS_PROXY_HOST, "host");
        properties.setProperty(ProxySettings.SOCKS_VERSION, "4");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(ProxyProvider.Proxy.SOCKS4, settings.type);
    }

    @Test
    public void overrideSocksPortWithCustomProperty() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.SOCKS_PROXY_HOST, "host");
        properties.setProperty(ProxySettings.SOCKS_PROXY_PORT, "2080");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals(2080, settings.port);
    }

    @Test
    public void setUserPasswordInSockProxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.SOCKS_PROXY_HOST, "host");
        properties.setProperty(ProxySettings.SOCKS_USERNAME, "user");
        properties.setProperty(ProxySettings.SOCKS_PASSWORD, "pwd");

        ProxySettings settings = ProxySettings.from(properties);

        assertNotNull(settings);
        assertEquals("user", settings.username);
        assertEquals("pwd", settings.password);
    }

    @Test
    public void createClientWithoutProxy() {
        Properties properties = new Properties();
        HttpClient client = new HttpClientProxyWithSystemProperties(HttpClient.create(), properties);

        TcpClient tcpClient = client.tcpConfiguration();

        assertNotNull(tcpClient);
        assertFalse(tcpClient.hasProxy());
    }

    @Test
    public void createClientWithHttpProxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTP_PROXY_HOST, "host");
        HttpClient client = new HttpClientProxyWithSystemProperties(HttpClient.create(), properties);

        TcpClient tcpClient = client.tcpConfiguration();

        assertNotNull(tcpClient);
        assertTrue(tcpClient.hasProxy());
        assertEquals(ProxyProvider.Proxy.HTTP, tcpClient.proxyProvider().getType());
    }

    @Test
    public void createClientWithHttpsProxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.HTTPS_PROXY_HOST, "host");
        HttpClient client = new HttpClientProxyWithSystemProperties(HttpClient.create(), properties);

        TcpClient tcpClient = client.tcpConfiguration();

        assertNotNull(tcpClient);
        assertTrue(tcpClient.hasProxy());
        assertEquals(ProxyProvider.Proxy.HTTP, tcpClient.proxyProvider().getType());
    }

    @Test
    public void createClientWithSock5Proxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.SOCKS_PROXY_HOST, "host");
        HttpClient client = new HttpClientProxyWithSystemProperties(HttpClient.create(), properties);

        TcpClient tcpClient = client.tcpConfiguration();

        assertNotNull(tcpClient);
        assertTrue(tcpClient.hasProxy());
        assertEquals(ProxyProvider.Proxy.SOCKS5, tcpClient.proxyProvider().getType());
    }

    @Test
    public void createClientWithSock4Proxy() {
        Properties properties = new Properties();
        properties.setProperty(ProxySettings.SOCKS_PROXY_HOST, "host");
        properties.setProperty(ProxySettings.SOCKS_VERSION, "4");
        HttpClient client = new HttpClientProxyWithSystemProperties(HttpClient.create(), properties);

        TcpClient tcpClient = client.tcpConfiguration();

        assertNotNull(tcpClient);
        assertTrue(tcpClient.hasProxy());
        assertEquals(ProxyProvider.Proxy.SOCKS4, tcpClient.proxyProvider().getType());
    }

    @Test
    public void createClientWithSystemProxySettings() {
        HttpClient client = HttpClient.create()
                .proxyWithSystemProperties();

        assertNotNull(client);
    }
}