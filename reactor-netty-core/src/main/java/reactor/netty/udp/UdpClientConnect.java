/*
 * Copyright (c) 2017-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.udp;

import io.netty.channel.ChannelOption;
import io.netty.util.NetUtil;
import reactor.netty.resources.ConnectionProvider;

import java.net.InetSocketAddress;
import java.util.Collections;

/**
 * Provides the actual {@link UdpClient} instance.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class UdpClientConnect extends UdpClient {

	static final UdpClientConnect INSTANCE = new UdpClientConnect();

	final UdpClientConfig config;

	UdpClientConnect() {
		this.config = new UdpClientConfig(
				ConnectionProvider.newConnection(),
				Collections.singletonMap(ChannelOption.AUTO_READ, false),
				() -> new InetSocketAddress(NetUtil.LOCALHOST, DEFAULT_PORT));
	}

	UdpClientConnect(UdpClientConfig config) {
		this.config = config;
	}

	@Override
	public UdpClientConfig configuration() {
		return config;
	}

	@Override
	protected UdpClient duplicate() {
		return new UdpClientConnect(new UdpClientConfig(config));
	}

	/**
	 * The default port for reactor-netty servers. Defaults to 12012 but can be tuned via
	 * the {@code PORT} <b>environment variable</b>.
	 */
	static final int DEFAULT_PORT = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 12012;
}
