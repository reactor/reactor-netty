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
package reactor.netty.observability;

import io.micrometer.api.instrument.docs.DocumentedObservation;
import io.micrometer.api.instrument.docs.TagKey;
import io.micrometer.tracing.docs.DocumentedSpan;
import reactor.netty.channel.ConnectObservations;

/**
 * Spans.
 */
enum ConnectSpans implements DocumentedSpan {

	/**
	 * Connect Span.
	 */
	CONNECT_SPAN {
		@Override
		public String getName() {
			return "%s";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ConnectTimeHighCardinalityTags.values();
		}

		@Override
		public DocumentedObservation overridesDefaultSpanFrom() {
			return ConnectObservations.CONNECT_TIME;
		}
	};

	// TODO: This is a duplication, maybe we can code to allow reusal?
	/**
	 * Description.
	 */
	enum ConnectTimeHighCardinalityTags implements TagKey {

		/**
		 * Reactor Netty Connect status.
		 */
		REACTOR_NETTY_STATUS {
			@Override
			public String getKey() {
				return "reactor.netty.status";
			}
		},

		/**
		 * Reactor Netty Connect type.
		 */
		REACTOR_NETTY_TYPE {
			@Override
			public String getKey() {
				return "reactor.netty.type";
			}
		},

		/**
		 * Reactor Netty Connect protocol.
		 */
		REACTOR_NETTY_PROTOCOL {
			@Override
			public String getKey() {
				return "reactor.netty.protocol";
			}
		}
	}
}
