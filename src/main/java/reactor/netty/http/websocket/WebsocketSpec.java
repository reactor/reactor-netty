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

package reactor.netty.http.websocket;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Wrapper for websocket configuration
 *
 * @author Dmitrii Borin
 * @author Violeta Georgieva
 * @since 0.9.5
 */
public interface WebsocketSpec {

	/**
	 * Returns the configured sub protocols.
	 *
	 * @return returns the configured sub protocols.
	 */
	@Nullable
	String protocols();

	/**
	 * Returns the configured maximum allowable frame payload length.
	 *
	 * @return returns the configured maximum allowable frame payload length.
	 */
	int maxFramePayloadLength();

	/**
	 * Returns whether to proxy websocket PING frames or respond to them.
	 *
	 * @return returns whether to proxy websocket PING frames or respond to them.
	 */
	boolean handlePing();

	/**
	 * Returns whether the websocket compression extension is enabled.
	 *
	 * @return returns whether the websocket compression extension is enabled.
	 */
	boolean compress();

	class Builder<SPEC extends Builder<SPEC>> implements Supplier<SPEC> {
		String protocols;
		int maxFramePayloadLength = 65536;
		boolean handlePing;
		boolean compress;

		protected Builder() {
		}

		/**
		 * Sets sub-protocol to use in websocket handshake signature.
		 * Null by default.
		 *
		 * @param protocols sub-protocol
		 * @return {@literal this}
		 * @throws NullPointerException if protocols is null
		 */
		public final SPEC protocols(String protocols) {
			this.protocols = Objects.requireNonNull(protocols, "protocols");
			return get();
		}

		/**
		 * Sets specifies a custom maximum allowable frame payload length.
		 * 65536 by default.
		 *
		 * @param maxFramePayloadLength maximum allowable frame payload length
		 * @return {@literal this}
		 * @throws IllegalArgumentException if maxFramePayloadLength is negative
		 */
		public final SPEC maxFramePayloadLength(int maxFramePayloadLength) {
			if (maxFramePayloadLength <= 0) {
				throw new IllegalArgumentException("Max frame payload length value must be strictly positive");
			}
			this.maxFramePayloadLength = maxFramePayloadLength;
			return get();
		}

		/**
		 * Sets flag whether to proxy websocket ping frames or respond to them.
		 * False by default.
		 *
		 * @param handlePing whether to proxy websocket ping frames or respond to them
		 * @return {@literal this}
		 */
		public final SPEC handlePing(boolean handlePing) {
			this.handlePing = handlePing;
			return get();
		}

		/**
		 * Sets flag whether the websocket compression extension is enabled
		 * if the client request presents websocket extensions headers.
		 * By default compression is disabled.
		 *
		 * @param compress whether the websocket compression extension is enabled
		 * if the client request presents websocket extensions headers.
		 * @return {@literal this}
		 */
		public final SPEC compress(boolean compress) {
			this.compress = compress;
			return get();
		}

		@Override
		@SuppressWarnings("unchecked")
		public SPEC get() {
			return (SPEC) this;
		}
	}
}
