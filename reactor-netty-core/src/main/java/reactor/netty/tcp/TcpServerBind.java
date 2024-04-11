/*
 * Copyright (c) 2017-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.tcp;

import io.netty.channel.ChannelOption;
import reactor.netty.internal.util.MapUtils;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides the actual {@link TcpServer} instance.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class TcpServerBind extends TcpServer {

	static final TcpServerBind INSTANCE = new TcpServerBind();

	final TcpServerConfig config;

	TcpServerBind() {
		Map<ChannelOption<?>, Boolean> childOptions = new HashMap<>(MapUtils.calculateInitialCapacity(2));
		childOptions.put(ChannelOption.AUTO_READ, false);
		childOptions.put(ChannelOption.TCP_NODELAY, true);
		this.config = new TcpServerConfig(
				Collections.singletonMap(ChannelOption.SO_REUSEADDR, true),
				childOptions,
				() -> new InetSocketAddress(DEFAULT_PORT));
	}

	TcpServerBind(TcpServerConfig config) {
		this.config = config;
	}

	@Override
	public TcpServerConfig configuration() {
		return config;
	}

	@Override
	protected TcpServer duplicate() {
		return new TcpServerBind(new TcpServerConfig(config));
	}

	static final int DEFAULT_PORT = 0;
}
