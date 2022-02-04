/*
 * Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.micrometer.api.instrument.DistributionSummary;
import io.micrometer.api.instrument.Meter;
import io.micrometer.api.instrument.docs.DocumentedMeter;
import io.micrometer.api.instrument.docs.TagKey;

/**
 * Metrics.
 */
public enum ChannelMeters implements DocumentedMeter {

	/**
	 * Metric emitted when data got received.
	 */
	DATA_RECEIVED {
		@Override
		public String getName() {
			return "%s";
		}

		@Override
		public String getBaseUnit() {
			return "bytes";
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.DISTRIBUTION_SUMMARY;
		}

		@Override
		public TagKey[] getTagKeys() {
			return DataReceivedTags.values();
		}
	};

	public static DistributionSummary.Builder toSummaryBuilder(String name, DocumentedMeter meter) {
		return DistributionSummary.builder(name)
				.baseUnit(meter.getBaseUnit());
	}

	/**
	 * Tags for DataReceived.
	 */
	enum DataReceivedTags implements TagKey {

		/**
		 * URI.
		 */
		URI {
			@Override
			public String getKey() {
				return "uri";
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
		}
	}
}
