/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import org.junit.jupiter.api.Test;
import reactor.netty.transport.AddressUtils;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerHttpServerMetricsRecorderTests {
	static final InetSocketAddress ADDRESS_1 = AddressUtils.createUnresolved("127.0.0.1", 80);
	static final InetSocketAddress ADDRESS_2 = AddressUtils.createUnresolved("0:0:0:0:0:0:0:1", 80);

	@Test
	void testGetServerConnectionAdder() {
		LongAdder longAdder1 = MicrometerHttpServerMetricsRecorder.INSTANCE.getServerConnectionAdder(ADDRESS_1);

		LongAdder longAdder2 = MicrometerHttpServerMetricsRecorder.INSTANCE.getServerConnectionAdder(ADDRESS_2);

		assertThat(longAdder1).isNotSameAs(longAdder2);
	}

	@Test
	void testGetActiveStreamsAdder() {
		LongAdder longAdder1 = MicrometerHttpServerMetricsRecorder.INSTANCE.getActiveStreamsAdder(ADDRESS_1);

		LongAdder longAdder2 = MicrometerHttpServerMetricsRecorder.INSTANCE.getActiveStreamsAdder(ADDRESS_2);

		assertThat(longAdder1).isNotSameAs(longAdder2);
	}
}
