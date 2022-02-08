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

import io.micrometer.api.instrument.docs.DocumentedObservation;
import io.micrometer.api.instrument.docs.TagKey;
import io.micrometer.tracing.docs.DocumentedSpan;

/**
 * {@link HttpServer} spans.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum HttpServerSpans implements DocumentedSpan {

	/**
	 * Response Span.
	 */
	HTTP_SERVER_RESPONSE_SPAN {
		@Override
		public String getName() {
			return "%s";
		}

		@Override
		public TagKey[] getTagKeys() {
			return ResponseTimeHighCardinalityTags.values();
		}

		@Override
		public DocumentedObservation overridesDefaultSpanFrom() {
			return HttpServerObservations.HTTP_SERVER_RESPONSE_TIME;
		}
	};

	enum ResponseTimeHighCardinalityTags implements TagKey {

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
		 * Reactor Netty type (always server).
		 */
		REACTOR_NETTY_TYPE {
			@Override
			public String getKey() {
				return "reactor.netty.type";
			}
		},

		/**
		 * Reactor Netty protocol (always http).
		 */
		REACTOR_NETTY_PROTOCOL {
			@Override
			public String getKey() {
				return "reactor.netty.protocol";
			}
		}
	}
}
