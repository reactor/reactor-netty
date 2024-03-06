/*
 * Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.channel;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * Connect observations.
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum ConnectObservations implements ObservationDocumentation {

	/**
	 * Connect metric.
	 */
	CONNECT_TIME {
		@Override
		public KeyName[] getHighCardinalityKeyNames() {
			return ConnectTimeHighCardinalityTags.values();
		}

		@Override
		public KeyName[] getLowCardinalityKeyNames() {
			return ConnectTimeLowCardinalityTags.values();
		}

		@Override
		public String getName() {
			return "%s";
		}
	};

	/**
	 * Connect High Cardinality Tags.
	 */
	enum ConnectTimeHighCardinalityTags implements KeyName {

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
		 * Reactor Netty protocol (tcp/http etc.).
		 */
		REACTOR_NETTY_PROTOCOL {
			@Override
			public String asString() {
				return "reactor.netty.protocol";
			}
		},

		/**
		 * Reactor Netty status.
		 */
		REACTOR_NETTY_STATUS {
			@Override
			public String asString() {
				return "reactor.netty.status";
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
	 * Connect Low Cardinality Tags.
	 */
	enum ConnectTimeLowCardinalityTags implements KeyName {

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
		}
	}
}
