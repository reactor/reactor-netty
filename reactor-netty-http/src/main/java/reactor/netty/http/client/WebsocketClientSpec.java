/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import reactor.netty.http.websocket.WebsocketSpec;

import java.util.Objects;

/**
 * Websocket client configuration
 *
 * @author Violeta Georgieva
 * @since 0.9.7
 */
public interface WebsocketClientSpec extends WebsocketSpec {

	/**
	 * Returns the configured WebSocket version
	 *
	 * @return returns the configured WebSocket version
	 * @since 1.0.3
	 */
	WebSocketVersion version();

	/**
	 * Create builder with default properties:<br>
	 * version = {@link io.netty.handler.codec.http.websocketx.WebSocketVersion#V13}
	 * <br>
	 * protocols = null
	 * <br>
	 * maxFramePayloadLength = 65536
	 * <br>
	 * handlePing = false
	 * <br>
	 * compress = false
	 *
	 * @return {@link Builder}
	 */
	static Builder builder() {
		return new Builder();
	}

	final class Builder extends WebsocketSpec.Builder<Builder> {

		WebSocketVersion version = WebSocketVersion.V13;

		private Builder() {
		}

		/**
		 * Sets websocket version to use.
		 * Set to {@link io.netty.handler.codec.http.websocketx.WebSocketVersion#V13} by default
		 *
		 * @param version WebSocket version to be used
		 * @return {@literal this}
		 * @throws NullPointerException if version is null
		 * @throws IllegalArgumentException if the version is unknown
		 * @since 1.0.3
		 */
		public final Builder version(WebSocketVersion version) {
			Objects.requireNonNull(version, "version");
			if (WebSocketVersion.UNKNOWN.equals(version)) {
				throw new IllegalArgumentException("WebSocketVersion.UNKNOWN represents an invalid version, please provide a proper version");
			}
			this.version = version;
			return this;
		}

		/**
		 * Builds new {@link WebsocketClientSpec}
		 *
		 * @return builds new {@link WebsocketClientSpec}
		 */
		public final WebsocketClientSpec build() {
			return new WebsocketClientSpecImpl(this);
		}
	}
}
