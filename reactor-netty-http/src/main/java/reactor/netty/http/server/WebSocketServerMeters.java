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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.docs.MeterDocumentation;

/**
 * WebSocket {@link HttpServer} meters.
 *
 * @author LivingLikeKrillin
 * @since 1.3.7
 */
enum WebSocketServerMeters implements MeterDocumentation {

	/**
	 * Time spent for the WebSocket handshake on the server.
	 */
	WEBSOCKET_SERVER_HANDSHAKE_TIME {
		@Override
		public String getName() {
			return "reactor.netty.websocket.server.handshake.time";
		}

		@Override
		public KeyName[] getKeyNames() {
			return HandshakeTimeTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.TIMER;
		}
	},

	/**
	 * Time spent in consuming incoming data on the WebSocket server.
	 */
	WEBSOCKET_SERVER_DATA_RECEIVED_TIME {
		@Override
		public String getName() {
			return "reactor.netty.websocket.server.data.received.time";
		}

		@Override
		public KeyName[] getKeyNames() {
			return DataReceivedTimeTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.TIMER;
		}
	},

	/**
	 * Time spent in sending outgoing data from the WebSocket server.
	 */
	WEBSOCKET_SERVER_DATA_SENT_TIME {
		@Override
		public String getName() {
			return "reactor.netty.websocket.server.data.sent.time";
		}

		@Override
		public KeyName[] getKeyNames() {
			return DataSentTimeTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.TIMER;
		}
	},

	/**
	 * Duration of the WebSocket connection on the server.
	 */
	WEBSOCKET_SERVER_CONNECTION_DURATION {
		@Override
		public String getName() {
			return "reactor.netty.websocket.server.connection.duration";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ConnectionDurationTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.TIMER;
		}
	};

	enum HandshakeTimeTags implements KeyName {

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

	enum DataReceivedTimeTags implements KeyName {

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

	enum DataSentTimeTags implements KeyName {

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

	enum ConnectionDurationTags implements KeyName {

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
