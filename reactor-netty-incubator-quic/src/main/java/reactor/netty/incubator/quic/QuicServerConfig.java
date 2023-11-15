/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.incubator.quic;

import io.netty.channel.ChannelOption;
import reactor.netty.channel.ChannelMetricsRecorder;

import java.net.SocketAddress;
import java.util.Map;
import java.util.function.Supplier;



public final class QuicServerConfig extends AbstractQuicServerConfig {
	/**
	 * Name prefix that will be used for the QUIC server's metrics
	 * registered in Micrometer's global registry.
	 */
	public static final String QUIC_SERVER_PREFIX = "reactor.netty.quic.server";

	QuicServerConfig(Map<ChannelOption<?>, ?> options, Map<ChannelOption<?>, ?> streamOptions, Supplier<? extends SocketAddress> bindAddress) {
		super(options, streamOptions, bindAddress);
	}

	QuicServerConfig(AbstractQuicServerConfig parent) {
		super(parent);
	}

	@Override
	protected ChannelMetricsRecorder defaultMetricsRecorder() {
		// TODO where we want metrics on QUIC channel or on QUIC stream
		return MicrometerQuicServerMetricsRecorder.getInstance(QUIC_SERVER_PREFIX);
	}
}