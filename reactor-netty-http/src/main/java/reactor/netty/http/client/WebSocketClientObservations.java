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
package reactor.netty.http.client;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * WebSocket {@link HttpClient} observations.
 *
 * @author LivingLikeKrillin
 * @since 1.3.5
 */
enum WebSocketClientObservations implements ObservationDocumentation {

	/**
	 * WebSocket handshake metric.
	 */
	WEBSOCKET_CLIENT_HANDSHAKE_TIME {
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
			return "reactor.netty.http.client.websocket.handshake.time";
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
		 * Net peer name.
		 */
		NET_PEER_NAME {
			@Override
			public String asString() {
				return "net.peer.name";
			}
		},

		/**
		 * Net peer port.
		 */
		NET_PEER_PORT {
			@Override
			public String asString() {
				return "net.peer.port";
			}
		},

		/**
		 * Reactor Netty type (always client).
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
		 * Proxy address, when there is a proxy configured.
		 */
		PROXY_ADDRESS {
			@Override
			public String asString() {
				return "proxy.address";
			}
		},

		/**
		 * Remote address.
		 */
		REMOTE_ADDRESS {
			@Override
			public String asString() {
				return "remote.address";
			}
		},

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
