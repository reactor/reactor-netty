/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Simon Baslé
 */
public class ConnectionIdleTest {

	@Test
	public void onReadIdleReplaces() {
		EmbeddedChannel channel = new EmbeddedChannel();
		Connection mockContext = () -> channel;

		AtomicLong idle1 = new AtomicLong();
		AtomicLong idle2 = new AtomicLong();

		mockContext.onReadIdle(100, idle1::incrementAndGet);
		mockContext.onReadIdle(150, idle2::incrementAndGet);
		ReactorNetty.InboundIdleStateHandler idleStateHandler =
				(ReactorNetty.InboundIdleStateHandler) channel.pipeline().get(NettyPipeline.OnChannelReadIdle);
		idleStateHandler.onReadIdle.run();

		assertThat(channel.pipeline().names()).isEqualTo(Arrays.asList(
				NettyPipeline.OnChannelReadIdle,
				"DefaultChannelPipeline$TailContext#0"));

		assertThat(idle1.intValue()).isEqualTo(0);
		assertThat(idle2.intValue()).isEqualTo(1);
	}



	@Test
	public void onWriteIdleReplaces() {
		EmbeddedChannel channel = new EmbeddedChannel();
		Connection mockContext = () -> channel;

		AtomicLong idle1 = new AtomicLong();
		AtomicLong idle2 = new AtomicLong();

		mockContext.onWriteIdle(100, idle1::incrementAndGet);
		mockContext.onWriteIdle(150, idle2::incrementAndGet);
		ReactorNetty.OutboundIdleStateHandler idleStateHandler =
				(ReactorNetty.OutboundIdleStateHandler) channel.pipeline().get(NettyPipeline.OnChannelWriteIdle);
		idleStateHandler.onWriteIdle.run();

		assertThat(channel.pipeline().names()).containsExactly(
				NettyPipeline.OnChannelWriteIdle,
				"DefaultChannelPipeline$TailContext#0");

		assertThat(idle1.intValue()).isZero();
		assertThat(idle2.intValue()).isEqualTo(1);
	}
}