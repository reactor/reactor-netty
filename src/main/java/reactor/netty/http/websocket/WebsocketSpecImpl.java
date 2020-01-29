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

import java.util.Objects;

/**
 * Configurer implementation for {@link WebSocketSpec}
 *
 * @author Dmitrii Borin
 */
final class WebsocketSpecImpl implements WebSocketSpec {
    private final String protocols;
    private final int maxFramePayloadLength;
    private final boolean proxyPing;

    WebsocketSpecImpl(WebSocketSpec.Builder builder) {
        this.protocols = builder.protocols;
        this.maxFramePayloadLength = builder.maxFramePayloadLength;
        this.proxyPing = builder.handlePing;
    }

    @Override
    public final String protocols() {
        return protocols;
    }

    @Override
    public final int maxFramePayloadLength() {
        return maxFramePayloadLength;
    }

    @Override
    public final boolean handlePing() {
        return proxyPing;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebsocketSpecImpl)) {
            return false;
        }
        WebsocketSpecImpl that = (WebsocketSpecImpl) o;
        return maxFramePayloadLength == that.maxFramePayloadLength &&
                proxyPing == that.proxyPing &&
                Objects.equals(protocols, that.protocols);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocols, maxFramePayloadLength, proxyPing);
    }
}
