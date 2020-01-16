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

/**
 * Configurer for websocket
 *
 * @author Dmitrii Borin
 */
public class WebSocketConfigurer {
    private String protocols;
    private int maxFramePayloadLength   =   65536;
    private boolean proxyPing           =   false;

    private WebSocketConfigurer() {
    }

    /**
     * Builds configurer with default properties:<br>
     * protocols = null
     * <br>
     * maxFramePayloadLength = 65536
     * <br>
     * proxyPing = false
     * @return {@link WebSocketConfigurer} with default properties
     */
    public static WebSocketConfigurer newInstance() {
        return new WebSocketConfigurer();
    }

    /**
     * Sets sub-protocol to use in websocket handshake signature
     *
     * @param protocols sub-protocol
     * @return {@literal this}
     */
    public WebSocketConfigurer setProtocols(String protocols) {
        this.protocols = protocols;
        return this;
    }

    /**
     * Sets specifies a custom maximum allowable frame payload length
     *
     * @param maxFramePayloadLength maximum allowable frame payload length
     * @return {@literal this}
     */
    public WebSocketConfigurer setMaxFramePayloadLength(int maxFramePayloadLength) {
        this.maxFramePayloadLength = maxFramePayloadLength;
        return this;
    }

    /**
     * Sets flag whether to proxy websocket ping frames or respond to them
     *
     * @param proxyPing whether to proxy websocket ping frames or respond to them
     * @return {@literal this}
     */
    public WebSocketConfigurer setProxyPing(boolean proxyPing) {
        this.proxyPing = proxyPing;
        return this;
    }

    public String getProtocols() {
        return protocols;
    }

    public int getMaxFramePayloadLength() {
        return maxFramePayloadLength;
    }

    public boolean isProxyPing() {
        return proxyPing;
    }
}
