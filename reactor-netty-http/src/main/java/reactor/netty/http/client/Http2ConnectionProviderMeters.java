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

import io.micrometer.api.instrument.Gauge;
import io.micrometer.api.instrument.Meter;
import io.micrometer.api.instrument.docs.DocumentedMeter;
import io.micrometer.api.instrument.docs.TagKey;

import java.util.function.ToDoubleFunction;

/**
 * HTTP/2 {@link reactor.netty.resources.ConnectionProvider} meters.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum Http2ConnectionProviderMeters implements DocumentedMeter {

	/**
	 * The number of the active HTTP/2 streams.
	 */
	ACTIVE_STREAMS {
		@Override
		public String getName() {
			return "reactor.netty.connection.provider.active.streams";
		}

		@Override
		public TagKey[] getTagKeys() {
			return Http2ConnectionProviderMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	},

	/**
	 * The number of requests that are waiting for opening HTTP/2 stream.
	 */
	PENDING_STREAMS {
		@Override
		public String getName() {
			return "reactor.netty.connection.provider.pending.streams";
		}

		@Override
		public TagKey[] getTagKeys() {
			return Http2ConnectionProviderMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	};

	enum Http2ConnectionProviderMetersTags implements TagKey {

		/**
		 * ID.
		 */
		ID {
			@Override
			public String getKey() {
				return "id";
			}
		},

		/**
		 * NAME.
		 */
		NAME {
			@Override
			public String getKey() {
				return "name";
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

	static <T> Gauge.Builder<T> toGaugeBuilder(DocumentedMeter meter, T obj, ToDoubleFunction<T> f) {
		return Gauge.builder(meter.getName(), obj, f);
	}
}
