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

package reactor.netty.http.server;

/**
 * Configurer implementation for {@link WebSocketConfigurer}
 *
 * @author Dmitrii Borin
 */
final class WebsocketServerConfigurer implements WebSocketConfigurer {
    private final String protocols;
    private final int maxFramePayloadLength;
    private final boolean proxyPing;

    WebsocketServerConfigurer(WebSocketConfigurer.Builder builder) {
        this.protocols = builder.protocols;
        this.maxFramePayloadLength = builder.maxFramePayloadLength;
        this.proxyPing = builder.proxyPing;
    }

    @Override
    public final String getProtocols() {
        return protocols;
    }

    @Override
    public final int getMaxFramePayloadLength() {
        return maxFramePayloadLength;
    }

    @Override
    public final boolean isProxyPing() {
        return proxyPing;
    }
}
