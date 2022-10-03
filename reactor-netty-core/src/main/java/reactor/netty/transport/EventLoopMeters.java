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
package reactor.netty.transport;

import io.micrometer.common.docs.KeyName;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.docs.MeterDocumentation;

/**
 * {@link io.netty.channel.EventLoop} meters.
 *
 * @author Violeta Georgieva
 * @since 1.1.0
 */
enum EventLoopMeters implements MeterDocumentation {

	/**
	 * Event loop pending scheduled tasks.
	 */
	PENDING_TASKS {
		@Override
		public String getName() {
			return "reactor.netty.eventloop.pending.tasks";
		}

		@Override
		public KeyName[] getKeyNames() {
			return EventLoopMetersTags.values();
		}

		@Override
		public Meter.Type getType() {
			return Meter.Type.GAUGE;
		}
	};

	enum EventLoopMetersTags implements KeyName {
		/**
		 * NAME.
		 */
		NAME {
			@Override
			public String asString() {
				return "name";
			}
		}
	}
}
