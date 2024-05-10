/*
 * Copyright (c) 2023-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.junit.jupiter.api.Test;
import reactor.netty.tcp.TcpClient;
import reactor.netty.tcp.TcpClientConfig;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportConnectorTest {

	@Test
	@SuppressWarnings("FutureReturnValueIgnored")
	void bind_whenBindException_thenChannelIsUnregistered() {
		final TcpClientConfig transportConfig = TcpClient.newConnection().configuration();
		final Channel channel1 = TransportConnector.bind(
				transportConfig,
				new RecordingChannelInitializer(),
				new InetSocketAddress("localhost", 0),
				false).block(Duration.ofSeconds(5));
		final RecordingChannelInitializer channelInitializer = new RecordingChannelInitializer();
		assertThatThrownBy(() -> TransportConnector.bind(
				transportConfig,
				channelInitializer,
				new InetSocketAddress("localhost", ((InetSocketAddress) channel1.localAddress()).getPort()),
				false).block(Duration.ofSeconds(5)));
		final Channel channel2 = channelInitializer.channel;
		assertThat(channel1.isRegistered()).isTrue();
		assertThat(channel2.isRegistered()).isFalse();
		channel1.close();
	}

	private static class RecordingChannelInitializer extends ChannelInitializer<Channel> {
		Channel channel;
		@Override
		protected void initChannel(final Channel ch) {
			channel = ch;
		}
	}
}