/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.tcp;

import io.micrometer.core.instrument.docs.DocumentedObservation;
import io.micrometer.core.instrument.docs.TagKey;

/**
 * TLS handshake observations.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum TlsHandshakeObservations implements DocumentedObservation {

	/**
	 * TLS handshake metric.
	 */
	TLS_HANDSHAKE_TIME {
		@Override
		public TagKey[] getHighCardinalityTagKeys() {
			return TlsHandshakeTimeHighCardinalityTags.values();
		}

		@Override
		public TagKey[] getLowCardinalityTagKeys() {
			return TlsHandshakeTimeLowCardinalityTags.values();
		}

		@Override
		public String getName() {
			return "%s";
		}
	};

	/**
	 * TLS Handshake High Cardinality Tags.
	 */
	enum TlsHandshakeTimeHighCardinalityTags implements TagKey {

		/**
		 * Reactor Netty protocol (tcp/http etc.).
		 */
		REACTOR_NETTY_PROTOCOL {
			@Override
			public String getKey() {
				return "reactor.netty.protocol";
			}
		},

		/**
		 * Reactor Netty status.
		 */
		REACTOR_NETTY_STATUS {
			@Override
			public String getKey() {
				return "reactor.netty.status";
			}
		},

		/**
		 * Reactor Netty type (client/server).
		 */
		REACTOR_NETTY_TYPE {
			@Override
			public String getKey() {
				return "reactor.netty.type";
			}
		}
	}

	/**
	 * TLS Handshake Low Cardinality Tags.
	 */
	enum TlsHandshakeTimeLowCardinalityTags implements TagKey {

		/**
		 * Remote address.
		 */
		REMOTE_ADDRESS {
			@Override
			public String getKey() {
				return "remote.address";
			}
		},

		/**
		 * STATUS.
		 */
		STATUS {
			@Override
			public String getKey() {
				return "status";
			}
		}
	}
}
