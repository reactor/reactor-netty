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
package reactor.netty.http.client;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * {@link HttpClient} observations.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum HttpClientObservations implements ObservationDocumentation {

	/**
	 * Response metric.
	 */
	HTTP_CLIENT_RESPONSE_TIME {
		@Override
		public KeyName[] getHighCardinalityKeyNames() {
			return ResponseTimeHighCardinalityTags.values();
		}

		@Override
		public KeyName[] getLowCardinalityKeyNames() {
			return ResponseTimeLowCardinalityTags.values();
		}

		@Override
		public String getName() {
			return "reactor.netty.http.client.response.time";
		}
	};

	/**
	 * Response High Cardinality Tags.
	 */
	enum ResponseTimeHighCardinalityTags implements KeyName {

		/**
		 * Reactor Netty protocol (always http).
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
	 * Response Low Cardinality Tags.
	 */
	enum ResponseTimeLowCardinalityTags implements KeyName {

		/**
		 * METHOD.
		 */
		METHOD {
			@Override
			public String asString() {
				return "method";
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
