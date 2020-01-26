/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
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

package reactor.netty.http.websocket;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Configurer for websocket
 *
 * @author Dmitrii Borin
 */
public interface WebSocketConfigurer {

    @Nullable
    String getProtocols();

    int getMaxFramePayloadLength();

    boolean isHandlePing();

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
        boolean handlePing = false;

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
         * @param handlePing whether to proxy websocket ping frames or respond to them
         * @return {@literal this}
         */
        public final Builder handlePing(boolean handlePing) {
            this.handlePing = handlePing;
            return this;
        }

        /**
         * Builds new {@link WebSocketConfigurer}
         *
         * @return builds new {@link WebSocketConfigurer}
         */
        public final WebSocketConfigurer build() {
            return new WebsocketConfigurerImpl(this);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Builder)) {
                return false;
            }
            Builder builder = (Builder) o;
            return maxFramePayloadLength == builder.maxFramePayloadLength &&
                    handlePing == builder.handlePing &&
                    Objects.equals(protocols, builder.protocols);
        }

        @Override
        public int hashCode() {
            return Objects.hash(protocols, maxFramePayloadLength, handlePing);
        }
    }
}
