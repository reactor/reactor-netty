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

import java.util.Collections;

import io.netty.channel.ChannelOption;
import io.netty.util.NetUtil;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.AddressUtils;

/**
 * Provides the actual {@link TcpClient} instance.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class TcpClientConnect extends TcpClient {

	final TcpClientConfig config;

	TcpClientConnect(ConnectionProvider provider) {
		this.config = new TcpClientConfig(
				provider,
				Collections.singletonMap(ChannelOption.AUTO_READ, false),
				() -> AddressUtils.createUnresolved(NetUtil.LOCALHOST.getHostAddress(), DEFAULT_PORT));
	}

	TcpClientConnect(TcpClientConfig config) {
		this.config = config;
	}

	@Override
	public TcpClientConfig configuration() {
		return config;
	}

	@Override
	protected TcpClient duplicate() {
		return new TcpClientConnect(new TcpClientConfig(config));
	}

	/**
	 * The default port for reactor-netty TCP clients. Defaults to 12012 but can be tuned via
	 * the {@code PORT} <b>environment variable</b>.
	 */
	static final int DEFAULT_PORT;
	static {
		int port;
		String portStr = null;
		try {
			portStr = System.getenv("PORT");
			port = portStr != null ? Integer.parseInt(portStr) : 12012;
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid environment variable [PORT=" + portStr + "].", e);
		}
		DEFAULT_PORT = port;
	}
}
