package reactor.netty.http.client;

import reactor.netty.tcp.ProxyProvider;
import reactor.netty.tcp.TcpClient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Properties;

final class HttpClientProxyWithSystemProperties extends HttpClientOperator {

    final static class ProxySettings {
        static final String HTTP_PROXY_HOST = "http.proxyHost";
        static final String HTTP_PROXY_PORT = "http.proxyPort";
        static final String HTTPS_PROXY_HOST = "https.proxyHost";
        static final String HTTPS_PROXY_PORT = "https.proxyPort";
        static final String HTTP_NON_PROXY_HOSTS = "http.nonProxyHosts";

        static final String SOCKS_PROXY_HOST = "socksProxyHost";
        static final String SOCKS_PROXY_PORT = "socksProxyPort";
        static final String SOCKS_VERSION = "socksProxyVersion";
        static final String SOCKS_VERSION_5 = "5";
        static final String SOCKS_USERNAME = "java.net.socks.username";
        static final String SOCKS_PASSWORD = "java.net.socks.password";
        public static final String DEFAULT_NON_PROXY_HOSTS = "localhost|127.*|[::1]";


        @Nonnull final ProxyProvider.Proxy type;
        @Nonnull final String hostname;
        final int port;
        @Nullable final String nonProxyHosts;
        @Nullable final String username;
        @Nullable final String password;

        public ProxySettings(@Nonnull ProxyProvider.Proxy type, @Nonnull String hostname, int port,
                             @Nullable String nonProxyHosts, @Nullable String username, @Nullable String password) {
            this.type = type;
            this.hostname = hostname;
            this.port = port;
            this.nonProxyHosts = nonProxyHosts;
            this.username = username;
            this.password = password;
        }

        @Nullable static ProxySettings from(Properties properties) {
            Objects.requireNonNull(properties, "properties");

            if (properties.containsKey(HTTP_PROXY_HOST) || properties.containsKey(HTTPS_PROXY_HOST)) {
                return httpProxyFrom(properties);
            }
            if (properties.containsKey(SOCKS_PROXY_HOST)) {
                return socksProxyFrom(properties);
            }

            return null;
        }

        static ProxySettings httpProxyFrom(Properties properties) {
            String hostname = properties.getProperty(HTTPS_PROXY_HOST);
            if (hostname == null) {
                hostname = properties.getProperty(HTTP_PROXY_HOST);
            }
            int port = properties.containsKey(HTTPS_PROXY_HOST) ? 443 : 80;
            if (properties.containsKey(HTTPS_PROXY_PORT)) {
                port = Integer.parseInt(properties.getProperty(HTTPS_PROXY_PORT));
            } else if (properties.containsKey(HTTP_PROXY_PORT)) {
                port = Integer.parseInt(properties.getProperty(HTTP_PROXY_PORT));
            }
            String nonProxyHosts = properties.getProperty(HTTP_NON_PROXY_HOSTS, DEFAULT_NON_PROXY_HOSTS);

            return new ProxySettings(ProxyProvider.Proxy.HTTP, hostname, port, nonProxyHosts, null, null);
        }

        static ProxySettings socksProxyFrom(Properties properties) {
            String hostname = properties.getProperty(SOCKS_PROXY_HOST);
            ProxyProvider.Proxy type = ProxyProvider.Proxy.SOCKS5;
            if (properties.containsKey(SOCKS_VERSION)) {
                type = SOCKS_VERSION_5.equals(properties.getProperty(SOCKS_VERSION))
                        ? ProxyProvider.Proxy.SOCKS5
                        : ProxyProvider.Proxy.SOCKS4;
            }
            int port = Integer.parseInt(properties.getProperty(SOCKS_PROXY_PORT, "1080"));

            String username = properties.getProperty(SOCKS_USERNAME);
            String password = properties.getProperty(SOCKS_PASSWORD);

            return new ProxySettings(type, hostname, port, null, username, password);
        }
    }

    final ProxySettings settings;

    HttpClientProxyWithSystemProperties(HttpClient source, Properties properties) {
        super(source);
        this.settings = ProxySettings.from(properties);
    }

    @Override
    protected TcpClient tcpConfiguration() {
        TcpClient client = source.tcpConfiguration();
        if (settings == null) return client;

        return client.proxy(p -> {
            ProxyProvider.Builder proxy = p.type(settings.type)
                    .host(settings.hostname)
                    .port(settings.port);

            if (settings.nonProxyHosts != null)  {
                proxy.nonProxyHosts(settings.nonProxyHosts);
            }
            if (settings.username != null) {
                proxy.username(settings.username);
            }
            if (settings.password != null) {
                proxy.password(u -> settings.password);
            }
        });
    }
}
