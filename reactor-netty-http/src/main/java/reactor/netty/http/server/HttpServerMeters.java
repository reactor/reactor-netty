/*
 * Copyright (c) 2022-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
 * {@link HttpServer} meters.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum HttpServerMeters implements MeterDocumentation {

	/**
	 * The number of http connections, on the server, currently processing requests.
	 */
	CONNECTIONS_ACTIVE {
		@Override
		public String getName() {
			return "reactor.netty.http.server.connections.active";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ConnectionsActiveTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of HTTP/2 streams currently active on the server.
	 */
	STREAMS_ACTIVE {
		@Override
		public String getName() {
			return "reactor.netty.http.server.streams.active";
		}

		@Override
		public KeyName[] getKeyNames() {
			return StreamsActiveTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * Amount of the data received, in bytes.
	 */
	HTTP_SERVER_DATA_RECEIVED {
		@Override
		public String getBaseUnit() {
			return "bytes";
		}

		@Override
		public String getName() {
			return "%s";
		}

		@Override
		public KeyName[] getKeyNames() {
			return HttpServerMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.DISTRIBUTION_SUMMARY;
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
		public KeyName[] getKeyNames() {
			return DataReceivedTimeTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.TIMER;
		}
	},

	/**
	 * Amount of the data sent, in bytes.
	 */
	HTTP_SERVER_DATA_SENT {
		@Override
		public String getBaseUnit() {
			return "bytes";
		}

		@Override
		public String getName() {
			return "%s";
		}

		@Override
		public KeyName[] getKeyNames() {
			return HttpServerMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.DISTRIBUTION_SUMMARY;
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
		public KeyName[] getKeyNames() {
			return DataSentTimeTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.TIMER;
		}
	},

	/**
	 * Number of errors that occurred.
	 */
	HTTP_SERVER_ERRORS_COUNT {
		@Override
		public String getName() {
			return "%s";
		}

		@Override
		public KeyName[] getKeyNames() {
			return HttpServerMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.COUNTER;
		}
	};

	enum StreamsActiveTags implements KeyName {

		/**
		 * Local address.
		 */
		LOCAL_ADDRESS {
			@Override
			public String asString() {
				return "local.address";
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

	enum ConnectionsActiveTags implements KeyName {

		/**
		 * Local address.
		 */
		LOCAL_ADDRESS {
			@Override
			public String asString() {
				return "local.address";
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
		 * METHOD.
		 */
		METHOD {
			@Override
			public String asString() {
				return "method";
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

	enum DataSentTimeTags implements KeyName {

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

	public enum HttpServerMetersTags implements KeyName {

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
