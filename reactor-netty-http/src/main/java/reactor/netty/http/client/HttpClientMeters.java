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

import io.micrometer.api.instrument.Meter;
import io.micrometer.api.instrument.Timer;
import io.micrometer.api.instrument.docs.DocumentedMeter;
import io.micrometer.api.instrument.docs.TagKey;

/**
 * {@link HttpClient} meters.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum HttpClientMeters implements DocumentedMeter {

	/**
	 * Time spent in consuming incoming data on the client.
	 */
	HTTP_CLIENT_DATA_RECEIVED_TIME {
		@Override
		public String getName() {
			return "reactor.netty.http.client.data.received.time";
		}

		@Override
		public TagKey[] getTagKeys() {
			return DataReceivedTimeTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.TIMER;
		}
	},

	/**
	 * Time spent in sending outgoing data from the client.
	 */
	HTTP_CLIENT_DATA_SENT_TIME {
		@Override
		public String getName() {
			return "reactor.netty.http.client.data.sent.time";
		}

		@Override
		public TagKey[] getTagKeys() {
			return DataSentTimeTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.TIMER;
		}
	};

	enum DataReceivedTimeTags implements TagKey {

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

	enum DataSentTimeTags implements TagKey {

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
		 * URI.
		 */
		URI {
			@Override
			public String getKey() {
				return "uri";
			}
		}
	}

	static Timer.Builder toTimerBuilder(String name) {
		return Timer.builder(name);
	}
}
