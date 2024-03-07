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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.docs.MeterDocumentation;

/**
 * Channel meters.
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public enum ChannelMeters implements MeterDocumentation {

	/**
	 * The number of all opened connections on the server.
	 */
	CONNECTIONS_TOTAL {
		@Override
		public String getName() {
			return "%s";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ConnectionsTotalMeterTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * Amount of the data received, in bytes.
	 */
	DATA_RECEIVED {
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
			return ChannelMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.DISTRIBUTION_SUMMARY;
		}
	},

	/**
	 * Amount of the data sent, in bytes.
	 */
	DATA_SENT {
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
			return ChannelMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.DISTRIBUTION_SUMMARY;
		}
	},

	/**
	 * Number of errors that occurred.
	 */
	ERRORS_COUNT {
		@Override
		public String getName() {
			return "%s";
		}

		@Override
		public KeyName[] getKeyNames() {
			return ChannelMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.COUNTER;
		}
	};

	public enum ChannelMetersTags implements KeyName {

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
		 * URI.
		 */
		URI {
			@Override
			public String asString() {
				return "uri";
			}
		}
	}

	public enum ConnectionsTotalMeterTags implements KeyName {

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
}
