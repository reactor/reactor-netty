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

import io.micrometer.api.instrument.Gauge;
import io.micrometer.api.instrument.Meter;
import io.micrometer.api.instrument.Timer;
import io.micrometer.api.instrument.docs.DocumentedMeter;
import io.micrometer.api.instrument.docs.TagKey;

import java.util.function.ToDoubleFunction;

/**
 * {@link HttpServer} meters.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum HttpServerMeters implements DocumentedMeter {

	/**
	 * The number of http connections, on the server, currently processing requests.
	 */
	CONNECTIONS_ACTIVE {
		@Override
		public String getName() {
			return "reactor.netty.http.server.connections.active";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ConnectionsActiveTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * Time spent in consuming incoming data on the server.
	 */
	HTTP_SERVER_DATA_RECEIVED_TIME {
		@Override
		public String getName() {
			return "reactor.netty.http.server.data.received.time";
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
	 * Time spent in sending outgoing data from the server.
	 */
	HTTP_SERVER_DATA_SENT_TIME {
		@Override
		public String getName() {
			return "reactor.netty.http.server.data.sent.time";
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

	enum ConnectionsActiveTags implements TagKey {

		/**
		 * Local address.
		 */
		LOCAL_ADDRESS {
			@Override
			public String getKey() {
				return "local.address";
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

	static <T> Gauge.Builder<T> toGaugeBuilder(DocumentedMeter meter, T obj, ToDoubleFunction<T> f) {
		return Gauge.builder(meter.getName(), obj, f);
	}

	static Timer.Builder toTimerBuilder(String name) {
		return Timer.builder(name);
	}
}
