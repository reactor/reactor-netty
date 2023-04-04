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
package reactor.netty.channel;

import java.time.Duration;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.test.subscriber.TestSubscriber;
import reactor.test.util.RaceTestUtils;

public class FluxReceiveTest {

	@Test
	void disposeAndSubscribeRaceTest() {
		for (int i = 0; i < 100; i++) {
			ChannelOperations<NettyInbound, NettyOutbound> operations =
					new ChannelOperations<>(EmbeddedChannel::new, (connection, newState) -> {
					});
			FluxReceive receive = new FluxReceive(operations);
			TestSubscriber<Object> subscriber = TestSubscriber.create();
			RaceTestUtils.race(receive::dispose, () -> receive.subscribe(subscriber));

			subscriber.block(Duration.ofSeconds(5));
		}
	}
}
