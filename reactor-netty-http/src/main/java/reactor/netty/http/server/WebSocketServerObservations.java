/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * WebSocket {@link HttpServer} observations.
 *
 * @author LivingLikeKrillin
 * @since 1.3.7
 */
enum WebSocketServerObservations implements ObservationDocumentation {

	/**
	 * WebSocket handshake metric.
	 */
	WEBSOCKET_SERVER_HANDSHAKE_TIME {
		@Override
		public KeyName[] getHighCardinalityKeyNames() {
			return HandshakeTimeHighCardinalityTags.values();
		}

		@Override
		public KeyName[] getLowCardinalityKeyNames() {
			return HandshakeTimeLowCardinalityTags.values();
		}

		@Override
		public String getName() {
			return "reactor.netty.websocket.server.handshake.time";
		}
	};

	/**
	 * Handshake Time High Cardinality Tags.
	 */
	enum HandshakeTimeHighCardinalityTags implements KeyName {

		/**
		 * Status code.
		 */
		HTTP_STATUS_CODE {
			@Override
			public String asString() {
				return "http.status_code";
			}
		},

		/**
		 * URL.
		 */
		HTTP_URL {
			@Override
			public String asString() {
				return "http.url";
			}
		},

		/**
		 * Reactor Netty type (always server).
		 */
		REACTOR_NETTY_TYPE {
			@Override
			public String asString() {
				return "reactor.netty.type";
			}
		}
	}

	/**
	 * Handshake Time Low Cardinality Tags.
	 */
	enum HandshakeTimeLowCardinalityTags implements KeyName {

		/**
		 * STATUS.
		 */
		STATUS {
			@Override
			public String asString() {
				return "status";
			}
		},

		/**
		 * URI.
		 */
		URI {
			@Override
			public String asString() {
				return "uri";
			}
		}
	}
}
