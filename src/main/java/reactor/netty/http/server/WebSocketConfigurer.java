package reactor.netty.http.server;

import javax.annotation.Nullable;

/**
 * Configurer for websocket
 *
 * @author Dmitrii Borin
 */
public interface WebSocketConfigurer {

    @Nullable
    String getProtocols();

    int getMaxFramePayloadLength();

    boolean isProxyPing();

    /**
     * Create builder with default properties:<br>
     * protocols = null
     * <br>
     * maxFramePayloadLength = 65536
     * <br>
     * proxyPing = false
     *
     * @return {@link Builder}
     */
    static Builder builder() {
        return new Builder();
    }

    final class Builder {
        String protocols;
        int maxFramePayloadLength = 65536;
        boolean proxyPing = false;

        private Builder() {
        }

        /**
         * Sets sub-protocol to use in websocket handshake signature
         *
         * @param protocols sub-protocol
         * @return {@literal this}
         */
        public final Builder protocols(@Nullable String protocols) {
            this.protocols = protocols;
            return this;
        }

        /**
         * Sets specifies a custom maximum allowable frame payload length
         *
         * @param maxFramePayloadLength maximum allowable frame payload length
         * @return {@literal this}
         */
        public final Builder maxFramePayloadLength(int maxFramePayloadLength) {
            this.maxFramePayloadLength = maxFramePayloadLength;
            return this;
        }

        /**
         * Sets flag whether to proxy websocket ping frames or respond to them
         *
         * @param proxyPing whether to proxy websocket ping frames or respond to them
         * @return {@literal this}
         */
        public final Builder proxyPing(boolean proxyPing) {
            this.proxyPing = proxyPing;
            return this;
        }

        /**
         * Builds new {@link WebSocketConfigurer}
         *
         * @return builds new {@link WebSocketConfigurer}
         */
        public final WebSocketConfigurer build() {
            return new WebsocketServerConfigurer(this);
        }
    }
}
