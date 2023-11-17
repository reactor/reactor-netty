/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import reactor.netty.http.websocket.WebsocketSpec;

/**
 * Websocket server configuration.
 *
 * @author Violeta Georgieva
 * @since 0.9.5
 */
public interface WebsocketServerSpec extends WebsocketSpec {

	/**
	 * Returns whether the client is allowed to activate {@code server_no_context_takeover}.
	 *
	 * @return whether the client is allowed to activate {@code server_no_context_takeover}
	 * @since 1.1.14
	 */
	boolean compressionAllowServerNoContext();

	/**
	 * Returns whether the server prefers to activate {@code client_no_context_takeover}.
	 *
	 * @return whether the server prefers to activate {@code client_no_context_takeover}
	 * @since 1.1.14
	 */
	boolean compressionPreferredClientNoContext();

	/**
	 * Create builder with default properties.<br>
	 * protocols = null
	 * <br>
	 * maxFramePayloadLength = 65536
	 * <br>
	 * handlePing = false
	 * <br>
	 * compress = false
	 * <br>
	 * compressionAllowServerNoContext = false
	 * <br>
	 * compressionPreferredClientNoContext = false
	 *
	 * @return {@link WebsocketServerSpec.Builder}
	 */
	static Builder builder() {
		return new Builder();
	}

	final class Builder extends WebsocketSpec.Builder<Builder> {

		boolean allowServerNoContext;
		boolean preferredClientNoContext;

		private Builder() {
		}

		/**
		 * Allows the client to activate {@code server_no_context_takeover}.
		 * Default to false.
		 *
		 * @param allowServerNoContext allows the client to activate {@code server_no_context_takeover}
		 * @return {@literal this}
		 * @since 1.1.14
		 * @see PerMessageDeflateServerExtensionHandshaker
		 */
		public final Builder compressionAllowServerNoContext(boolean allowServerNoContext) {
			this.allowServerNoContext = allowServerNoContext;
			return this;
		}

		/**
		 * Indicates if the server prefers to activate {@code client_no_context_takeover} if client is compatible with.
		 * Default to false.
		 *
		 * @param preferredClientNoContext indicates if the server prefers to activate
		 * {@code client_no_context_takeover} if client is compatible with
		 * @return {@literal this}
		 * @since 1.1.14
		 * @see PerMessageDeflateServerExtensionHandshaker
		 */
		public final Builder compressionPreferredClientNoContext(boolean preferredClientNoContext) {
			this.preferredClientNoContext = preferredClientNoContext;
			return this;
		}

		/**
		 * Builds new {@link WebsocketServerSpec}.
		 *
		 * @return builds new {@link WebsocketServerSpec}
		 */
		public final WebsocketServerSpec build() {
			return new WebsocketServerSpecImpl(this);
		}
	}
}
