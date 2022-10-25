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
package reactor.netty.http.server;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * {@link HttpServer} observations.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum HttpServerObservations implements ObservationDocumentation {

	/**
	 * Response metric.
	 */
	HTTP_SERVER_RESPONSE_TIME {
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
			return "reactor.netty.http.server.response.time";
		}
	};

	/**
	 * Response High Cardinality Tags.
	 */
	enum ResponseTimeHighCardinalityTags implements KeyName {

		/**
		 * HTTP scheme.
		 */
		HTTP_SCHEME {
			@Override
			public String asString() {
				return "http.scheme";
			}
		},

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
		 * Net host name.
		 */
		NET_HOST_NAME {
			@Override
			public String asString() {
				return "net.host.name";
			}
		},

		/**
		 * Net host port.
		 */
		NET_HOST_PORT {
			@Override
			public String asString() {
				return "net.host.port";
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
