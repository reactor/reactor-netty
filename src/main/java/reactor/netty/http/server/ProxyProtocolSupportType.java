package reactor.netty.http.server;

/**
 * Proxy protocol support type, this enum class defines how the HttpServer handles proxy protocol.
 *
 * @author aftersss
 */
public enum ProxyProtocolSupportType {
    /**
     * Each connection of the same `HttpServer` will auto detect whether there is proxy protocol.
     * The HttpServer can support multiple clients(or reverse proxy server like HaProxy)
     * with and without proxy protocol enabled at the same time.
     */
    AUTO,

    /**
     * Enable support for the {@code "HAProxy proxy protocol"}
     * for deriving information about the address of the remote peer.
     */
    ON,

    /**
     * Disable the proxy protocol support
     */
    OFF
}
