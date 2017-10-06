/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

/**
 * @author Simon Baslé
 */
public class NettyInboundTest {

	@Test
	public void onReadIdleReplaces() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel();
		Connection mockContext = () -> channel;
		NettyInbound inbound = new NettyInbound() {
			@Override
			public Connection context() {
				return mockContext;
			}

			@Override
			public Flux<?> receiveObject() {
				return Flux.empty();
			}
		};

		AtomicLong idle1 = new AtomicLong();
		AtomicLong idle2 = new AtomicLong();

		inbound.onReadIdle(100, idle1::incrementAndGet);
		inbound.onReadIdle(150, idle2::incrementAndGet);
		ReactorNetty.InboundIdleStateHandler idleStateHandler =
				(ReactorNetty.InboundIdleStateHandler) channel.pipeline().get(NettyPipeline.OnChannelReadIdle);
		idleStateHandler.onReadIdle.run();

		assertThat(channel.pipeline().names(), is(Arrays.asList(
				NettyPipeline.OnChannelReadIdle,
				"DefaultChannelPipeline$TailContext#0")));

		assertThat(idle1.intValue(), is(0));
		assertThat(idle2.intValue(), is(1));
	}

}