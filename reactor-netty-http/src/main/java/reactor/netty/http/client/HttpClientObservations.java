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

import io.micrometer.observation.docs.DocumentedObservation;
import io.micrometer.common.docs.TagKey;

/**
 * {@link HttpClient} observations.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum HttpClientObservations implements DocumentedObservation {

	/**
	 * Response metric.
	 */
	HTTP_CLIENT_RESPONSE_TIME {
		@Override
		public TagKey[] getHighCardinalityTagKeys() {
			return ResponseTimeHighCardinalityTags.values();
		}

		@Override
		public TagKey[] getLowCardinalityTagKeys() {
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
	enum ResponseTimeHighCardinalityTags implements TagKey {

		/**
		 * Reactor Netty protocol (always http).
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
		 * Reactor Netty type (always client).
		 */
		REACTOR_NETTY_TYPE {
			@Override
			public String getKey() {
				return "reactor.netty.type";
			}
		}
	}

	/**
	 * Response Low Cardinality Tags.
	 */
	enum ResponseTimeLowCardinalityTags implements TagKey {

		/**
		 * METHOD.
		 */
		METHOD {
			@Override
			public String getKey() {
				return "method";
			}
		},

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
		},

		/**
		 * URI.
		 */
		URI {
			@Override
			public String getKey() {
				return "uri";
			}
		}
	}
}
