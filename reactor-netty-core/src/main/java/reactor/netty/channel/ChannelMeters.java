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
package reactor.netty.channel;

import io.micrometer.api.instrument.Counter;
import io.micrometer.api.instrument.DistributionSummary;
import io.micrometer.api.instrument.Gauge;
import io.micrometer.api.instrument.Meter;
import io.micrometer.api.instrument.docs.DocumentedMeter;
import io.micrometer.api.instrument.docs.TagKey;

import java.util.function.ToDoubleFunction;

/**
 * Channel meters.
 *
 * @author Marcin Grzejszczak
 * @author Violeta Georgieva
 * @since 1.1.0
 */
public enum ChannelMeters implements DocumentedMeter {

	/**
	 * The number of all opened connections on the server.
	 */
	CONNECTIONS_TOTAL {
		@Override
		public String getName() {
			return "%s";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ConnectionsTotalTags.values();
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
		public TagKey[] getTagKeys() {
			return DataReceivedTags.values();
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
		public TagKey[] getTagKeys() {
			return DataSentTags.values();
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
		public TagKey[] getTagKeys() {
			return ErrorsCountTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.COUNTER;
		}
	};

	public enum ConnectionsTotalTags implements TagKey {

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

	public enum DataReceivedTags implements TagKey {

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

	public enum DataSentTags implements TagKey {

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

	public enum ErrorsCountTags implements TagKey {

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

	public static Counter.Builder toCounterBuilder(String name) {
		return Counter.builder(name);
	}

	public static <T> Gauge.Builder<T> toGaugeBuilder(String name, T obj, ToDoubleFunction<T> f) {
		return Gauge.builder(name, obj, f);
	}

	public static DistributionSummary.Builder toSummaryBuilder(String name, DocumentedMeter meter) {
		return DistributionSummary.builder(name).baseUnit(meter.getBaseUnit());
	}
}
